from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import timedelta
from typing import Any
from uuid import UUID

from psycopg import Error as PsycopgError
from psycopg_pool import AsyncConnectionPool

from michisonae_api.settings import Settings

PROJECTION_POLICY_VERSION = "projection-v1"
PROJECTION_REBUILD_LOCK_ID = 5_432_240_840_150_319_487

CLAIM_OUTBOX_SQL = """
WITH claimable AS (
    SELECT id
    FROM public.observation_outbox
    WHERE published_at IS NULL
      AND dead_lettered_at IS NULL
      AND next_attempt_at <= clock_timestamp()
      AND (claimed_until IS NULL OR claimed_until < clock_timestamp())
    ORDER BY id
    FOR UPDATE SKIP LOCKED
    LIMIT %s
)
UPDATE public.observation_outbox AS item
SET claimed_by = %s,
    claimed_until = clock_timestamp() + make_interval(secs => %s),
    last_attempt_at = clock_timestamp(),
    delivery_attempts = item.delivery_attempts + 1,
    last_error = NULL
FROM claimable
WHERE item.id = claimable.id
RETURNING item.id, item.observation_event_id, item.delivery_attempts
"""

RENEW_CLAIM_SQL = """
UPDATE public.observation_outbox
SET claimed_until = clock_timestamp() + make_interval(secs => %s)
WHERE id = %s
  AND claimed_by = %s
  AND published_at IS NULL
  AND dead_lettered_at IS NULL
RETURNING observation_event_id
"""

LOAD_CLAIMED_OBSERVATION_SQL = """
SELECT
    item.observation_event_id,
    observation.installation_id,
    observation.detected_at,
    observation.kind,
    observation.severity,
    observation.confidence,
    ST_GeoHash(observation.location::geometry, %s) AS spatial_cell
FROM public.observation_outbox AS item
JOIN public.road_observations AS observation
  ON observation.event_id = item.observation_event_id
WHERE item.id = %s
  AND item.claimed_by = %s
  AND item.published_at IS NULL
  AND item.dead_lettered_at IS NULL
"""

INSERT_CLUSTER_SQL = """
INSERT INTO public.hazard_clusters (
    cluster_key,
    kind,
    spatial_cell
)
VALUES (%s, %s, %s)
ON CONFLICT (cluster_key) DO NOTHING
"""

LOCK_CLUSTER_SQL = """
SELECT cluster_key
FROM public.hazard_clusters
WHERE cluster_key = %s
FOR UPDATE
"""

INSERT_PROCESSED_EVENT_SQL = """
INSERT INTO public.projection_processed_events (
    event_id,
    outbox_id,
    cluster_key
)
VALUES (%s, %s, %s)
ON CONFLICT (event_id) DO NOTHING
RETURNING event_id
"""

UPSERT_CONTRIBUTOR_SQL = """
INSERT INTO public.hazard_contributors (
    cluster_key,
    installation_id,
    latest_event_id,
    first_detected_at,
    last_detected_at,
    latest_location,
    latest_severity,
    latest_confidence,
    observation_count
)
SELECT
    %s,
    observation.installation_id,
    observation.event_id,
    observation.detected_at,
    observation.detected_at,
    observation.location,
    observation.severity,
    observation.confidence,
    1
FROM public.road_observations AS observation
WHERE observation.event_id = %s
ON CONFLICT (cluster_key, installation_id) DO UPDATE
SET latest_event_id = CASE
        WHEN (EXCLUDED.last_detected_at, EXCLUDED.latest_event_id)
           > (hazard_contributors.last_detected_at, hazard_contributors.latest_event_id)
        THEN EXCLUDED.latest_event_id
        ELSE hazard_contributors.latest_event_id
    END,
    first_detected_at = LEAST(
        hazard_contributors.first_detected_at,
        EXCLUDED.first_detected_at
    ),
    last_detected_at = GREATEST(
        hazard_contributors.last_detected_at,
        EXCLUDED.last_detected_at
    ),
    latest_location = CASE
        WHEN (EXCLUDED.last_detected_at, EXCLUDED.latest_event_id)
           > (hazard_contributors.last_detected_at, hazard_contributors.latest_event_id)
        THEN EXCLUDED.latest_location
        ELSE hazard_contributors.latest_location
    END,
    latest_severity = CASE
        WHEN (EXCLUDED.last_detected_at, EXCLUDED.latest_event_id)
           > (hazard_contributors.last_detected_at, hazard_contributors.latest_event_id)
        THEN EXCLUDED.latest_severity
        ELSE hazard_contributors.latest_severity
    END,
    latest_confidence = CASE
        WHEN (EXCLUDED.last_detected_at, EXCLUDED.latest_event_id)
           > (hazard_contributors.last_detected_at, hazard_contributors.latest_event_id)
        THEN EXCLUDED.latest_confidence
        ELSE hazard_contributors.latest_confidence
    END,
    observation_count = hazard_contributors.observation_count + 1,
    updated_at = clock_timestamp()
"""

