from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Literal
from uuid import UUID, uuid4

import psycopg
from psycopg.types.json import Jsonb

from michisonae_api.settings import Settings

MAINTENANCE_LOCK_ID = 6_706_727_259_550_296_349
REASON_PATTERN = re.compile(r"^[a-z][a-z0-9_]{2,63}$")

RETENTION_ELIGIBLE_SQL = """
SELECT
    count(*)::integer AS candidate_count,
    min(observation.received_at) AS oldest_received_at,
    max(observation.received_at) AS newest_received_at
FROM public.road_observations AS observation
JOIN public.observation_outbox AS outbox
  ON outbox.observation_event_id = observation.event_id
JOIN public.projection_processed_events AS processed
  ON processed.event_id = observation.event_id
WHERE observation.received_at < %s
  AND outbox.published_at IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM public.hazard_contributors AS contributor
      WHERE contributor.latest_event_id = observation.event_id
  )
"""

DELETE_RETENTION_BATCH_SQL = """
WITH candidates AS MATERIALIZED (
    SELECT
        observation.event_id,
        observation.installation_id,
        observation.detected_at,
        processed.cluster_key
    FROM public.road_observations AS observation
    JOIN public.observation_outbox AS outbox
      ON outbox.observation_event_id = observation.event_id
    JOIN public.projection_processed_events AS processed
      ON processed.event_id = observation.event_id
    WHERE observation.received_at < %s
      AND outbox.published_at IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM public.hazard_contributors AS contributor
          WHERE contributor.latest_event_id = observation.event_id
      )
    ORDER BY observation.received_at, observation.event_id
    FOR UPDATE OF observation, outbox SKIP LOCKED
    LIMIT %s
),
retained_rollups AS (
    INSERT INTO public.retained_contributor_rollups (
        cluster_key,
        installation_id,
        first_detected_at,
        observation_count
    )
    SELECT
        candidates.cluster_key,
        candidates.installation_id,
        min(candidates.detected_at),
        count(*)::bigint
    FROM candidates
    GROUP BY candidates.cluster_key, candidates.installation_id
    ON CONFLICT (cluster_key, installation_id) DO UPDATE
    SET first_detected_at = LEAST(
            retained_contributor_rollups.first_detected_at,
            EXCLUDED.first_detected_at
        ),
        observation_count = (
            retained_contributor_rollups.observation_count
            + EXCLUDED.observation_count
        ),
        updated_at = clock_timestamp()
    RETURNING cluster_key, installation_id
),
deleted_processed AS (
    DELETE FROM public.projection_processed_events AS processed
    USING candidates
    WHERE processed.event_id = candidates.event_id
      AND EXISTS (
          SELECT 1
          FROM retained_rollups
          WHERE retained_rollups.cluster_key = candidates.cluster_key
            AND retained_rollups.installation_id = candidates.installation_id
      )
    RETURNING processed.event_id
),
deleted_outbox AS (
    DELETE FROM public.observation_outbox AS outbox
    USING candidates
    WHERE outbox.observation_event_id = candidates.event_id
      AND outbox.observation_event_id IN (
          SELECT event_id FROM deleted_processed
      )
    RETURNING outbox.observation_event_id
)
DELETE FROM public.road_observations AS observation
USING deleted_outbox
WHERE observation.event_id = deleted_outbox.observation_event_id
RETURNING observation.event_id
"""

OUTBOX_DEAD_LETTER_STATUS_SQL = """
SELECT
    count(*) FILTER (
        WHERE dead_lettered_at IS NOT NULL AND quarantined_at IS NULL
    )::integer AS retryable_count,
    count(*) FILTER (WHERE quarantined_at IS NOT NULL)::integer AS quarantined_count,
    min(dead_lettered_at) AS oldest_dead_lettered_at
FROM public.observation_outbox
"""

SNAPSHOT_DEAD_LETTER_STATUS_SQL = """
SELECT
    count(*) FILTER (
        WHERE dead_lettered_at IS NOT NULL AND quarantined_at IS NULL
    )::integer AS retryable_count,
    count(*) FILTER (WHERE quarantined_at IS NOT NULL)::integer AS quarantined_count,
    min(dead_lettered_at) AS oldest_dead_lettered_at
FROM public.regional_snapshot_work
"""

RETRY_OUTBOX_SQL = """
UPDATE public.observation_outbox
SET delivery_attempts = 0,
    next_attempt_at = clock_timestamp(),
    last_error = NULL,
    claimed_by = NULL,
    claimed_until = NULL,
    last_attempt_at = NULL,
    dead_lettered_at = NULL,
    dead_letter_reason = NULL,
    quarantined_at = NULL,
    quarantine_reason = NULL
WHERE id = %s
  AND dead_lettered_at IS NOT NULL
RETURNING id
"""

