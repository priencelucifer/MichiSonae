from __future__ import annotations

import json
from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Protocol
from uuid import UUID

from psycopg import Error as PsycopgError
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from michisonae_api.migration import expected_migration
from michisonae_api.models import ObservationBatch, RoadObservation
from michisonae_api.settings import Settings

INSERT_BATCH_SQL = """
WITH input AS (
    SELECT
        event_id,
        installation_id,
        detected_at,
        latitude,
        longitude,
        location_accuracy_m,
        speed_mps,
        kind,
        severity,
        confidence,
        source,
        detector_version,
        payload,
        decode(payload_sha256, 'hex') AS payload_sha256
    FROM jsonb_to_recordset(%s::jsonb) AS record(
        event_id uuid,
        installation_id text,
        detected_at timestamptz,
        latitude double precision,
        longitude double precision,
        location_accuracy_m real,
        speed_mps real,
        kind text,
        severity real,
        confidence real,
        source text,
        detector_version text,
        payload jsonb,
        payload_sha256 text
    )
),
inserted_observations AS (
    INSERT INTO public.road_observations (
        event_id,
        installation_id,
        detected_at,
        location,
        location_accuracy_m,
        speed_mps,
        kind,
        severity,
        confidence,
        source,
        detector_version,
        payload,
        payload_sha256
    )
    SELECT
        event_id,
        installation_id,
        detected_at,
        ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
        location_accuracy_m,
        speed_mps,
        kind,
        severity,
        confidence,
        source,
        detector_version,
        payload,
        payload_sha256
    FROM input
    ON CONFLICT (event_id) DO NOTHING
    RETURNING event_id, payload
),
queued_events AS (
    INSERT INTO public.observation_outbox (
        observation_event_id,
        payload
    )
    SELECT
        event_id,
        jsonb_build_object(
            'schema_version', '1.0',
            'observation', payload
        )
    FROM inserted_observations
    RETURNING observation_event_id
)
SELECT
    (SELECT count(*) FROM inserted_observations),
    (SELECT count(*) FROM queued_events)
"""

FIND_CONFLICTS_SQL = """
WITH input AS (
    SELECT
        event_id,
        decode(payload_sha256, 'hex') AS payload_sha256
    FROM jsonb_to_recordset(%s::jsonb) AS record(
        event_id uuid,
        payload_sha256 text
    )
)
SELECT stored.event_id
FROM input
JOIN public.road_observations AS stored USING (event_id)
WHERE stored.payload_sha256 <> input.payload_sha256
ORDER BY stored.event_id
"""

READINESS_SQL = """
SELECT EXISTS (
    SELECT 1
    FROM public.schema_migrations
    WHERE version = %s AND checksum = %s
)
"""


class StoreUnavailable(RuntimeError):
    """Raised when durable storage cannot safely accept a request."""


class EventIdConflict(RuntimeError):
    def __init__(self, event_ids: tuple[UUID, ...]) -> None:
        self.event_ids = event_ids
        super().__init__("event_id was previously stored with different content")


@dataclass(frozen=True)
class IngestionResult:
    received_count: int
    stored_count: int

    @property
    def duplicate_count(self) -> int:
        return self.received_count - self.stored_count


class ObservationStore(Protocol):
    async def open(self) -> None: ...

    async def close(self) -> None: ...

    async def ready(self) -> bool: ...

    async def ingest(self, batch: ObservationBatch) -> IngestionResult: ...


class PostgresObservationStore:
    def __init__(self, settings: Settings) -> None:
        if not settings.database_url:
            raise ValueError("database_url is required for PostgreSQL storage")

        self._pool: AsyncConnectionPool[Any] = AsyncConnectionPool(
            conninfo=settings.database_url,
            min_size=settings.database_pool_min_size,
            max_size=settings.database_pool_max_size,
            timeout=settings.database_pool_timeout_seconds,
            max_waiting=settings.database_pool_max_size * 10,
            kwargs={
                "autocommit": True,
                "connect_timeout": settings.database_connect_timeout_seconds,
            },
            open=False,
            name="michisonae-observation-store",
        )
        self._timeout_seconds = settings.database_pool_timeout_seconds

    async def open(self) -> None:
        await self._pool.open(wait=False)

    async def close(self) -> None:
        await self._pool.close()

    async def ready(self) -> bool:
        migration = expected_migration()
        try:
            async with self._pool.connection(timeout=self._timeout_seconds) as connection:
                cursor = await connection.execute(
                    READINESS_SQL,
                    (migration.version, migration.checksum),
                )
                row = await cursor.fetchone()
                return bool(row and row[0])
        except PsycopgError:
            return False

    async def ingest(self, batch: ObservationBatch) -> IngestionResult:
        records = [_observation_record(observation) for observation in batch.observations]
        try:
            async with self._pool.connection(timeout=self._timeout_seconds) as connection:
                async with connection.transaction():
                    cursor = await connection.execute(
                        INSERT_BATCH_SQL,
                        (Jsonb(records),),
                    )
                    counts = await cursor.fetchone()
                    if counts is None:
                        raise StoreUnavailable("database returned no ingestion result")
                    stored_count = int(counts[0])
                    queued_count = int(counts[1])
                    if stored_count != queued_count:
                        raise StoreUnavailable("observation and outbox counts diverged")

                    conflict_cursor = await connection.execute(
                        FIND_CONFLICTS_SQL,
                        (Jsonb(records),),
                    )
                    conflicts = tuple(
                        UUID(str(row[0]))
                        for row in await conflict_cursor.fetchall()
                    )
                    if conflicts:
                        raise EventIdConflict(conflicts)
        except (EventIdConflict, StoreUnavailable):
            raise
        except PsycopgError as error:
            raise StoreUnavailable("durable observation transaction failed") from error

        return IngestionResult(
            received_count=len(records),
            stored_count=stored_count,
        )


def _observation_record(observation: RoadObservation) -> dict[str, Any]:
    payload = observation.model_dump(mode="json")
    canonical_payload = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return {
        **payload,
        "payload": payload,
        "payload_sha256": sha256(canonical_payload).hexdigest(),
    }
