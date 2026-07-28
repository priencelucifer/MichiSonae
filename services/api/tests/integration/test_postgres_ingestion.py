from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.migration import expected_migration, run_migrations
from michisonae_api.settings import Settings

DATABASE_URL = os.getenv("MICHI_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="MICHI_TEST_DATABASE_URL is required for PostgreSQL integration tests",
)


def observation(*, event_id: str | None = None, severity: float = 0.7) -> dict[str, object]:
    return {
        "event_id": event_id or str(uuid4()),
        "installation_id": "anonymous-install-0001",
        "detected_at": datetime.now(UTC).isoformat(),
        "latitude": 26.1445,
        "longitude": 91.7362,
        "location_accuracy_m": 8.0,
        "speed_mps": 12.0,
        "kind": "rough_road",
        "severity": severity,
        "confidence": 0.8,
        "source": "phone",
        "detector_version": "phone-shadow-v1",
    }


def batch(*observations: dict[str, object]) -> dict[str, object]:
    return {
        "schema_version": "1.0",
        "observations": list(observations),
    }


@pytest.fixture(autouse=True)
def reset_database() -> None:
    assert DATABASE_URL is not None
    run_migrations(DATABASE_URL)
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            TRUNCATE
                public.projection_processed_events,
                public.hazard_projections,
                public.hazard_contributors,
                public.hazard_clusters,
                public.observation_outbox,
                public.road_observations
            RESTART IDENTITY
            """
        )


def settings() -> Settings:
    assert DATABASE_URL is not None
    return Settings(
        environment="test",
        database_url=DATABASE_URL,
        database_pool_min_size=1,
        database_pool_max_size=8,
        database_pool_timeout_seconds=5,
    )


def table_counts() -> tuple[int, int]:
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        observation_count = connection.execute(
            "SELECT count(*) FROM public.road_observations"
        ).fetchone()
        outbox_count = connection.execute(
            "SELECT count(*) FROM public.observation_outbox"
        ).fetchone()
    assert observation_count is not None
    assert outbox_count is not None
    return int(observation_count[0]), int(outbox_count[0])


def test_retry_produces_one_observation_and_one_outbox_event() -> None:
    event_id = str(uuid4())
    payload = batch(observation(event_id=event_id))

    with TestClient(create_app(settings())) as client:
        first = client.post("/v1/observations:batch", json=payload)
        retry = client.post("/v1/observations:batch", json=payload)

    assert first.status_code == 202
    assert first.json()["stored_count"] == 1
    assert retry.status_code == 202
    assert retry.json()["stored_count"] == 0
    assert retry.json()["duplicate_count"] == 1
    assert table_counts() == (1, 1)


def test_maximum_batch_is_stored_and_queued_atomically() -> None:
    payload = batch(*(observation() for _ in range(100)))

    with TestClient(create_app(settings())) as client:
        response = client.post("/v1/observations:batch", json=payload)

    assert response.status_code == 202
    assert response.json()["stored_count"] == 100
    assert table_counts() == (100, 100)


def test_concurrent_retries_have_one_business_effect() -> None:
    payload = batch(observation())

    with TestClient(create_app(settings())) as client:
        with ThreadPoolExecutor(max_workers=8) as executor:
            responses = list(
                executor.map(
                    lambda _: client.post("/v1/observations:batch", json=payload),
                    range(32),
                )
            )

    assert all(response.status_code == 202 for response in responses)
    assert sum(response.json()["stored_count"] for response in responses) == 1
    assert table_counts() == (1, 1)


def test_event_id_conflict_rolls_back_other_new_events() -> None:
    reused_event_id = str(uuid4())
    new_event_id = str(uuid4())

    with TestClient(create_app(settings())) as client:
        first = client.post(
            "/v1/observations:batch",
            json=batch(observation(event_id=reused_event_id)),
        )
        conflict = client.post(
            "/v1/observations:batch",
            json=batch(
                observation(event_id=reused_event_id, severity=0.1),
                observation(event_id=new_event_id),
            ),
        )

    assert first.status_code == 202
    assert conflict.status_code == 409
    assert table_counts() == (1, 1)


def test_outbox_insert_failure_rolls_back_observation() -> None:
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            CREATE OR REPLACE FUNCTION public.fail_test_outbox_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RAISE EXCEPTION 'forced outbox failure';
            END;
            $$
            """
        )
        connection.execute(
            """
            CREATE TRIGGER fail_test_outbox_insert
            BEFORE INSERT ON public.observation_outbox
            FOR EACH ROW EXECUTE FUNCTION public.fail_test_outbox_insert()
            """
        )

    try:
        with TestClient(create_app(settings())) as client:
            response = client.post(
                "/v1/observations:batch",
                json=batch(observation()),
            )
    finally:
        with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
            connection.execute(
                "DROP TRIGGER IF EXISTS fail_test_outbox_insert "
                "ON public.observation_outbox"
            )
            connection.execute(
                "DROP FUNCTION IF EXISTS public.fail_test_outbox_insert()"
            )

    assert response.status_code == 503
    assert table_counts() == (0, 0)


def test_readiness_rejects_tampered_migration_history() -> None:
    assert DATABASE_URL is not None
    migration = expected_migration()
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE public.schema_migrations
            SET checksum = 'tampered'
            WHERE version = %s
            """,
            (migration.version,),
        )

    try:
        with TestClient(create_app(settings())) as client:
            response = client.get("/health/ready")
    finally:
        with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
            connection.execute(
                """
                UPDATE public.schema_migrations
                SET checksum = %s
                WHERE version = %s
                """,
                (migration.checksum, migration.version),
            )

    assert response.status_code == 503