REFRESH_PROJECTION_SQL = """
WITH aggregate AS (
    SELECT
        cluster.cluster_key,
        cluster.kind,
        cluster.road_segment_id,
        cluster.match_state,
        ST_Centroid(
            ST_Collect(
                contributor.latest_location::geometry
                ORDER BY contributor.installation_id
            )
        )::geography AS location,
        percentile_cont(0.5) WITHIN GROUP (
            ORDER BY contributor.latest_severity
        )::real AS severity,
        LEAST(
            0.99,
            percentile_cont(0.5) WITHIN GROUP (
                ORDER BY contributor.latest_confidence
            ) * CASE count(*)
                WHEN 1 THEN 0.5
                WHEN 2 THEN 0.75
                ELSE 1.0
            END
        )::real AS confidence,
        count(*)::integer AS contributor_count,
        CASE
            WHEN count(*) = 1 THEN 'community_unverified'
            WHEN count(*) = 2 THEN 'provisional'
            ELSE 'confirmed'
        END AS lifecycle_state,
        min(contributor.first_detected_at) AS first_detected_at,
        max(contributor.last_detected_at) AS last_detected_at
    FROM public.hazard_clusters AS cluster
    JOIN public.hazard_contributors AS contributor
      ON contributor.cluster_key = cluster.cluster_key
    WHERE cluster.cluster_key = %s
    GROUP BY
        cluster.cluster_key,
        cluster.kind,
        cluster.road_segment_id,
        cluster.match_state
)
INSERT INTO public.hazard_projections (
    cluster_key,
    kind,
    road_segment_id,
    match_state,
    location,
    severity,
    confidence,
    contributor_count,
    lifecycle_state,
    first_detected_at,
    last_detected_at,
    policy_version
)
SELECT
    cluster_key,
    kind,
    road_segment_id,
    match_state,
    location,
    severity,
    confidence,
    contributor_count,
    lifecycle_state,
    first_detected_at,
    last_detected_at,
    %s
FROM aggregate
ON CONFLICT (cluster_key) DO UPDATE
SET kind = EXCLUDED.kind,
    road_segment_id = EXCLUDED.road_segment_id,
    match_state = EXCLUDED.match_state,
    location = EXCLUDED.location,
    severity = EXCLUDED.severity,
    confidence = EXCLUDED.confidence,
    contributor_count = EXCLUDED.contributor_count,
    lifecycle_state = EXCLUDED.lifecycle_state,
    first_detected_at = EXCLUDED.first_detected_at,
    last_detected_at = EXCLUDED.last_detected_at,
    policy_version = EXCLUDED.policy_version,
    revision = hazard_projections.revision + 1,
    updated_at = clock_timestamp()
"""

ACKNOWLEDGE_OUTBOX_SQL = """
UPDATE public.observation_outbox
SET published_at = clock_timestamp(),
    claimed_by = NULL,
    claimed_until = NULL,
    last_error = NULL
WHERE id = %s
  AND claimed_by = %s
  AND published_at IS NULL
  AND dead_lettered_at IS NULL
RETURNING id
"""

MARK_REGION_DIRTY_SQL = """
INSERT INTO public.regional_snapshot_work (region_id)
VALUES (%s)
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

RECORD_FAILURE_SQL = """
UPDATE public.observation_outbox
SET claimed_by = NULL,
    claimed_until = NULL,
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
WHERE id = %s
  AND claimed_by = %s
  AND published_at IS NULL
  AND dead_lettered_at IS NULL
