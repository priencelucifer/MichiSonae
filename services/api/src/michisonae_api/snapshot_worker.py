from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
from dataclasses import asdict
from uuid import uuid4

from michisonae_api.settings import get_settings
from michisonae_api.snapshots import PostgresSnapshotPublisher


def _worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"


async def _run(arguments: argparse.Namespace) -> None:
    settings = get_settings()
    publisher = PostgresSnapshotPublisher(settings, _worker_id())
    await publisher.open()
    try:
        if arguments.status:
            print(json.dumps(asdict(await publisher.status()), sort_keys=True))
            return
        if arguments.seed:
            print(json.dumps({"seeded_count": await publisher.seed_current_regions()}))
        if arguments.once:
            print(json.dumps(asdict(await publisher.run_once()), sort_keys=True))
            return
        if arguments.drain or arguments.seed:
            print(json.dumps(asdict(await publisher.drain()), sort_keys=True))
            return

        await publisher.seed_current_regions()
        while True:
            result = await publisher.run_once()
            if result.claimed_count == 0:
                await asyncio.sleep(settings.snapshot_poll_seconds)
    finally:
        await publisher.close()


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
    if not get_settings().database_url:
        parser.error("database URL required via MICHI_DATABASE_URL")
    try:
        asyncio.run(_run(arguments))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
