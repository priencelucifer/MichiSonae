from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from typing import Any, Protocol

from psycopg import Error as PsycopgError
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from michisonae_api.projection import projection_retry_delay
from michisonae_api.settings import Settings

REGION_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
REGION_PATTERN = re.compile(r"^gh(?P<precision>[1-9][0-9]*):(?P<cell>[0-9a-z]+)$")

CLAIM_WORK_SQL = """
WITH claimable AS (
    SELECT region_id
    FROM public.regional_snapshot_work
    WHERE dead_lettered_at IS NULL
      AND next_attempt_at <= clock_timestamp()
      AND (claimed_until IS NULL OR claimed_until < clock_timestamp())
    ORDER BY dirty_at, region_id
    FOR UPDATE SKIP LOCKED
    LIMIT %s
)
UPDATE public.regional_snapshot_work AS work
SET claimed_by = %s,
    claimed_until = clock_timestamp() + make_interval(secs => %s),
    claimed_generation = work.generation,
    last_attempt_at = clock_timestamp(),
    delivery_attempts = work.delivery_attempts + 1,
    last_error = NULL
FROM claimable
WHERE work.region_id = claimable.region_id
RETURNING
    work.region_id,
    work.claimed_generation,
    work.delivery_attempts
"""

RENEW_WORK_SQL = """
UPDATE public.regional_snapshot_work
SET claimed_until = clock_timestamp() + make_interval(secs => %s)
WHERE region_id = %s
  AND claimed_by = %s
  AND claimed_generation = %s
  AND dead_lettered_at IS NULL
RETURNING region_id
"""

LOAD_PUBLIC_PROJECTIONS_SQL = """
SELECT
    cluster_key,
    kind,
    ST_Y(location::geometry) AS latitude,
    ST_X(location::geometry) AS longitude,
    severity,
    confidence,
    contributor_count,
    lifecycle_state,
    match_state,
    road_segment_id,
    first_detected_at,
    last_detected_at,
    policy_version,
    updated_at
FROM public.hazard_projections
WHERE lifecycle_state IN ('provisional', 'confirmed')
  AND location && ST_SetSRID(ST_GeomFromGeoHash(%s), 4326)::geography
  AND ST_Covers(
      ST_SetSRID(ST_GeomFromGeoHash(%s), 4326),
      location::geometry
  )
ORDER BY cluster_key
"""

INSERT_SNAPSHOT_SQL = """
INSERT INTO public.regional_hazard_snapshots (
    region_id,
    version,
    content_hash,
    source_updated_at,
    hazard_count,
    payload
)
VALUES (%s, %s, %s, %s, %s, %s)
ON CONFLICT (region_id, content_hash) DO NOTHING
"""

LOAD_SNAPSHOT_BY_HASH_SQL = """
SELECT
    region_id,
    version,
    generated_at,
    source_updated_at,
    hazard_count,
    payload
FROM public.regional_hazard_snapshots
WHERE region_id = %s AND content_hash = %s
"""

UPSERT_HEAD_SQL = """
INSERT INTO public.regional_snapshot_heads (region_id, version)
VALUES (%s, %s)
ON CONFLICT (region_id) DO UPDATE
SET version = EXCLUDED.version,
    updated_at = CASE
        WHEN regional_snapshot_heads.version <> EXCLUDED.version
        THEN clock_timestamp()
        ELSE regional_snapshot_heads.updated_at
    END
"""

COMPLETE_WORK_SQL = """
DELETE FROM public.regional_snapshot_work
WHERE region_id = %s
  AND claimed_by = %s
  AND claimed_generation = %s
RETURNING region_id
"""

RECORD_WORK_FAILURE_SQL = """
UPDATE public.regional_snapshot_work
SET claimed_by = NULL,
    claimed_until = NULL,
    claimed_generation = NULL,
    next_attempt_at = CASE
        WHEN delivery_attempts >= %s THEN next_attempt_at
        ELSE clock_timestamp() + %s::interval
    END,
    last_error = %s,
    dead_lettered_at = CASE
        WHEN delivery_attempts >= %s THEN clock_timestamp()
        ELSE NULL
    END,
    dead_letter_reason = CASE
        WHEN delivery_attempts >= %s THEN %s
        ELSE NULL
    END
WHERE region_id = %s
  AND claimed_by = %s
  AND claimed_generation = %s
  AND dead_lettered_at IS NULL
RETURNING dead_lettered_at IS NOT NULL
"""