RETURNING dead_lettered_at IS NOT NULL
"""

STATUS_SQL = """
SELECT
    count(*) FILTER (
        WHERE published_at IS NULL AND dead_lettered_at IS NULL
    )::integer AS pending_count,
    count(*) FILTER (
        WHERE published_at IS NULL
          AND dead_lettered_at IS NULL
          AND claimed_until >= clock_timestamp()
    )::integer AS claimed_count,
    count(*) FILTER (WHERE dead_lettered_at IS NOT NULL)::integer AS dead_letter_count,
    count(*) FILTER (WHERE published_at IS NOT NULL)::integer AS completed_count,
    COALESCE(
        EXTRACT(
            epoch FROM clock_timestamp() - min(created_at) FILTER (
                WHERE published_at IS NULL AND dead_lettered_at IS NULL
            )
        ),
        0
    )::double precision AS oldest_pending_seconds
FROM public.observation_outbox
"""

RESET_PROJECTIONS_SQL = """
TRUNCATE
    public.regional_snapshot_heads,
    public.regional_hazard_snapshots,
    public.regional_snapshot_work,
    public.projection_processed_events,
    public.hazard_projections,
    public.hazard_contributors,
    public.hazard_clusters
"""

DELETE_REGION_PROCESSED_SQL = """
DELETE FROM public.projection_processed_events AS processed
USING public.hazard_clusters AS cluster
WHERE processed.cluster_key = cluster.cluster_key
  AND left(cluster.spatial_cell, %s) = %s
"""

DELETE_REGION_CLUSTERS_SQL = """
DELETE FROM public.hazard_clusters
WHERE left(spatial_cell, %s) = %s
"""

RESET_REGION_OUTBOX_SQL = """
UPDATE public.observation_outbox AS outbox
SET published_at = NULL,
    delivery_attempts = 0,
    next_attempt_at = clock_timestamp(),
    last_error = NULL,
    claimed_by = NULL,
    claimed_until = NULL,
    last_attempt_at = NULL,
    dead_lettered_at = NULL,
    dead_letter_reason = NULL,
    quarantined_at = NULL,
    quarantine_reason = NULL
FROM public.road_observations AS observation
WHERE observation.event_id = outbox.observation_event_id
  AND ST_GeoHash(observation.location::geometry, %s) = %s
"""

DELETE_REGION_SNAPSHOT_HEAD_SQL = """
DELETE FROM public.regional_snapshot_heads
WHERE region_id = %s
"""

DELETE_REGION_SNAPSHOTS_SQL = """
DELETE FROM public.regional_hazard_snapshots
WHERE region_id = %s
"""

DELETE_REGION_SNAPSHOT_WORK_SQL = """
DELETE FROM public.regional_snapshot_work
WHERE region_id = %s
"""

RESET_OUTBOX_SQL = """
UPDATE public.observation_outbox
SET published_at = NULL,
    delivery_attempts = 0,
    next_attempt_at = clock_timestamp(),
    last_error = NULL,
    claimed_by = NULL,
    claimed_until = NULL,
    last_attempt_at = NULL,
    dead_lettered_at = NULL,
    dead_letter_reason = NULL,
    quarantined_at = NULL,
    quarantine_reason = NULL
"""

APPLY_RETAINED_CONTRIBUTORS_SQL = """
WITH retained_counts AS (
    SELECT
        processed.cluster_key,
        observation.installation_id,
        count(*)::bigint AS observation_count
    FROM public.projection_processed_events AS processed
    JOIN public.road_observations AS observation
      ON observation.event_id = processed.event_id
    GROUP BY processed.cluster_key, observation.installation_id
)
UPDATE public.hazard_contributors AS contributor
SET first_detected_at = LEAST(
        contributor.first_detected_at,
        rollup.first_detected_at
    ),
    observation_count = (
        retained_counts.observation_count
        + rollup.observation_count
    ),
    updated_at = CASE
        WHEN contributor.first_detected_at > rollup.first_detected_at
          OR contributor.observation_count <> (
              retained_counts.observation_count + rollup.observation_count
          )
        THEN clock_timestamp()
        ELSE contributor.updated_at
    END
FROM public.retained_contributor_rollups AS rollup
JOIN retained_counts
  ON retained_counts.cluster_key = rollup.cluster_key
 AND retained_counts.installation_id = rollup.installation_id
