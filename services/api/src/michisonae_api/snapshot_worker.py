from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import socket
from dataclasses import asdict
from uuid import uuid4

from michisonae_api.observability import (
    BackendMetrics,
    WorkerHealthServer,
    WorkerHealthState,
    configure_json_logging,
    graceful_worker_loop,
    install_shutdown_handlers,
)
from michisonae_api.settings import Settings, get_settings
from michisonae_api.snapshots import (
    PostgresSnapshotPublisher,
    SnapshotRunResult,
)

logger = logging.getLogger(__name__)


def _worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"


def _observe_result(
    metrics: BackendMetrics,
    result: SnapshotRunResult,
) -> None:
    metrics.observe_worker(
        "snapshot",
        {
            "claimed": result.claimed_count,
            "published": result.published_count,
            "unchanged": result.unchanged_count,
            "retry": result.retry_count,
            "dead_letter": result.dead_letter_count,
        },
    )


async def _refresh_status(
    publisher: PostgresSnapshotPublisher,
    metrics: BackendMetrics,
) -> None:
    current = await publisher.status()
    metrics.set_queue(
        "snapshot",
        pending_count=current.pending_count,
        dead_letter_count=current.dead_letter_count,
        oldest_pending_seconds=current.oldest_pending_seconds,
    )
    metrics.set_pool("snapshot", publisher.pool_stats())


async def _close_publisher(
    publisher: PostgresSnapshotPublisher,
    settings: Settings,
) -> None:
    try:
        await asyncio.wait_for(
            publisher.close(),
            timeout=settings.worker_shutdown_timeout_seconds,
        )
    except TimeoutError:
        logger.error(
            "snapshot_shutdown_timeout",
            extra={"component": "snapshot", "failure_code": "shutdown_timeout"},
        )


async def _run(arguments: argparse.Namespace, settings: Settings) -> None:
    metrics = BackendMetrics()
    state = WorkerHealthState("snapshot", metrics)
    continuous = not any(
        (
            arguments.status,
            arguments.seed,
            arguments.once,
            arguments.drain,
        )
    )
    server = (
        WorkerHealthServer(
            host=settings.worker_health_host,
            port=settings.snapshot_health_port,
            state=state,
            metrics=metrics,
        )
        if continuous
        else None
    )
    if server is not None:
        server.start()

    publisher = PostgresSnapshotPublisher(settings, _worker_id())
    try:
        await publisher.open()
        state.set_started(True)
        ready = await publisher.ready()
        state.set_ready(ready)
        if not ready:
            raise RuntimeError("snapshot database or required schema is unavailable")

        if arguments.status:
            current = await publisher.status()
            print(json.dumps(asdict(current), sort_keys=True))
            return
        if arguments.seed:
            print(json.dumps({"seeded_count": await publisher.seed_current_regions()}))
        if arguments.once:
            result = await publisher.run_once()
            _observe_result(metrics, result)
            print(json.dumps(asdict(result), sort_keys=True))
            return
        if arguments.drain or arguments.seed:
            result = await publisher.drain()
            _observe_result(metrics, result)
            print(json.dumps(asdict(result), sort_keys=True))
            return

        await publisher.seed_current_regions()
        stop_event = asyncio.Event()
        install_shutdown_handlers(stop_event)
        logger.info(
            "snapshot_worker_started",
            extra={"component": "snapshot", "worker_kind": "snapshot"},
        )
        async for result in graceful_worker_loop(
            publisher.run_once,
            stop_event=stop_event,
            idle_seconds=settings.snapshot_poll_seconds,
            idle_when=lambda current: current.claimed_count == 0,
        ):
            _observe_result(metrics, result)
            await _refresh_status(publisher, metrics)
    finally:
        state.set_ready(False)
        await _close_publisher(publisher, settings)
        state.set_started(False)
        if server is not None:
            server.close()
        logger.info(
            "snapshot_worker_stopped",
            extra={"component": "snapshot", "worker_kind": "snapshot"},
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Publish immutable regional MichiSonae hazard snapshots.",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--once", action="store_true", help="Publish at most one batch.")
    mode.add_argument("--drain", action="store_true", help="Publish all pending regions.")
    mode.add_argument("--status", action="store_true", help="Print publisher status.")
    mode.add_argument(
        "--seed",
        action="store_true",
        help="Mark every current projection region dirty and drain it.",
    )
    arguments = parser.parse_args()
    settings = get_settings()
    if not settings.database_url:
        parser.error("database URL required via MICHI_DATABASE_URL")
    if settings.json_logging_enabled:
        configure_json_logging(settings.log_level)
    try:
        asyncio.run(_run(arguments, settings))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