RETRY_SNAPSHOT_SQL = """
UPDATE public.regional_snapshot_work
SET delivery_attempts = 0,
    next_attempt_at = clock_timestamp(),
    last_error = NULL,
    claimed_by = NULL,
    claimed_until = NULL,
    claimed_generation = NULL,
    last_attempt_at = NULL,
    dead_lettered_at = NULL,
    dead_letter_reason = NULL,
    quarantined_at = NULL,
    quarantine_reason = NULL
WHERE region_id = %s
  AND dead_lettered_at IS NOT NULL
RETURNING region_id
"""

QUARANTINE_OUTBOX_SQL = """
UPDATE public.observation_outbox
SET quarantined_at = COALESCE(quarantined_at, clock_timestamp()),
    quarantine_reason = COALESCE(quarantine_reason, %s),
    claimed_by = NULL,
    claimed_until = NULL
WHERE id = %s
  AND dead_lettered_at IS NOT NULL
  AND quarantined_at IS NULL
RETURNING id
"""

QUARANTINE_SNAPSHOT_SQL = """
UPDATE public.regional_snapshot_work
SET quarantined_at = COALESCE(quarantined_at, clock_timestamp()),
    quarantine_reason = COALESCE(quarantine_reason, %s),
    claimed_by = NULL,
    claimed_until = NULL,
    claimed_generation = NULL
WHERE region_id = %s
  AND dead_lettered_at IS NOT NULL
  AND quarantined_at IS NULL
RETURNING region_id
"""

PURGE_OUTBOX_BATCH_SQL = """
WITH candidates AS MATERIALIZED (
    SELECT outbox.id, outbox.observation_event_id
    FROM public.observation_outbox AS outbox
    WHERE outbox.quarantined_at < %s
      AND NOT EXISTS (
          SELECT 1
          FROM public.projection_processed_events AS processed
          WHERE processed.outbox_id = outbox.id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM public.hazard_contributors AS contributor
          WHERE contributor.latest_event_id = outbox.observation_event_id
      )
    ORDER BY outbox.quarantined_at, outbox.id
    FOR UPDATE OF outbox SKIP LOCKED
    LIMIT %s
),
deleted_processed AS (
    DELETE FROM public.projection_processed_events AS processed
    USING candidates
    WHERE processed.outbox_id = candidates.id
    RETURNING processed.outbox_id
),
deleted_outbox AS (
    DELETE FROM public.observation_outbox AS outbox
    USING candidates
    WHERE outbox.id = candidates.id
      AND (
          outbox.id IN (SELECT outbox_id FROM deleted_processed)
          OR NOT EXISTS (
              SELECT 1
              FROM public.projection_processed_events AS processed
              WHERE processed.outbox_id = outbox.id
          )
      )
    RETURNING outbox.observation_event_id
)
DELETE FROM public.road_observations AS observation
USING deleted_outbox
WHERE observation.event_id = deleted_outbox.observation_event_id
RETURNING observation.event_id
"""

PURGE_SNAPSHOT_BATCH_SQL = """
DELETE FROM public.regional_snapshot_work
WHERE region_id IN (
    SELECT region_id
    FROM public.regional_snapshot_work
    WHERE quarantined_at < %s
    ORDER BY quarantined_at, region_id
    FOR UPDATE SKIP LOCKED
    LIMIT %s
)
RETURNING region_id
"""

INSERT_OPERATIONS_AUDIT_SQL = """
INSERT INTO public.operations_audit_events (
    command_id,
    correlation_id,
    action,
    mode,
    outcome,
    details
)
VALUES (%s, %s, %s, %s, %s, %s)
"""

CONSISTENCY_SQL = """
SELECT
    (
        SELECT count(*)
        FROM public.road_observations AS observation
        LEFT JOIN public.observation_outbox AS outbox
          ON outbox.observation_event_id = observation.event_id
        WHERE outbox.id IS NULL
    )::integer AS observations_without_outbox,
    (
        SELECT count(*)
        FROM public.observation_outbox AS outbox
        LEFT JOIN public.projection_processed_events AS processed
          ON processed.outbox_id = outbox.id
        WHERE outbox.published_at IS NOT NULL
          AND processed.event_id IS NULL
    )::integer AS completed_without_processed_event,
    (
        SELECT count(*)
        FROM public.projection_processed_events AS processed
        JOIN public.observation_outbox AS outbox
          ON outbox.id = processed.outbox_id
        WHERE outbox.published_at IS NULL
    )::integer AS processed_without_completion,
    (
        SELECT count(*)
        FROM public.hazard_projections AS projection
        WHERE projection.contributor_count <> (
            SELECT count(*)
            FROM public.hazard_contributors AS contributor
            WHERE contributor.cluster_key = projection.cluster_key
        )
    )::integer AS projection_contributor_mismatches,
    (
        SELECT count(*)
        FROM (
            SELECT installation_id
            FROM public.auth_access_tokens
            WHERE revoked_at IS NULL AND expires_at > clock_timestamp()
            GROUP BY installation_id
            HAVING count(*) > %s
        ) AS excessive
    )::integer AS installations_over_access_limit,
    (
        SELECT count(*)
        FROM public.retained_contributor_rollups AS rollup
        LEFT JOIN public.hazard_contributors AS contributor
          ON contributor.cluster_key = rollup.cluster_key
         AND contributor.installation_id = rollup.installation_id
        WHERE contributor.cluster_key IS NULL
    )::integer AS retained_rollups_without_contributors
"""