JOIN public.hazard_clusters AS cluster
  ON cluster.cluster_key = rollup.cluster_key
WHERE contributor.cluster_key = rollup.cluster_key
  AND contributor.installation_id = rollup.installation_id
  AND (
      %s::text IS NULL
      OR left(cluster.spatial_cell, char_length(%s::text)) = %s::text
  )
RETURNING contributor.cluster_key
"""

REFRESH_RETAINED_PROJECTIONS_SQL = """
WITH aggregates AS (
    SELECT
        contributor.cluster_key,
        min(contributor.first_detected_at) AS first_detected_at,
        sum(contributor.observation_count)::bigint AS revision
    FROM public.hazard_contributors AS contributor
    JOIN public.hazard_clusters AS cluster
      ON cluster.cluster_key = contributor.cluster_key
    WHERE %s::text IS NULL
       OR left(cluster.spatial_cell, char_length(%s::text)) = %s::text
    GROUP BY contributor.cluster_key
)
UPDATE public.hazard_projections AS projection
SET first_detected_at = aggregates.first_detected_at,
    revision = aggregates.revision,
    updated_at = CASE
        WHEN projection.first_detected_at <> aggregates.first_detected_at
          OR projection.revision <> aggregates.revision
        THEN clock_timestamp()
        ELSE projection.updated_at
    END