SNAPSHOT_STATUS_SQL = """
SELECT
    count(*) FILTER (WHERE dead_lettered_at IS NULL)::integer,
    count(*) FILTER (
        WHERE dead_lettered_at IS NULL
          AND claimed_until >= clock_timestamp()
    )::integer,
    count(*) FILTER (WHERE dead_lettered_at IS NOT NULL)::integer,
    COALESCE(
        EXTRACT(
            epoch FROM clock_timestamp() - min(dirty_at)
                FILTER (WHERE dead_lettered_at IS NULL)
        ),
        0
    )::double precision,
    (SELECT count(*)::integer FROM public.regional_hazard_snapshots),
    (SELECT count(*)::integer FROM public.regional_snapshot_heads)
FROM public.regional_snapshot_work
"""

SEED_REGIONS_SQL = """
INSERT INTO public.regional_snapshot_work (region_id)
SELECT DISTINCT
    %s || ST_GeoHash(location::geometry, %s)
FROM public.hazard_projections
ON CONFLICT (region_id) DO UPDATE
SET generation = regional_snapshot_work.generation + 1,
    dirty_at = clock_timestamp(),
    next_attempt_at = clock_timestamp(),
    delivery_attempts = CASE
        WHEN regional_snapshot_work.dead_lettered_at IS NOT NULL THEN 0
        ELSE regional_snapshot_work.delivery_attempts
    END,
    last_error = NULL,
    dead_lettered_at = NULL,
    dead_letter_reason = NULL,
    quarantined_at = NULL,
    quarantine_reason = NULL
"""

LOAD_CURRENT_SNAPSHOT_SQL = """
SELECT
    snapshot.region_id,
    snapshot.version,
    snapshot.generated_at,
    snapshot.source_updated_at,
    snapshot.hazard_count,
    snapshot.payload
FROM public.regional_snapshot_heads AS head
JOIN public.regional_hazard_snapshots AS snapshot
  ON snapshot.region_id = head.region_id
 AND snapshot.version = head.version
WHERE head.region_id = %s
"""

LOAD_VERSIONED_SNAPSHOT_SQL = """
SELECT
    region_id,
    version,
    generated_at,
    source_updated_at,
    hazard_count,
    payload
FROM public.regional_hazard_snapshots
WHERE region_id = %s AND version = %s
"""


class SnapshotUnavailable(RuntimeError):
    """Raised when regional snapshots cannot be read or generated safely."""


class SnapshotClaimLost(RuntimeError):
    """Raised when a publisher no longer owns a leased region."""


@dataclass(frozen=True)
class SnapshotWorkClaim:
    region_id: str
    generation: int
    attempt: int


@dataclass(frozen=True)
class SnapshotRecord:
    region_id: str
    version: str
    generated_at: datetime
    source_updated_at: datetime | None
    hazard_count: int
    payload: dict[str, Any]


@dataclass(frozen=True)
class SnapshotRunResult:
    claimed_count: int
    published_count: int
    unchanged_count: int
    retry_count: int
    dead_letter_count: int


@dataclass(frozen=True)
class SnapshotStatus:
    pending_count: int
    claimed_count: int
    dead_letter_count: int
    oldest_pending_seconds: float
    snapshot_count: int
    region_count: int


class HazardSnapshotStore(Protocol):
    async def open(self) -> None: ...

    async def close(self) -> None: ...

    async def get(
        self,
        region_id: str,
        version: str | None = None,
    ) -> SnapshotRecord | None: ...


class PostgresHazardSnapshotStore:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._pool = _pool(settings, "michisonae-snapshot-reader")

    async def open(self) -> None:
        await self._pool.open(wait=False)

    async def close(self) -> None:
        await self._pool.close()

    async def get(
        self,
        region_id: str,
        version: str | None = None,
    ) -> SnapshotRecord | None:
        query = LOAD_VERSIONED_SNAPSHOT_SQL if version is not None else LOAD_CURRENT_SNAPSHOT_SQL
        parameters = (region_id, version) if version is not None else (region_id,)
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(query, parameters)
                row = await cursor.fetchone()
        except PsycopgError as error:
            raise SnapshotUnavailable("regional snapshot read failed") from error
        return None if row is None else _snapshot_record(row)


