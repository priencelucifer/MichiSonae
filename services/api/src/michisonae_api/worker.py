from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
from dataclasses import asdict
from uuid import uuid4

from michisonae_api.projection import PostgresProjectionWorker
from michisonae_api.settings import Settings, get_settings


def _worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"


async def _run(arguments: argparse.Namespace, settings: Settings) -> None:
    worker = PostgresProjectionWorker(settings, worker_id=_worker_id())
    await worker.open()
    try:
        if arguments.status:
            print(json.dumps(asdict(await worker.status()), sort_keys=True))
            return

        if arguments.rebuild:
            print(json.dumps(asdict(await worker.rebuild()), sort_keys=True))

        if arguments.once:
            print(json.dumps(asdict(await worker.run_once()), sort_keys=True))
            return

        if arguments.drain or arguments.rebuild:
            print(json.dumps(asdict(await worker.drain()), sort_keys=True))
            return

        while True:
            result = await worker.run_once()
            if result.claimed_count == 0:
                await asyncio.sleep(settings.projection_poll_seconds)
    finally:
        await worker.close()


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

    try:
        asyncio.run(_run(arguments, settings))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
