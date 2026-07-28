from __future__ import annotations

import asyncio
import os
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID, uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.migration import run_migrations
from michisonae_api.operations import PostgresMaintenance
from michisonae_api.projection import PostgresProjectionWorker
from michisonae_api.settings import Settings
from michisonae_api.snapshots import PostgresSnapshotPublisher

DATABASE_URL = os.getenv("MICHI_TEST_DATABASE_URL")
AUTHORIZATIONS: dict[str, tuple[str, str]] = {}
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="MICHI_TEST_DATABASE_URL is required for PostgreSQL integration tests",
)


def settings(**overrides: Any) -> Settings:
    assert DATABASE_URL is not None
    return Settings(
        environment="test",
        database_url=DATABASE_URL,
        database_pool_min_size=1,
        database_pool_max_size=8,
        database_pool_timeout_seconds=5,
        registration_rate_limit_per_hour=1000,
        projection_poll_seconds=0.05,
        snapshot_poll_seconds=0.05,
        **overrides,
    )


def observation(
    alias: str,
    *,
    event_id: UUID | None = None,
    detected_at: datetime | None = None,
    latitude: float = 26.1445,
    longitude: float = 91.7362,
    severity: float = 0.7,
) -> dict[str, object]:
    return {
        "event_id": str(event_id or uuid4()),
        "installation_id": alias,
        "detected_at": (detected_at or datetime.now(UTC)).isoformat(),
        "latitude": latitude,
        "longitude": longitude,
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


def project_and_publish(*, rebuild: bool = False) -> tuple[int, int, int]:
    async def execute() -> tuple[int, int, int]:
        projector = PostgresProjectionWorker(
            settings(),
            worker_id=f"operations-project-{uuid4().hex}",
        )
        publisher = PostgresSnapshotPublisher(
            settings(),
            worker_id=f"operations-snapshot-{uuid4().hex}",
        )
        await asyncio.gather(projector.open(), publisher.open())
        try:
            if rebuild:
                await projector.rebuild()
            projected = await projector.drain()
            rollups = await projector.apply_retained_rollups()
            await publisher.seed_current_regions()
            published = await publisher.drain()
            return (
                projected.projected_count,
                rollups,
                published.published_count,
            )
        finally:
            await asyncio.gather(projector.close(), publisher.close())

    return asyncio.run(execute())


def current_snapshot() -> tuple[str, dict[str, object]]:
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        row = connection.execute(
            """
            SELECT snapshot.version, snapshot.payload
            FROM public.regional_snapshot_heads AS head
            JOIN public.regional_hazard_snapshots AS snapshot
              ON snapshot.region_id = head.region_id
             AND snapshot.version = head.version
            """
        ).fetchone()
    assert row is not None
    return str(row[0]), row[1]


def test_retention_is_exact_resumable_and_preserves_deterministic_rebuild() -> None:
    now = datetime.now(UTC)
    expired_event = uuid4()
    latest_event = uuid4()
    peer_event = uuid4()
    pending_event = uuid4()
    submit(
        observation(
            "driver-a",
            event_id=expired_event,
            detected_at=now - timedelta(hours=1),
            severity=0.2,
        ),
        observation(
            "driver-a",
            event_id=latest_event,
            detected_at=now,
            severity=0.6,
        ),
        observation(
            "driver-b",
            event_id=peer_event,
            detected_at=now,
            severity=0.8,
        ),
    )
    assert project_and_publish()[0] == 3
    before = current_snapshot()
    submit(
        observation(
            "driver-c",
            event_id=pending_event,
            detected_at=now,
            latitude=26.1455,
        )
    )

    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            "UPDATE public.road_observations SET received_at = %s",
            (now - timedelta(days=91),),
        )

    maintenance = PostgresMaintenance(settings())
    cutoff = now - timedelta(days=90)
    preview = maintenance.retention(cutoff=cutoff, dry_run=True, batch_size=1)
    applied = maintenance.retention(cutoff=cutoff, dry_run=False, batch_size=1)
    resumed = maintenance.retention(cutoff=cutoff, dry_run=False, batch_size=1)

    assert preview.candidate_count == 1
    assert preview.deleted_count == 0
    assert applied.candidate_count == applied.deleted_count == 1
    assert resumed.candidate_count == resumed.deleted_count == 0
    with psycopg.connect(DATABASE_URL) as connection:
        retained = connection.execute(
            """
            SELECT event_id
            FROM public.road_observations
            ORDER BY event_id
            """
        ).fetchall()
        rollup = connection.execute(
            """
            SELECT observation_count, first_detected_at
            FROM public.retained_contributor_rollups
            """
        ).fetchone()
        audit_count = connection.execute(
            """
            SELECT count(*)
            FROM public.operations_audit_events
            WHERE action = 'observation_retention'
            """
        ).fetchone()
    assert {UUID(str(row[0])) for row in retained} == {
        latest_event,
        peer_event,
        pending_event,
    }
    assert rollup == (1, now - timedelta(hours=1))
    assert audit_count == (3,)

    rebuilt = project_and_publish(rebuild=True)
    assert rebuilt[:2] == (3, 1)
    assert current_snapshot() == before
    assert maintenance.consistency().is_consistent


