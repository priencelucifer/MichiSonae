from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import asdict
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import uuid4

from michisonae_api.operations import PostgresMaintenance
from michisonae_api.projection import PostgresProjectionWorker
from michisonae_api.settings import Settings, get_settings
from michisonae_api.snapshots import (
    PostgresSnapshotPublisher,
    parse_region_id,
)

RETENTION_CONFIRMATION = "DELETE-EXPIRED-OBSERVATIONS"
PURGE_CONFIRMATION = "PURGE-QUARANTINED-WORK"
REBUILD_CONFIRMATION = "REBUILD-DERIVED-STATE"


def _json(value: Any) -> str:
    return json.dumps(value, default=str, sort_keys=True)


def _cutoff(value: str | None, default: datetime) -> datetime:
    if value is None:
        return default
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("cutoff must include a timezone")
    return parsed.astimezone(UTC)


def _require_production_confirmation(
    settings: Settings,
    supplied: str | None,
    expected: str,
) -> None:
    if settings.environment == "production" and supplied != expected:
        raise ValueError(f"production action requires --confirm {expected}")


async def _rebuild(
    settings: Settings,
    region_id: str | None,
) -> dict[str, Any]:
    projector = PostgresProjectionWorker(
        settings,
        worker_id=f"maintenance-project-{uuid4().hex[:12]}",
    )
    publisher = PostgresSnapshotPublisher(
        settings,
        worker_id=f"maintenance-snapshot-{uuid4().hex[:12]}",
    )
    await asyncio.gather(projector.open(), publisher.open())
    try:
        if region_id is None:
            reset = await projector.rebuild()
        else:
            region_cell = parse_region_id(
                region_id,
                settings.snapshot_region_geohash_precision,
            )
            if region_cell is None:
                raise ValueError("region ID does not match configured precision")
            reset = await projector.rebuild_region(
                region_id=region_id,
                region_cell=region_cell,
            )
        projected = await projector.drain()
        rollups_applied = await projector.apply_retained_rollups(
            region_cell=None if region_id is None else region_cell,
        )
        seeded = await publisher.seed_current_regions()
        published = await publisher.drain()
    finally:
        await asyncio.gather(projector.close(), publisher.close())
    consistency = PostgresMaintenance(settings).consistency()
    if not consistency.is_consistent:
        raise RuntimeError("post-rebuild consistency check failed")
    return {
        "region_id": region_id,
        "reset": asdict(reset),
        "projection": asdict(projected),
        "retained_rollups_applied": rollups_applied,
        "seeded_count": seeded,
        "snapshots": asdict(published),
        "consistency": asdict(consistency),
    }


def main() -> None:
    settings = get_settings()
    parser = argparse.ArgumentParser(
        description="Run guarded MichiSonae backend lifecycle operations.",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    retention = commands.add_parser("retention")
    retention.add_argument("--apply", action="store_true")
    retention.add_argument("--cutoff")
    retention.add_argument("--batch-size", type=int)
    retention.add_argument("--confirm")

    dead_letter = commands.add_parser("dead-letter")
    dead_letter_commands = dead_letter.add_subparsers(
        dest="dead_letter_command",
        required=True,
    )
    dead_status = dead_letter_commands.add_parser("status")
    dead_status.add_argument("--kind", choices=("outbox", "snapshot"), required=True)
    dead_retry = dead_letter_commands.add_parser("retry")
    dead_retry.add_argument("--kind", choices=("outbox", "snapshot"), required=True)
    dead_retry.add_argument("--key", required=True)
    dead_quarantine = dead_letter_commands.add_parser("quarantine")
    dead_quarantine.add_argument(
        "--kind",
        choices=("outbox", "snapshot"),
        required=True,
    )
    dead_quarantine.add_argument("--key", required=True)
    dead_quarantine.add_argument("--reason", required=True)
    dead_purge = dead_letter_commands.add_parser("purge")
    dead_purge.add_argument("--kind", choices=("outbox", "snapshot"), required=True)
    dead_purge.add_argument("--cutoff")
    dead_purge.add_argument("--batch-size", type=int)
    dead_purge.add_argument("--confirm")

    commands.add_parser("check")
    rebuild = commands.add_parser("rebuild")
    rebuild.add_argument("--region")
    rebuild.add_argument("--confirm")

    arguments = parser.parse_args()
    if not settings.database_url:
        parser.error("MICHI_DATABASE_URL is required")
    maintenance = PostgresMaintenance(settings)

    if arguments.command == "retention":
        cutoff = _cutoff(
            arguments.cutoff,
            datetime.now(UTC) - timedelta(days=settings.observation_retention_days),
        )
        if arguments.apply:
            _require_production_confirmation(
                settings,
                arguments.confirm,
                RETENTION_CONFIRMATION,
            )
        retention_result = maintenance.retention(
            cutoff=cutoff,
            dry_run=not arguments.apply,
            batch_size=arguments.batch_size,
        )
        print(_json(asdict(retention_result)))
        return

    if arguments.command == "dead-letter":
        kind = arguments.kind
        if arguments.dead_letter_command == "status":
            print(_json(asdict(maintenance.dead_letter_status(kind))))
            return
        if arguments.dead_letter_command == "retry":
            key: int | str = int(arguments.key) if kind == "outbox" else arguments.key
            dead_result = maintenance.retry_dead_letter(kind, key)
        elif arguments.dead_letter_command == "quarantine":
            key = int(arguments.key) if kind == "outbox" else arguments.key
            dead_result = maintenance.quarantine_dead_letter(
                kind,
                key,
                arguments.reason,
            )
        else:
            _require_production_confirmation(
                settings,
                arguments.confirm,
                PURGE_CONFIRMATION,
            )
            cutoff = _cutoff(
                arguments.cutoff,
                datetime.now(UTC) - timedelta(days=settings.dead_letter_quarantine_days),
            )
            dead_result = maintenance.purge_quarantined(
                kind,
                cutoff=cutoff,
                batch_size=arguments.batch_size,
            )
        print(_json(asdict(dead_result)))
        return

    if arguments.command == "check":
        consistency_result = maintenance.consistency()
        print(_json(asdict(consistency_result)))
        if not consistency_result.is_consistent:
            raise SystemExit(1)
        return

    _require_production_confirmation(
        settings,
        arguments.confirm,
        REBUILD_CONFIRMATION,
    )
    print(_json(asyncio.run(_rebuild(settings, arguments.region))))


if __name__ == "__main__":
    main()