class PostgresSnapshotPublisher:
    def __init__(self, settings: Settings, worker_id: str) -> None:
        if not worker_id.strip():
            raise ValueError("worker_id must not be empty")
        self._settings = settings
        self._worker_id = worker_id
        self._pool = _pool(settings, f"michisonae-snapshot-{worker_id[:32]}")

    async def open(self) -> None:
        await self._pool.open(wait=False)

    async def close(self) -> None:
        await self._pool.close()

    async def seed_current_regions(self) -> int:
        precision = self._settings.snapshot_region_geohash_precision
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    SEED_REGIONS_SQL,
                    (f"gh{precision}:", precision),
                )
                return int(cursor.rowcount)
        except PsycopgError as error:
            raise SnapshotUnavailable("regional snapshot seeding failed") from error

    async def run_once(self) -> SnapshotRunResult:
        claims = await self._claim_batch()
        published_count = 0
        unchanged_count = 0
        retry_count = 0
        dead_letter_count = 0
        for claim in claims:
            try:
                published = await self._process_claim(claim)
            except Exception as error:
                if await self._record_failure(claim, error):
                    dead_letter_count += 1
                else:
                    retry_count += 1
            else:
                if published:
                    published_count += 1
                else:
                    unchanged_count += 1
        return SnapshotRunResult(
            claimed_count=len(claims),
            published_count=published_count,
            unchanged_count=unchanged_count,
            retry_count=retry_count,
            dead_letter_count=dead_letter_count,
        )

    async def drain(self) -> SnapshotRunResult:
        total = SnapshotRunResult(0, 0, 0, 0, 0)
        while True:
            current = await self.run_once()
            total = SnapshotRunResult(
                claimed_count=total.claimed_count + current.claimed_count,
                published_count=total.published_count + current.published_count,
                unchanged_count=total.unchanged_count + current.unchanged_count,
                retry_count=total.retry_count + current.retry_count,
                dead_letter_count=total.dead_letter_count + current.dead_letter_count,
            )
            current_status = await self.status()
            if current_status.pending_count == 0:
                return total
            if current.claimed_count == 0:
                await asyncio.sleep(self._settings.snapshot_poll_seconds)

    async def status(self) -> SnapshotStatus:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(SNAPSHOT_STATUS_SQL)
                row = await cursor.fetchone()
        except PsycopgError as error:
            raise SnapshotUnavailable("regional snapshot status failed") from error
        if row is None:
            raise SnapshotUnavailable("regional snapshot status returned no result")
        return SnapshotStatus(
            pending_count=int(row[0]),
            claimed_count=int(row[1]),
            dead_letter_count=int(row[2]),
            oldest_pending_seconds=float(row[3]),
            snapshot_count=int(row[4]),
            region_count=int(row[5]),
        )

    async def _claim_batch(self) -> tuple[SnapshotWorkClaim, ...]:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    CLAIM_WORK_SQL,
                    (
                        self._settings.snapshot_batch_size,
                        self._worker_id,
                        self._settings.snapshot_lease_seconds,
                    ),
                )
                rows = await cursor.fetchall()
        except PsycopgError as error:
            raise SnapshotUnavailable("regional snapshot claim failed") from error
        return tuple(
            SnapshotWorkClaim(
                region_id=str(row[0]),
                generation=int(row[1]),
                attempt=int(row[2]),
            )
            for row in rows
        )

    async def _process_claim(self, claim: SnapshotWorkClaim) -> bool:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    renew_cursor = await connection.execute(
                        RENEW_WORK_SQL,
                        (
                            self._settings.snapshot_lease_seconds,
                            claim.region_id,
                            self._worker_id,
                            claim.generation,
                        ),
                    )
                    if await renew_cursor.fetchone() is None:
                        raise SnapshotClaimLost("regional snapshot lease was lost")

                    region_cell = parse_region_id(
                        claim.region_id,
                        self._settings.snapshot_region_geohash_precision,
                    )
                    if region_cell is None:
                        raise SnapshotUnavailable("snapshot work has an invalid region")
                    projection_cursor = await connection.execute(
                        LOAD_PUBLIC_PROJECTIONS_SQL,
                        (region_cell, region_cell),
                    )
                    rows = await projection_cursor.fetchall()
                    payload = snapshot_content(claim.region_id, rows)
                    canonical = canonical_snapshot_bytes(payload)
                    content_hash = sha256(canonical).digest()
                    version = content_hash.hex()
                    source_updated_at = max(
                        (row[13] for row in rows),
                        default=None,
                    )
                    previous_cursor = await connection.execute(
                        LOAD_CURRENT_SNAPSHOT_SQL,
                        (claim.region_id,),
                    )
                    previous = await previous_cursor.fetchone()
                    previous_version = None if previous is None else str(previous[1])

                    await connection.execute(
                        INSERT_SNAPSHOT_SQL,
                        (
                            claim.region_id,
                            version,
                            content_hash,
                            source_updated_at,
                            len(rows),
                            Jsonb(payload),
                        ),
                    )
                    snapshot_cursor = await connection.execute(
                        LOAD_SNAPSHOT_BY_HASH_SQL,
                        (claim.region_id, content_hash),
                    )
                    if await snapshot_cursor.fetchone() is None:
                        raise SnapshotUnavailable("stored snapshot could not be reloaded")
                    await connection.execute(
                        UPSERT_HEAD_SQL,
                        (claim.region_id, version),
                    )
                    completed_cursor = await connection.execute(
                        COMPLETE_WORK_SQL,
                        (
                            claim.region_id,
                            self._worker_id,
                            claim.generation,
                        ),
                    )
                    if await completed_cursor.fetchone() is None:
                        raise SnapshotClaimLost(
                            "regional snapshot lease was lost before completion"
                        )
        except (SnapshotClaimLost, SnapshotUnavailable):
            raise
        except PsycopgError as error:
            raise SnapshotUnavailable("regional snapshot transaction failed") from error
        return previous_version != version

    async def _record_failure(
        self,
        claim: SnapshotWorkClaim,
        error: Exception,
    ) -> bool:
        failure_code = snapshot_failure_code(error)
        delay = projection_retry_delay(
            attempt=claim.attempt,
            base_seconds=self._settings.snapshot_retry_base_seconds,
            maximum_seconds=self._settings.snapshot_retry_max_seconds,
        )
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    RECORD_WORK_FAILURE_SQL,
                    (
                        self._settings.snapshot_max_attempts,
                        str(timedelta(seconds=delay)),
                        failure_code,
                        self._settings.snapshot_max_attempts,
                        self._settings.snapshot_max_attempts,
                        failure_code,
                        claim.region_id,
                        self._worker_id,
                        claim.generation,
                    ),
                )
                row = await cursor.fetchone()
        except PsycopgError as database_error:
            raise SnapshotUnavailable(
                "regional snapshot failure recording failed"
            ) from database_error
        if row is None:
            raise SnapshotClaimLost("regional snapshot lease was lost on failure")
        return bool(row[0])


