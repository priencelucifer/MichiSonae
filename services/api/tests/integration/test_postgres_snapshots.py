from __future__ import annotations

import asyncio
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.migration import run_migrations
from michisonae_api.projection import PostgresProjectionWorker
from michisonae_api.settings import Settings
from michisonae_api.snapshots import PostgresSnapshotPublisher

DATABASE_URL = os.getenv("MICHI_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="MICHI_TEST_DATABASE_URL is required for PostgreSQL integration tests",
)
REGION_ID = "gh5:wh9hx"
AUTHORIZATIONS: dict[str, tuple[str, str]] = {}


def settings() -> Settings:
    assert DATABASE_URL is not None
    return Settings(
        environment="test",
        database_url=DATABASE_URL,
        database_pool_min_size=1,
        database_pool_max_size=8,
        database_pool_timeout_seconds=5,
    )


def observation(installation_id: str, severity: float) -> dict[str, object]:
    return {
        "event_id": str(uuid4()),
        "installation_id": installation_id,
        "detected_at": datetime.now(UTC).isoformat(),
        "latitude": 26.1445,
        "longitude": 91.7362,
        "location_accuracy_m": 8.0,
        "speed_mps": 12.0,
        "kind": "road_damage",
        "severity": severity,
        "confidence": 0.8,
        "source": "phone",
        "detector_version": "phone-shadow-v1",
    }


@pytest.fixture(autouse=True)
def reset_database() -> None:
    assert DATABASE_URL is not None
    run_migrations(DATABASE_URL)
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            TRUNCATE
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
                public.observation_outbox,
                public.road_observations
            RESTART IDENTITY
            """
        )
    AUTHORIZATIONS.clear()


def submit(*rows: dict[str, object]) -> None:
    with TestClient(create_app(settings())) as client:
        grouped: dict[str, list[dict[str, object]]] = {}
        for row in rows:
            grouped.setdefault(str(row["installation_id"]), []).append(row)
        for alias, group in grouped.items():
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
            for row in group:
                row["installation_id"] = installation_id
            response = client.post(
                "/v1/observations:batch",
                json={"schema_version": "1.0", "observations": group},
                headers={"Authorization": f"Bearer {access_token}"},
            )
            assert response.status_code == 202


def project_and_publish() -> tuple[int, int]:
    async def execute() -> tuple[int, int]:
        projector = PostgresProjectionWorker(settings(), f"project-{uuid4().hex}")
        publisher = PostgresSnapshotPublisher(settings(), f"snapshot-{uuid4().hex}")
        await asyncio.gather(projector.open(), publisher.open())
        try:
            projected = await projector.run_once()
            published = await publisher.run_once()
            return projected.projected_count, published.published_count
        finally:
            await asyncio.gather(projector.close(), publisher.close())

    return asyncio.run(execute())


def test_projection_publishes_privacy_minimized_versioned_snapshot() -> None:
    submit(observation("anonymous-install-0001", 0.2))
    assert project_and_publish() == (1, 1)

    with TestClient(create_app(settings())) as client:
        unverified = client.get(f"/v1/regions/{REGION_ID}/hazards")
    assert unverified.status_code == 200
    assert unverified.json()["hazard_count"] == 0
    first_version = unverified.json()["version"]

    submit(observation("anonymous-install-0002", 0.8))
    assert project_and_publish() == (1, 1)
    with TestClient(create_app(settings())) as client:
        current = client.get(f"/v1/regions/{REGION_ID}/hazards")
        immutable = client.get(
            f"/v1/regions/{REGION_ID}/hazards",
            params={"version": first_version},
        )

    body = current.json()
    assert body["hazard_count"] == 1
    assert body["hazards"][0]["lifecycle_state"] == "provisional"
    assert body["hazards"][0]["severity"] == pytest.approx(0.5)
    encoded = current.text
    assert "anonymous-install" not in encoded
    assert "payload" not in encoded
    assert immutable.status_code == 200
    assert immutable.json()["hazard_count"] == 0
    assert immutable.headers["cache-control"].endswith("immutable")


def test_identical_content_is_not_republished() -> None:
    submit(
        observation("anonymous-install-0001", 0.2),
        observation("anonymous-install-0002", 0.8),
    )
    assert project_and_publish() == (2, 1)

    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            "INSERT INTO public.regional_snapshot_work (region_id) VALUES (%s)",
            (REGION_ID,),
        )

    async def republish() -> tuple[int, int]:
        publisher = PostgresSnapshotPublisher(settings(), "snapshot-identical")
        await publisher.open()
        try:
            result = await publisher.run_once()
            return result.published_count, result.unchanged_count
        finally:
            await publisher.close()

    assert asyncio.run(republish()) == (0, 1)
    with psycopg.connect(DATABASE_URL) as connection:
        count = connection.execute(
            "SELECT count(*) FROM public.regional_hazard_snapshots"
        ).fetchone()
    assert count == (1,)


def test_concurrent_snapshot_publishers_have_one_head() -> None:
    submit(
        observation("anonymous-install-0001", 0.2),
        observation("anonymous-install-0002", 0.8),
    )

    async def execute() -> tuple[int, int]:
        projector = PostgresProjectionWorker(settings(), "project-concurrent")
        publishers = (
            PostgresSnapshotPublisher(settings(), "snapshot-concurrent-a"),
            PostgresSnapshotPublisher(settings(), "snapshot-concurrent-b"),
        )
        await projector.open()
        await asyncio.gather(*(publisher.open() for publisher in publishers))
        try:
            await projector.run_once()
            results = await asyncio.gather(*(publisher.run_once() for publisher in publishers))
            return (
                sum(result.published_count for result in results),
                sum(result.claimed_count for result in results),
            )
        finally:
            await projector.close()
            await asyncio.gather(*(publisher.close() for publisher in publishers))

    assert asyncio.run(execute()) == (1, 1)


def test_repeated_reads_depend_only_on_snapshot_tables() -> None:
    submit(
        observation("anonymous-install-0001", 0.2),
        observation("anonymous-install-0002", 0.8),
    )
    project_and_publish()
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            "ALTER TABLE public.road_observations RENAME TO hidden_road_observations"
        )
        connection.execute(
            "ALTER TABLE public.hazard_projections RENAME TO hidden_hazard_projections"
        )

    try:
        with TestClient(create_app(settings())) as client:
            with ThreadPoolExecutor(max_workers=16) as executor:
                responses = list(
                    executor.map(
                        lambda _: client.get(f"/v1/regions/{REGION_ID}/hazards"),
                        range(64),
                    )
                )
    finally:
        with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
            connection.execute(
                "ALTER TABLE public.hidden_hazard_projections RENAME TO hazard_projections"
            )
            connection.execute(
                "ALTER TABLE public.hidden_road_observations RENAME TO road_observations"
            )

    assert all(response.status_code == 200 for response in responses)
    assert {response.headers["etag"] for response in responses} != set()
    assert len({response.headers["etag"] for response in responses}) == 1