FROM aggregates
WHERE projection.cluster_key = aggregates.cluster_key
"""


class ProjectionUnavailable(RuntimeError):
    """Raised when projection work cannot be performed safely."""


class ClaimLost(RuntimeError):
    """Raised when a worker no longer owns the leased outbox item."""


@dataclass(frozen=True)
class OutboxClaim:
    outbox_id: int
    event_id: UUID
    attempt: int


@dataclass(frozen=True)
class ProjectionRunResult:
    claimed_count: int
    projected_count: int
    replayed_count: int
    retry_count: int
    dead_letter_count: int


@dataclass(frozen=True)
class ProjectionStatus:
    pending_count: int
    claimed_count: int
    dead_letter_count: int
    completed_count: int
    oldest_pending_seconds: float


@dataclass(frozen=True)
class RebuildResult:
    reset_count: int


@dataclass(frozen=True)
class _ClaimedObservation:
    event_id: UUID
    installation_id: str
    kind: str
    spatial_cell: str

    @property
    def cluster_key(self) -> str:
        return f"{self.kind}:{self.spatial_cell}"


class PostgresProjectionWorker:
    def __init__(self, settings: Settings, worker_id: str) -> None:
        if not settings.database_url:
            raise ValueError("database_url is required for projection")
        if not worker_id.strip():
            raise ValueError("worker_id must not be empty")

        self._settings = settings
        self._worker_id = worker_id
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
            name=f"michisonae-projector-{worker_id[:32]}",
        )

    async def open(self) -> None:
        await self._pool.open(wait=False)

    async def close(self) -> None:
        await self._pool.close()

    async def run_once(self) -> ProjectionRunResult:
        claims = await self._claim_batch()
        projected_count = 0
        replayed_count = 0
        retry_count = 0
        dead_letter_count = 0

        for claim in claims:
            try:
                projected = await self._process_claim(claim)
            except Exception as error:
                dead_lettered = await self._record_failure(claim, error)
                if dead_lettered:
                    dead_letter_count += 1
                else:
                    retry_count += 1
            else:
                if projected:
                    projected_count += 1
                else:
                    replayed_count += 1

        return ProjectionRunResult(
            claimed_count=len(claims),
            projected_count=projected_count,
            replayed_count=replayed_count,
            retry_count=retry_count,
            dead_letter_count=dead_letter_count,
        )

    async def status(self) -> ProjectionStatus:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(STATUS_SQL)
                row = await cursor.fetchone()
        except PsycopgError as error:
            raise ProjectionUnavailable("projection status query failed") from error

        if row is None:
            raise ProjectionUnavailable("projection status query returned no result")
        return ProjectionStatus(
            pending_count=int(row[0]),
            claimed_count=int(row[1]),
            dead_letter_count=int(row[2]),
            completed_count=int(row[3]),
            oldest_pending_seconds=float(row[4]),
        )

    async def rebuild(self) -> RebuildResult:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    await connection.execute(
                        "SELECT pg_advisory_xact_lock(%s)",
                        (PROJECTION_REBUILD_LOCK_ID,),
                    )
                    await connection.execute(RESET_PROJECTIONS_SQL)
                    cursor = await connection.execute(RESET_OUTBOX_SQL)
                    reset_count = cursor.rowcount
        except PsycopgError as error:
            raise ProjectionUnavailable("projection rebuild reset failed") from error
        return RebuildResult(reset_count=reset_count)

    async def rebuild_region(
        self,
        *,
        region_id: str,
        region_cell: str,
    ) -> RebuildResult:
        precision = len(region_cell)
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    await connection.execute(
                        "SELECT pg_advisory_xact_lock(%s)",
                        (PROJECTION_REBUILD_LOCK_ID,),
                    )
                    await connection.execute(
                        DELETE_REGION_SNAPSHOT_HEAD_SQL,
                        (region_id,),
                    )
                    await connection.execute(
                        DELETE_REGION_SNAPSHOTS_SQL,
                        (region_id,),
                    )
                    await connection.execute(
                        DELETE_REGION_SNAPSHOT_WORK_SQL,
                        (region_id,),
                    )
                    await connection.execute(
                        DELETE_REGION_PROCESSED_SQL,
                        (precision, region_cell),
                    )
                    await connection.execute(
                        DELETE_REGION_CLUSTERS_SQL,
                        (precision, region_cell),
                    )
                    cursor = await connection.execute(
                        RESET_REGION_OUTBOX_SQL,
                        (precision, region_cell),
                    )
                    reset_count = cursor.rowcount
        except PsycopgError as error:
            raise ProjectionUnavailable("regional projection rebuild failed") from error
        return RebuildResult(reset_count=reset_count)

    async def apply_retained_rollups(self, region_cell: str | None = None) -> int:
        parameters = (region_cell, region_cell, region_cell)
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    await connection.execute(
                        "SELECT pg_advisory_xact_lock(%s)",
                        (PROJECTION_REBUILD_LOCK_ID,),
                    )
                    contributor_cursor = await connection.execute(
                        APPLY_RETAINED_CONTRIBUTORS_SQL,
                        parameters,
                    )
                    applied_count = int(contributor_cursor.rowcount)
                    await connection.execute(
                        REFRESH_RETAINED_PROJECTIONS_SQL,
                        parameters,
                    )
        except PsycopgError as error:
            raise ProjectionUnavailable("retained contributor rollup failed") from error
        return applied_count

    async def drain(self) -> ProjectionRunResult:
        total = ProjectionRunResult(0, 0, 0, 0, 0)
        while True:
            current = await self.run_once()
            total = ProjectionRunResult(
                claimed_count=total.claimed_count + current.claimed_count,
                projected_count=total.projected_count + current.projected_count,
                replayed_count=total.replayed_count + current.replayed_count,
                retry_count=total.retry_count + current.retry_count,
                dead_letter_count=total.dead_letter_count + current.dead_letter_count,
            )
            status = await self.status()
            if status.pending_count == 0:
                return total
            if current.claimed_count == 0:
                await asyncio.sleep(self._settings.projection_poll_seconds)

    async def _claim_batch(self) -> tuple[OutboxClaim, ...]:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    CLAIM_OUTBOX_SQL,
                    (
                        self._settings.projection_batch_size,
                        self._worker_id,
                        self._settings.projection_lease_seconds,
                    ),
                )
                rows = await cursor.fetchall()
        except PsycopgError as error:
            raise ProjectionUnavailable("outbox claim failed") from error

        return tuple(
            OutboxClaim(
                outbox_id=int(row[0]),
                event_id=UUID(str(row[1])),
                attempt=int(row[2]),
            )
            for row in sorted(rows, key=lambda item: int(item[0]))
        )

    async def _process_claim(self, claim: OutboxClaim) -> bool:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    await connection.execute(
                        "SELECT pg_advisory_xact_lock_shared(%s)",
                        (PROJECTION_REBUILD_LOCK_ID,),
                    )
                    renew_cursor = await connection.execute(
                        RENEW_CLAIM_SQL,
                        (
                            self._settings.projection_lease_seconds,
                            claim.outbox_id,
                            self._worker_id,
                        ),
                    )
                    renewed = await renew_cursor.fetchone()
                    if renewed is None or UUID(str(renewed[0])) != claim.event_id:
                        raise ClaimLost("projection lease was lost before processing")
                    observation = await self._load_claimed_observation(connection, claim)
                    await connection.execute(
                        INSERT_CLUSTER_SQL,
                        (
                            observation.cluster_key,
                            observation.kind,
                            observation.spatial_cell,
                        ),
                    )
                    lock_cursor = await connection.execute(
                        LOCK_CLUSTER_SQL,
                        (observation.cluster_key,),
                    )
                    if await lock_cursor.fetchone() is None:
                        raise ProjectionUnavailable("projection cluster lock failed")

                    processed_cursor = await connection.execute(
                        INSERT_PROCESSED_EVENT_SQL,
                        (claim.event_id, claim.outbox_id, observation.cluster_key),
                    )
                    projected = await processed_cursor.fetchone() is not None
                    if projected:
                        await connection.execute(
                            UPSERT_CONTRIBUTOR_SQL,
                            (observation.cluster_key, claim.event_id),
                        )
                        await connection.execute(
                            REFRESH_PROJECTION_SQL,
                            (observation.cluster_key, PROJECTION_POLICY_VERSION),
                        )
                        region_precision = self._settings.snapshot_region_geohash_precision
                        await connection.execute(
                            MARK_REGION_DIRTY_SQL,
                            (
                                f"gh{region_precision}:"
                                f"{observation.spatial_cell[:region_precision]}",
                            ),
                        )

                    acknowledge_cursor = await connection.execute(
                        ACKNOWLEDGE_OUTBOX_SQL,
                        (claim.outbox_id, self._worker_id),
                    )
                    if await acknowledge_cursor.fetchone() is None:
                        raise ClaimLost("projection lease was lost before acknowledgement")
        except (ClaimLost, ProjectionUnavailable):
            raise
        except PsycopgError as error:
            raise ProjectionUnavailable("projection transaction failed") from error
        return projected

    async def _load_claimed_observation(
        self,
        connection: Any,
        claim: OutboxClaim,
    ) -> _ClaimedObservation:
        cursor = await connection.execute(
            LOAD_CLAIMED_OBSERVATION_SQL,
            (
                self._settings.projection_geohash_precision,
                claim.outbox_id,
                self._worker_id,
            ),
        )
        row = await cursor.fetchone()
        if row is None:
            raise ClaimLost("projection lease is missing or expired")
        if UUID(str(row[0])) != claim.event_id:
            raise ProjectionUnavailable("claimed event identity changed")
        return _ClaimedObservation(
            event_id=UUID(str(row[0])),
            installation_id=str(row[1]),
            kind=str(row[3]),
            spatial_cell=str(row[6]),
        )

    async def _record_failure(self, claim: OutboxClaim, error: Exception) -> bool:
        failure_code = projection_failure_code(error)
        retry_delay = projection_retry_delay(
            attempt=claim.attempt,
            base_seconds=self._settings.projection_retry_base_seconds,
            maximum_seconds=self._settings.projection_retry_max_seconds,
        )
        retry_interval = str(timedelta(seconds=retry_delay))
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    RECORD_FAILURE_SQL,
                    (
                        self._settings.projection_max_attempts,
                        retry_interval,
                        failure_code,
                        self._settings.projection_max_attempts,
                        self._settings.projection_max_attempts,
                        failure_code,
                        claim.outbox_id,
                        self._worker_id,
                    ),
                )
                row = await cursor.fetchone()
        except PsycopgError as database_error:
            raise ProjectionUnavailable("projection failure recording failed") from database_error
        if row is None:
            raise ClaimLost("projection lease was lost while recording failure")
        return bool(row[0])


def projection_retry_delay(
    *,
    attempt: int,
    base_seconds: float,
    maximum_seconds: float,
) -> float:
    if attempt < 1:
        raise ValueError("attempt must be at least 1")
    return float(min(maximum_seconds, base_seconds * (2 ** (attempt - 1))))


def projection_failure_code(error: Exception) -> str:
    if isinstance(error, ClaimLost):
        return "projection_claim_lost"
    if isinstance(error, ProjectionUnavailable):
        return "projection_processing_unavailable"
    return "projection_processing_error"
