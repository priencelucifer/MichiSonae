from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.migration import run_migrations
from michisonae_api.settings import Settings

DATABASE_URL = os.getenv("MICHI_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="MICHI_TEST_DATABASE_URL is required for PostgreSQL integration tests",
)


def settings(**overrides: object) -> Settings:
    assert DATABASE_URL is not None
    return Settings(
        environment="test",
        database_url=DATABASE_URL,
        database_pool_min_size=1,
        database_pool_max_size=16,
        database_pool_timeout_seconds=5,
        **overrides,
    )


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


def register(client: TestClient) -> dict[str, object]:
    response = client.post(
        "/v1/installations:register",
        json={
            "schema_version": "1.0",
            "attestation": "test-risk-signal-only",
        },
    )
    assert response.status_code == 201
    return response.json()


def observation(
    installation_id: str,
    *,
    event_id: str | None = None,
) -> dict[str, object]:
    return {
        "event_id": event_id or str(uuid4()),
        "installation_id": installation_id,
        "detected_at": datetime.now(UTC).isoformat(),
        "latitude": 26.1445,
        "longitude": 91.7362,
        "location_accuracy_m": 8.0,
        "speed_mps": 12.0,
        "kind": "road_damage",
        "severity": 0.7,
        "confidence": 0.8,
        "source": "phone",
        "detector_version": "phone-shadow-v1",
    }


def test_registration_stores_only_hashes_and_risk_presence() -> None:
    with TestClient(create_app(settings())) as client:
        credentials = register(client)

    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        installation = connection.execute(
            """
            SELECT installation_id, attestation_present, octet_length(attestation_hash)
            FROM public.anonymous_installations
            """
        ).fetchone()
        access_hash = connection.execute(
            "SELECT encode(token_hash, 'hex') FROM public.auth_access_tokens"
        ).fetchone()
        refresh_hash = connection.execute(
            "SELECT encode(token_hash, 'hex') FROM public.auth_refresh_tokens"
        ).fetchone()
        audit = connection.execute(
            "SELECT details::text FROM public.security_audit_events"
        ).fetchone()

    assert installation == (credentials["installation_id"], True, 32)
    assert access_hash is not None
    assert refresh_hash is not None
    assert credentials["access_token"] not in access_hash[0]
    assert credentials["refresh_token"] not in refresh_hash[0]
    assert audit is not None
    assert credentials["access_token"] not in audit[0]
    assert credentials["refresh_token"] not in audit[0]


def test_concurrent_refresh_creates_one_successor_and_reuse_revokes_family() -> None:
    with TestClient(create_app(settings())) as client:
        original = register(client)
        payload = {
            "schema_version": "1.0",
            "refresh_token": original["refresh_token"],
        }
        with ThreadPoolExecutor(max_workers=2) as executor:
            responses = list(
                executor.map(
                    lambda _: client.post("/v1/auth:refresh", json=payload),
                    range(2),
                )
            )

        successful = [response for response in responses if response.status_code == 200]
        rejected = [response for response in responses if response.status_code == 401]
        assert len(successful) == 1
        assert len(rejected) == 1
        assert rejected[0].json()["code"] == "refresh_token_reuse"

        successor_access = successful[0].json()["access_token"]
        rejected_ingestion = client.post(
            "/v1/observations:batch",
            json={
                "schema_version": "1.0",
                "observations": [observation(original["installation_id"])],
            },
            headers={"Authorization": f"Bearer {successor_access}"},
        )

    assert rejected_ingestion.status_code == 401
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        family = connection.execute(
            """
            SELECT revocation_reason
            FROM public.auth_token_families
            """
        ).fetchone()
        generations = connection.execute(
            "SELECT count(*), max(generation) FROM public.auth_refresh_tokens"
        ).fetchone()
    assert family == ("refresh_token_reuse",)
    assert generations == (2, 2)


def test_authenticated_ingestion_cannot_spoof_installation_identity() -> None:
    with TestClient(create_app(settings())) as client:
        credentials = register(client)
        spoofed = client.post(
            "/v1/observations:batch",
            json={
                "schema_version": "1.0",
                "observations": [observation(f"ins_{uuid4().hex}")],
            },
            headers={"Authorization": f"Bearer {credentials['access_token']}"},
        )

    assert spoofed.status_code == 403
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        count = connection.execute("SELECT count(*) FROM public.road_observations").fetchone()
    assert count == (0,)


def test_atomic_installation_rate_limit_and_proxy_spoof_resistance() -> None:
    app_settings = settings(ingestion_rate_limit_per_minute=5)
    with TestClient(create_app(app_settings)) as client:
        credentials = register(client)

        def ingest(index: int) -> int:
            response = client.post(
                "/v1/observations:batch",
                json={
                    "schema_version": "1.0",
                    "observations": [
                        observation(
                            credentials["installation_id"],
                            event_id=str(uuid4()),
                        )
                    ],
                },
                headers={
                    "Authorization": f"Bearer {credentials['access_token']}",
                    "X-Forwarded-For": f"198.51.100.{index + 1}",
                },
            )
            if response.status_code == 429:
                assert int(response.headers["retry-after"]) >= 1
            return response.status_code

        with ThreadPoolExecutor(max_workers=16) as executor:
            statuses = list(executor.map(ingest, range(16)))

    assert statuses.count(202) == 5
    assert statuses.count(429) == 11
    assert DATABASE_URL is not None
    with psycopg.connect(DATABASE_URL) as connection:
        count = connection.execute("SELECT count(*) FROM public.road_observations").fetchone()
        ip_subjects = connection.execute(
            """
            SELECT count(*)
            FROM public.security_rate_limits
            WHERE scope = 'ingestion_ip'
            """
        ).fetchone()
    assert count == (5,)
    assert ip_subjects == (1,)