def parse_region_id(region_id: str, expected_precision: int) -> str | None:
    match = REGION_PATTERN.fullmatch(region_id)
    if match is None:
        return None
    precision = int(match.group("precision"))
    cell = match.group("cell")
    if precision != expected_precision or len(cell) != precision:
        return None
    if any(character not in REGION_ALPHABET for character in cell):
        return None
    return cell


def canonical_snapshot_bytes(payload: dict[str, Any]) -> bytes:
    return json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def snapshot_content(region_id: str, rows: list[Any]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "region_id": region_id,
        "coverage": {
            "status": "unknown",
            "basis": "community_observations_only",
        },
        "hazards": [
            {
                "hazard_id": sha256(str(row[0]).encode("utf-8")).hexdigest()[:24],
                "kind": str(row[1]),
                "latitude": round(float(row[2]), 6),
                "longitude": round(float(row[3]), 6),
                "severity": round(float(row[4]), 3),
                "confidence": round(float(row[5]), 3),
                "contributor_count": int(row[6]),
                "lifecycle_state": str(row[7]),
                "match_state": str(row[8]),
                "road_segment_id": None if row[9] is None else str(row[9]),
                "first_detected_at": _canonical_datetime(row[10]),
                "last_detected_at": _canonical_datetime(row[11]),
                "policy_version": str(row[12]),
            }
            for row in rows
        ],
    }


def snapshot_failure_code(error: Exception) -> str:
    if isinstance(error, SnapshotClaimLost):
        return "snapshot_claim_lost"
    if isinstance(error, SnapshotUnavailable):
        return "snapshot_processing_unavailable"
    return "snapshot_processing_error"


def empty_snapshot_content(region_id: str) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "region_id": region_id,
        "coverage": {
            "status": "unknown",
            "basis": "no_published_snapshot",
        },
        "hazards": [],
    }


def empty_snapshot_etag(region_id: str) -> str:
    return sha256(canonical_snapshot_bytes(empty_snapshot_content(region_id))).hexdigest()


def _snapshot_record(row: Any) -> SnapshotRecord:
    payload = row[5]
    if not isinstance(payload, dict):
        raise SnapshotUnavailable("regional snapshot payload is invalid")
    return SnapshotRecord(
        region_id=str(row[0]),
        version=str(row[1]).strip(),
        generated_at=row[2],
        source_updated_at=row[3],
        hazard_count=int(row[4]),
        payload=payload,
    )


def _canonical_datetime(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _pool(settings: Settings, name: str) -> AsyncConnectionPool[Any]:
    if not settings.database_url:
        raise ValueError("database_url is required for regional snapshots")
    return AsyncConnectionPool(
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
        name=name,
    )
