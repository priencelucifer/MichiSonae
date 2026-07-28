from __future__ import annotations

import asyncio
import os
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.migration import run_migrations
from michisonae_api.projection import PostgresProjectionWorker, ProjectionRunResult
from michisonae_api.settings import Settings

DATABASE_URL = os.getenv("MICHI_TEST_DATABASE_URL")
AUTHORIZATIONS: dict[str, tuple[str, str]] = {}
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="MICHI_TEST_DATABASE_URL is required for PostgreSQL integration tests",
)


def observation(
    *,
    installation_id: str,
    event_id: str | None = None,
    detected_at: datetime | None = None,
    severity: float = 0.7,
    confidence: float = 0.8,
) -> dict[str, object]:
    return {
        "event_id": event_id or str(uuid4()),
        "installation_id": installation_id,
        "detected_at": (detected_at or datetime.now(UTC)).isoformat(),
        "latitude": 26.1445,
        "longitude": 91.7362,
        "location_accuracy_m": 8.0,
        "speed_mps": 12.0,
        "kind": "road_damage",
        "severity": severity,
        "confidence": confidence,
        "source": "phone",
        "detector_version": "phone-shadow-v1",
    }


def batch(*observations: dict[str, object]) -> dict[str, object]:
    return {"schema_version": "1.0", "observations": list(observations)}


@pytest.fixture(autouse=True)
def reset_database() -> None:
    assert DATABASE_URL is not None
    run_migrations(DATABASE_URL)
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            TRUNCATE
                public.operations_audit_events,
                public.security_audit_events,
                public.security_rate_limits,
                public.auth_access_tokens,
                public.auth_refresh_tokens,
                public.auth_token_families,
                public.anonymous_installations,
                public.regional_snapshot_heads,
                public.regional_hazard_snapshots,
                public.regional_snapshot_work,
                public.projection_processed_events,
                public.hazard_projections,
                public.hazard_contributors,
                public.hazard_clusters,
                public.retained_contributor_rollups,
                public.observation_outbox,
                public.road_observations
            RESTART IDENTITY
            """
        )
    AUTHORIZATIONS.clear()


def settings(**overrides: Any) -> Settings:
    assert DATABASE_URL is not None
    return Settings(
        environment="test",
        database_url=DATABASE_URL,
        database_pool_min_size=1,
        database_pool_max_size=8,
        database_pool_timeout_seconds=5,
        registration_rate_limit_per_hour=1000,
        **overrides,
    )


def submit(*observations: dict[str, object]) -> None:
    with TestClient(create_app(settings())) as client:
        grouped: dict[str, list[dict[str, object]]] = {}
        for row in observations:
            grouped.setdefault(str(row["installation_id"]), []).append(row)
        for alias, rows in grouped.items():
            if alias not in AUTHORIZATIONS:
                registration = client.post(
                    "/v1/installations:register",
                    json={"schema_version": "1.0"},
                )
                assert registration.status_code == 201
                body = registration.json()
                AUTHORIZATIONS[alias] = (
                    body["installation_id"],
                    body["access_token"],
                )
            installation_id, access_token = AUTHORIZATIONS[alias]
            for row in rows:
                row["installation_id"] = installation_id
            response = client.post(
                "/v1/observations:batch",
                json=batch(*rows),
                headers={"Authorization": f"Bearer {access_token}"},
            )
            assert response.status_code == 202
            assert response.json()["stored_count"] == len(rows)


def run_once(worker_settings: Settings | None = None) -> ProjectionRunResult:
    async def execute() -> ProjectionRunResult:
        worker = PostgresProjectionWorker(
            worker_settings or settings(),
            worker_id=f"test-{uuid4().hex}",
        )
        await worker.open()
        try:
            return await worker.run_once()
        finally:
            await worker.close()

    return asyncio.run(execute())


def projection_row() -> tuple[Any, ...]:
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        row = connection.execute(
            """
            SELECT
                lifecycle_state,
                contributor_count,
                severity,
                confidence,
                policy_version,
                revision
            FROM public.hazard_projections
            """
        ).fetchone()
    assert row is not None
    return row


def test_distinct_installations_drive_consensus_and_robust_severity() -> None:
    submit(observation(installation_id="anonymous-install-0001", severity=0.1))
    first = run_once()
    assert first.projected_count == 1
    assert projection_row()[:3] == ("community_unverified", 1, pytest.approx(0.1))

    submit(observation(installation_id="anonymous-install-0002", severity=0.9))
    run_once()
    assert projection_row()[:3] == ("provisional", 2, pytest.approx(0.5))

    submit(observation(installation_id="anonymous-install-0003", severity=0.5))
    run_once()
    row = projection_row()
    assert row[:3] == ("confirmed", 3, pytest.approx(0.5))
    assert row[3] == pytest.approx(0.8)
    assert row[4] == "projection-v1"


def test_repeat_source_updates_latest_value_without_inflating_consensus() -> None:
    now = datetime.now(UTC)
    submit(
        observation(
            installation_id="anonymous-install-0001",
            detected_at=now,
            severity=0.9,
        )
    )
    run_once()
    submit(
        observation(
            installation_id="anonymous-install-0001",
            detected_at=now - timedelta(hours=1),
            severity=0.1,
        )
    )
    run_once()

    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        contributor = connection.execute(
            """
            SELECT observation_count, latest_severity, first_detected_at, last_detected_at
            FROM public.hazard_contributors
            """
        ).fetchone()
    assert contributor is not None
    assert contributor[0] == 2
    assert contributor[1] == pytest.approx(0.9)
    assert contributor[2] == now - timedelta(hours=1)
    assert contributor[3] == now
    assert projection_row()[:3] == ("community_unverified", 1, pytest.approx(0.9))


def test_replayed_outbox_item_has_one_business_effect() -> None:
    submit(observation(installation_id="anonymous-install-0001"))
    run_once()
    before = projection_row()

    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE public.observation_outbox
            SET published_at = NULL, next_attempt_at = clock_timestamp()
            """
        )

    replay = run_once()
    assert replay.replayed_count == 1
    assert projection_row() == before
    with psycopg.connect(DATABASE_URL) as connection:
        count = connection.execute(
            "SELECT count(*) FROM public.projection_processed_events"
        ).fetchone()
    assert count == (1,)