def test_dead_letter_retry_quarantine_and_purge_are_audited_and_idempotent() -> None:
    event_id = uuid4()
    submit(observation("driver-a", event_id=event_id))
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        row = connection.execute(
            """
            UPDATE public.observation_outbox
            SET delivery_attempts = 5,
                dead_lettered_at = clock_timestamp(),
                dead_letter_reason = 'projection_processing_unavailable',
                last_error = 'projection_processing_unavailable'
            RETURNING id
            """
        ).fetchone()
    assert row is not None
    outbox_id = int(row[0])
    maintenance = PostgresMaintenance(settings())

    assert maintenance.dead_letter_status("outbox").retryable_count == 1
    assert maintenance.retry_dead_letter("outbox", outbox_id).affected_count == 1
    assert maintenance.retry_dead_letter("outbox", outbox_id).affected_count == 0

    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE public.observation_outbox
            SET dead_lettered_at = clock_timestamp(),
                dead_letter_reason = 'operator_test'
            WHERE id = %s
            """,
            (outbox_id,),
        )
    quarantined = maintenance.quarantine_dead_letter(
        "outbox",
        outbox_id,
        "confirmed_poison_event",
    )
    repeated = maintenance.quarantine_dead_letter(
        "outbox",
        outbox_id,
        "confirmed_poison_event",
    )
    assert quarantined.affected_count == 1
    assert repeated.affected_count == 0

    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE public.observation_outbox
            SET quarantined_at = clock_timestamp() - interval '8 days'
            WHERE id = %s
            """,
            (outbox_id,),
        )
    cutoff = datetime.now(UTC) - timedelta(days=7)
    assert maintenance.purge_quarantined("outbox", cutoff=cutoff, batch_size=1).affected_count == 1
    assert maintenance.purge_quarantined("outbox", cutoff=cutoff, batch_size=1).affected_count == 0
    with psycopg.connect(DATABASE_URL) as connection:
        counts = connection.execute(
            """
            SELECT
                (SELECT count(*) FROM public.road_observations),
                (SELECT count(*) FROM public.observation_outbox),
                (SELECT count(*) FROM public.operations_audit_events)
            """
        ).fetchone()
    assert counts == (0, 0, 6)


def test_snapshot_dead_letters_can_be_quarantined_and_purged() -> None:
    assert DATABASE_URL is not None
    region_id = "gh5:wh9hx"
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            INSERT INTO public.regional_snapshot_work (
                region_id,
                dead_lettered_at,
                dead_letter_reason
            )
            VALUES (%s, clock_timestamp(), 'snapshot_processing_unavailable')
            """,
            (region_id,),
        )
    maintenance = PostgresMaintenance(settings())

    assert maintenance.dead_letter_status("snapshot").retryable_count == 1
    assert (
        maintenance.quarantine_dead_letter(
            "snapshot",
            region_id,
            "invalid_projection_data",
        ).affected_count
        == 1
    )
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE public.regional_snapshot_work
            SET quarantined_at = clock_timestamp() - interval '8 days'
            WHERE region_id = %s
            """,
            (region_id,),
        )
    result = maintenance.purge_quarantined(
        "snapshot",
        cutoff=datetime.now(UTC) - timedelta(days=7),
    )

    assert result.affected_count == 1
    assert maintenance.dead_letter_status("snapshot").quarantined_count == 0


def test_consistency_checker_returns_nonzero_for_interrupted_projection() -> None:
    submit(observation("driver-a"))
    project_and_publish()
    maintenance = PostgresMaintenance(settings())
    assert maintenance.consistency().is_consistent

    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL, autocommit=True) as connection:
        connection.execute("UPDATE public.observation_outbox SET published_at = NULL")

    result = maintenance.consistency()
    assert not result.is_consistent
    assert result.processed_without_completion == 1


def test_regional_rebuild_preserves_other_regions_and_snapshot_content() -> None:
    submit(
        observation("guwahati-a", severity=0.2),
        observation("guwahati-b", severity=0.8),
        observation(
            "new-york-a",
            latitude=40.7128,
            longitude=-74.0060,
            severity=0.3,
        ),
        observation(
            "new-york-b",
            latitude=40.7128,
            longitude=-74.0060,
            severity=0.9,
        ),
    )
    assert project_and_publish()[0] == 4
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        before = dict(
            connection.execute(
                """
                SELECT snapshot.region_id, snapshot.payload
                FROM public.regional_snapshot_heads AS head
                JOIN public.regional_hazard_snapshots AS snapshot
                  ON snapshot.region_id = head.region_id
                 AND snapshot.version = head.version
                ORDER BY snapshot.region_id
                """
            ).fetchall()
        )
    assert len(before) == 2
    region_id = next(
        key
        for key, payload in before.items()
        if payload["hazards"][0]["latitude"] == pytest.approx(26.1445)
    )
    region_cell = region_id.split(":", maxsplit=1)[1]

    async def rebuild_region() -> tuple[int, int]:
        projector = PostgresProjectionWorker(
            settings(),
            worker_id="operations-regional-rebuild",
        )
        publisher = PostgresSnapshotPublisher(
            settings(),
            worker_id="operations-regional-snapshot",
        )
        await asyncio.gather(projector.open(), publisher.open())
        try:
            reset = await projector.rebuild_region(
                region_id=region_id,
                region_cell=region_cell,
            )
            await projector.drain()
            await projector.apply_retained_rollups(region_cell=region_cell)
            await publisher.seed_current_regions()
            await publisher.drain()
            return reset.reset_count, len(before)
        finally:
            await asyncio.gather(projector.close(), publisher.close())

    reset_count, region_count = asyncio.run(rebuild_region())
    with psycopg.connect(DATABASE_URL) as connection:
        after = dict(
            connection.execute(
                """
                SELECT snapshot.region_id, snapshot.payload
                FROM public.regional_snapshot_heads AS head
                JOIN public.regional_hazard_snapshots AS snapshot
                  ON snapshot.region_id = head.region_id
                 AND snapshot.version = head.version
                ORDER BY snapshot.region_id
                """
            ).fetchall()
        )

    assert reset_count == 2
    assert region_count == 2
    assert after == before
    assert PostgresMaintenance(settings()).consistency().is_consistent
