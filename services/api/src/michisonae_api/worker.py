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
from michisonae_api.projection import (
    PostgresProjectionWorker,
    ProjectionRunResult,
)
from michisonae_api.settings import Settings, get_settings

logger = logging.getLogger(__name__)


def _worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"


def _observe_result(
    metrics: BackendMetrics,
    result: ProjectionRunResult,
) -> None:
    metrics.observe_worker(
        "projection",
        {
            "claimed": result.claimed_count,
            "projected": result.projected_count,
            "replayed": result.replayed_count,
            "retry": result.retry_count,
            "dead_letter": result.dead_letter_count,
        },
    )


async def _refresh_status(
    worker: PostgresProjectionWorker,
    metrics: BackendMetrics,
) -> None:
    current = await worker.status()
    metrics.set_queue(
        "projection",
        pending_count=current.pending_count,
        dead_letter_count=current.dead_letter_count,
        oldest_pending_seconds=current.oldest_pending_seconds,
    )
    metrics.set_pool("projection", worker.pool_stats())


async def _close_worker(
    worker: PostgresProjectionWorker,
    settings: Settings,
) -> None:
    try:
        await asyncio.wait_for(
            worker.close(),
            timeout=settings.worker_shutdown_timeout_seconds,
        )
    except TimeoutError:
        logger.error(
            "projection_shutdown_timeout",
            extra={
                "component": "projection",
                "failure_code": "shutdown_timeout",
            },
        )


async def _run(arguments: argparse.Namespace, settings: Settings) -> None:
    metrics = BackendMetrics()
    state = WorkerHealthState("projection", metrics)
    continuous = not any(
        (
            arguments.status,
            arguments.rebuild,
            arguments.once,
            arguments.drain,
        )
    )
    server = (
        WorkerHealthServer(
            host=settings.worker_health_host,
            port=settings.projection_health_port,
            state=state,
            metrics=metrics,
        )
        if continuous
        else None
    )
    if server is not None:
        server.start()

    worker = PostgresProjectionWorker(settings, worker_id=_worker_id())
    try:
        await worker.open()
        state.set_started(True)
        ready = await worker.ready()
        state.set_ready(ready)
        if not ready:
            raise RuntimeError("projection database or required schema is unavailable")

        if arguments.status:
            current = await worker.status()
            print(json.dumps(asdict(current), sort_keys=True))
            return

        if arguments.rebuild:
            print(json.dumps(asdict(await worker.rebuild()), sort_keys=True))

        if arguments.once:
            result = await worker.run_once()
            _observe_result(metrics, result)
            print(json.dumps(asdict(result), sort_keys=True))
            return

        if arguments.drain or arguments.rebuild:
            result = await worker.drain()
            _observe_result(metrics, result)
            print(json.dumps(asdict(result), sort_keys=True))
            return

        stop_event = asyncio.Event()
        install_shutdown_handlers(stop_event)
        logger.info(
            "projection_worker_started",
            extra={"component": "projection", "worker_kind": "projection"},
        )
        async for result in graceful_worker_loop(
            worker.run_once,
            stop_event=stop_event,
            idle_seconds=settings.projection_poll_seconds,
            idle_when=lambda current: current.claimed_count == 0,
        ):
            _observe_result(metrics, result)
            await _refresh_status(worker, metrics)
    finally:
        state.set_ready(False)
        await _close_worker(worker, settings)
        state.set_started(False)
        if server is not None:
            server.close()
        logger.info(
            "projection_worker_stopped",
            extra={"component": "projection", "worker_kind": "projection"},
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Project durable observations into current MichiSonae hazards.",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--once", action="store_true", help="Process at most one batch.")
    mode.add_argument("--drain", action="store_true", help="Run until no pending work remains.")
    mode.add_argument("--status", action="store_true", help="Print queue status and exit.")
    mode.add_argument(
        "--rebuild",
        action="store_true",
        help="Reset derived state and deterministically drain retained observations.",
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