def test_concurrent_workers_do_not_lose_same_cluster_updates() -> None:
    submit(
        *(observation(installation_id=f"anonymous-install-{index:04d}") for index in range(1, 31))
    )

    async def execute() -> tuple[ProjectionRunResult, ProjectionRunResult]:
        workers = (
            PostgresProjectionWorker(settings(), worker_id="test-concurrent-a"),
            PostgresProjectionWorker(settings(), worker_id="test-concurrent-b"),
        )
        await asyncio.gather(*(worker.open() for worker in workers))
        try:
            first, second = await asyncio.gather(*(worker.drain() for worker in workers))
            return first, second
        finally:
            await asyncio.gather(*(worker.close() for worker in workers))

    results = asyncio.run(execute())
    assert sum(result.projected_count for result in results) == 30
    row = projection_row()
    assert row[0] == "confirmed"
    assert row[1] == 30


def test_expired_lease_is_recovered_by_another_worker() -> None:
    submit(observation(installation_id="anonymous-install-0001"))
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE public.observation_outbox
            SET claimed_by = 'crashed-worker',
                claimed_until = clock_timestamp() - interval '1 second'
            """
        )

    result = run_once()
    assert result.projected_count == 1
    assert projection_row()[1] == 1


def test_poison_event_retries_then_moves_to_dead_letter() -> None:
    submit(observation(installation_id="anonymous-install-0001"))
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            CREATE OR REPLACE FUNCTION public.fail_test_contributor()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RAISE EXCEPTION 'sensitive poison detail';
            END;
            $$
            """
        )
        connection.execute(
            """
            CREATE TRIGGER fail_test_contributor
            BEFORE INSERT ON public.hazard_contributors
            FOR EACH ROW EXECUTE FUNCTION public.fail_test_contributor()
            """
        )

    worker_settings = settings(
        projection_max_attempts=3,
        projection_retry_base_seconds=0,
        projection_retry_max_seconds=0,
    )
    try:
        assert run_once(worker_settings).retry_count == 1
        assert run_once(worker_settings).retry_count == 1
        assert run_once(worker_settings).dead_letter_count == 1
    finally:
        with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
            connection.execute(
                "DROP TRIGGER IF EXISTS fail_test_contributor ON public.hazard_contributors"
            )
            connection.execute("DROP FUNCTION IF EXISTS public.fail_test_contributor()")

    with psycopg.connect(DATABASE_URL) as connection:
        outbox = connection.execute(
            """
            SELECT delivery_attempts, dead_letter_reason, last_error, published_at
            FROM public.observation_outbox
            """
        ).fetchone()
    assert outbox == (
        3,
        "projection_processing_unavailable",
        "projection_processing_unavailable",
        None,
    )


def test_rebuild_reproduces_logical_projection() -> None:
    submit(
        observation(installation_id="anonymous-install-0001", severity=0.2),
        observation(installation_id="anonymous-install-0002", severity=0.8),
        observation(installation_id="anonymous-install-0003", severity=0.6),
    )
    run_once()
    before = projection_row()

    async def rebuild() -> tuple[int, ProjectionRunResult]:
        worker = PostgresProjectionWorker(settings(), worker_id="test-rebuild")
        await worker.open()
        try:
            reset = await worker.rebuild()
            result = await worker.drain()
            return reset.reset_count, result
        finally:
            await worker.close()

    reset_count, result = asyncio.run(rebuild())
    assert reset_count == 3
    assert result.projected_count == 3
    assert projection_row() == before