class MaintenanceError(RuntimeError):
    """Raised when lifecycle maintenance cannot complete safely."""


@dataclass(frozen=True)
class RetentionResult:
    command_id: UUID
    cutoff: datetime
    dry_run: bool
    candidate_count: int
    deleted_count: int
    oldest_received_at: datetime | None
    newest_received_at: datetime | None


@dataclass(frozen=True)
class DeadLetterStatus:
    kind: Literal["outbox", "snapshot"]
    retryable_count: int
    quarantined_count: int
    oldest_dead_lettered_at: datetime | None


@dataclass(frozen=True)
class OperationResult:
    command_id: UUID
    action: str
    affected_count: int


@dataclass(frozen=True)
class ConsistencyResult:
    observations_without_outbox: int
    completed_without_processed_event: int
    processed_without_completion: int
    projection_contributor_mismatches: int
    installations_over_access_limit: int
    retained_rollups_without_contributors: int

    @property
    def is_consistent(self) -> bool:
        return all(
            value == 0
            for value in (
                self.observations_without_outbox,
                self.completed_without_processed_event,
                self.processed_without_completion,
                self.projection_contributor_mismatches,
                self.installations_over_access_limit,
                self.retained_rollups_without_contributors,
            )
        )


class PostgresMaintenance:
    def __init__(self, settings: Settings) -> None:
        if not settings.database_url:
            raise ValueError("database_url is required for maintenance")
        self._settings = settings
        self._database_url = settings.database_url

    def retention(
        self,
        *,
        cutoff: datetime,
        dry_run: bool,
        batch_size: int | None = None,
    ) -> RetentionResult:
        normalized_cutoff = self._normalize_cutoff(cutoff)
        command_id = uuid4()
        size = self._validated_batch_size(batch_size)
        try:
            with psycopg.connect(self._database_url) as connection:
                connection.execute(
                    "SELECT pg_advisory_xact_lock(%s)",
                    (MAINTENANCE_LOCK_ID,),
                )
                row = connection.execute(
                    RETENTION_ELIGIBLE_SQL,
                    (normalized_cutoff,),
                ).fetchone()
                if row is None:
                    raise MaintenanceError("retention preview returned no result")
                candidate_count = int(row[0])
                oldest = row[1]
                newest = row[2]
                if dry_run or candidate_count == 0:
                    self._audit(
                        connection,
                        command_id=command_id,
                        action="observation_retention",
                        mode="dry_run" if dry_run else "apply",
                        outcome="no_op" if candidate_count == 0 else "completed",
                        details={
                            "candidate_count": candidate_count,
                            "cutoff": normalized_cutoff.isoformat(),
                        },
                    )
            if dry_run or candidate_count == 0:
                return RetentionResult(
                    command_id,
                    normalized_cutoff,
                    dry_run,
                    candidate_count,
                    0,
                    oldest,
                    newest,
                )

            deleted = self._delete_in_batches(
                DELETE_RETENTION_BATCH_SQL,
                normalized_cutoff,
                size,
            )
            with psycopg.connect(self._database_url) as connection:
                self._audit(
                    connection,
                    command_id=command_id,
                    action="observation_retention",
                    mode="apply",
                    outcome="no_op" if deleted == 0 else "completed",
                    details={
                        "candidate_count": candidate_count,
                        "deleted_count": deleted,
                        "cutoff": normalized_cutoff.isoformat(),
                    },
                )
        except (MaintenanceError, psycopg.Error):
            raise
        return RetentionResult(
            command_id,
            normalized_cutoff,
            False,
            candidate_count,
            deleted,
            oldest,
            newest,
        )

    def dead_letter_status(
        self,
        kind: Literal["outbox", "snapshot"],
    ) -> DeadLetterStatus:
        query = (
            OUTBOX_DEAD_LETTER_STATUS_SQL if kind == "outbox" else SNAPSHOT_DEAD_LETTER_STATUS_SQL
        )
        with psycopg.connect(self._database_url) as connection:
            row = connection.execute(query).fetchone()
        if row is None:
            raise MaintenanceError("dead-letter status returned no result")
        return DeadLetterStatus(kind, int(row[0]), int(row[1]), row[2])

    def retry_dead_letter(
        self,
        kind: Literal["outbox", "snapshot"],
        key: int | str,
    ) -> OperationResult:
        query = RETRY_OUTBOX_SQL if kind == "outbox" else RETRY_SNAPSHOT_SQL
        return self._single_dead_letter_operation(
            action=f"{kind}_dead_letter_retry",
            query=query,
            parameters=(key,),
            details={"target": str(key)},
        )

    def quarantine_dead_letter(
        self,
        kind: Literal["outbox", "snapshot"],
        key: int | str,
        reason: str,
    ) -> OperationResult:
        if REASON_PATTERN.fullmatch(reason) is None:
            raise ValueError("quarantine reason must be a safe snake_case code")
        query = QUARANTINE_OUTBOX_SQL if kind == "outbox" else QUARANTINE_SNAPSHOT_SQL
        return self._single_dead_letter_operation(
            action=f"{kind}_dead_letter_quarantine",
            query=query,
            parameters=(reason, key),
            details={"target": str(key), "reason": reason},
        )

    def purge_quarantined(
        self,
        kind: Literal["outbox", "snapshot"],
        *,
        cutoff: datetime,
        batch_size: int | None = None,
    ) -> OperationResult:
        query = PURGE_OUTBOX_BATCH_SQL if kind == "outbox" else PURGE_SNAPSHOT_BATCH_SQL
        command_id = uuid4()
        affected = self._delete_in_batches(
            query,
            self._normalize_cutoff(cutoff),
            self._validated_batch_size(batch_size),
        )
        with psycopg.connect(self._database_url) as connection:
            self._audit(
                connection,
                command_id=command_id,
                action=f"{kind}_quarantine_purge",
                mode="apply",
                outcome="no_op" if affected == 0 else "completed",
                details={
                    "affected_count": affected,
                    "cutoff": self._normalize_cutoff(cutoff).isoformat(),
                },
            )
        return OperationResult(
            command_id,
            f"{kind}_quarantine_purge",
            affected,
        )

    def consistency(self) -> ConsistencyResult:
        with psycopg.connect(self._database_url) as connection:
            row = connection.execute(
                CONSISTENCY_SQL,
                (self._settings.maximum_active_access_tokens,),
            ).fetchone()
        if row is None:
            raise MaintenanceError("consistency check returned no result")
        return ConsistencyResult(*(int(value) for value in row))

    def _single_dead_letter_operation(
        self,
        *,
        action: str,
        query: str,
        parameters: tuple[Any, ...],
        details: dict[str, Any],
    ) -> OperationResult:
        command_id = uuid4()
        with psycopg.connect(self._database_url) as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(%s)",
                (MAINTENANCE_LOCK_ID,),
            )
            cursor = connection.execute(query, parameters)
            affected = len(cursor.fetchall())
            self._audit(
                connection,
                command_id=command_id,
                action=action,
                mode="apply",
                outcome="no_op" if affected == 0 else "completed",
                details={**details, "affected_count": affected},
            )
        return OperationResult(command_id, action, affected)

    def _delete_in_batches(
        self,
        query: str,
        cutoff: datetime,
        batch_size: int,
    ) -> int:
        deleted = 0
        while True:
            with psycopg.connect(self._database_url) as connection:
                connection.execute(
                    "SELECT pg_advisory_xact_lock(%s)",
                    (MAINTENANCE_LOCK_ID,),
                )
                rows = connection.execute(
                    query,
                    (cutoff, batch_size),
                ).fetchall()
            batch_count = len(rows)
            deleted += batch_count
            if batch_count < batch_size:
                return deleted

    def _audit(
        self,
        connection: Any,
        *,
        command_id: UUID,
        action: str,
        mode: Literal["dry_run", "apply"],
        outcome: Literal["completed", "no_op", "failed"],
        details: dict[str, Any],
    ) -> None:
        connection.execute(
            INSERT_OPERATIONS_AUDIT_SQL,
            (
                command_id,
                command_id,
                action,
                mode,
                outcome,
                Jsonb(details),
            ),
        )

    def _validated_batch_size(self, supplied: int | None) -> int:
        batch_size = supplied if supplied is not None else self._settings.maintenance_batch_size
        if not 1 <= batch_size <= 5000:
            raise ValueError("maintenance batch size must be between 1 and 5000")
        return batch_size

    @staticmethod
    def _normalize_cutoff(cutoff: datetime) -> datetime:
        if cutoff.tzinfo is None or cutoff.utcoffset() is None:
            raise ValueError("maintenance cutoff must include a timezone")
        return cutoff.astimezone(UTC)
