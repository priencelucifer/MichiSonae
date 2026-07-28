from datetime import UTC, datetime, timedelta

from fakes import MemorySecurityService, MemorySnapshotStore, snapshot_record
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.settings import Settings
from michisonae_api.snapshots import (
    canonical_snapshot_bytes,
    empty_snapshot_etag,
    parse_region_id,
    snapshot_content,
)

REGION_ID = "gh5:wh9hx"
VERSION = "b" * 64


def test_region_id_is_global_deterministic_and_precision_bound() -> None:
    assert parse_region_id(REGION_ID, 5) == "wh9hx"
    assert parse_region_id("gh5:WH7D9", 5) is None
    assert parse_region_id("gh4:wh7d", 5) is None
    assert parse_region_id("gh5:wh7di", 5) is None


def test_snapshot_content_is_deterministic_and_excludes_internal_identity() -> None:
    now = datetime(2026, 7, 28, 12, 0, tzinfo=UTC)
    rows = [
        (
            "road_damage:wh9hxx2q",
            "road_damage",
            26.14451234,
            91.73623456,
            0.71234,
            0.84567,
            3,
            "confirmed",
            "unmatched",
            None,
            now,
            now,
            "projection-v1",
            now,
        )
    ]

    first = snapshot_content(REGION_ID, rows)
    second = snapshot_content(REGION_ID, rows)

    assert canonical_snapshot_bytes(first) == canonical_snapshot_bytes(second)
    encoded = canonical_snapshot_bytes(first).decode()
    assert "installation" not in encoded
    assert "payload" not in encoded
    assert "road_damage:wh9hxx2q" not in encoded
    assert first["hazards"][0]["latitude"] == 26.144512


def test_empty_region_is_honest_and_conditionally_cacheable() -> None:
    store = MemorySnapshotStore()
    security = MemorySecurityService()
    with TestClient(
        create_app(
            Settings(environment="test"),
            snapshot_store=store,
            security_service=security,
        ),
    ) as client:
        first = client.get(f"/v1/regions/{REGION_ID}/hazards")
        cached = client.get(
            f"/v1/regions/{REGION_ID}/hazards",
            headers={"If-None-Match": f'"{empty_snapshot_etag(REGION_ID)}"'},
        )

    assert first.status_code == 200
    assert first.json()["coverage"] == {
        "status": "unknown",
        "basis": "no_published_snapshot",
    }
    assert first.json()["hazards"] == []
    assert first.json()["version"] is None
    assert first.headers["cache-control"] == "public, max-age=30"
    assert cached.status_code == 304
    assert cached.content == b""
    assert store.opened
    assert store.closed


def test_current_snapshot_supports_etag_and_staleness_headers() -> None:
    generated_at = datetime.now(UTC) - timedelta(hours=1)
    record = snapshot_record(
        region_id=REGION_ID,
        version=VERSION,
        generated_at=generated_at,
    )
    store = MemorySnapshotStore((record,))
    security = MemorySecurityService()
    app_settings = Settings(environment="test", snapshot_stale_after_seconds=60)
    with TestClient(
        create_app(
            app_settings,
            snapshot_store=store,
            security_service=security,
        ),
    ) as client:
        response = client.get(f"/v1/regions/{REGION_ID}/hazards")
        cached = client.get(
            f"/v1/regions/{REGION_ID}/hazards",
            headers={"If-None-Match": f'W/"{VERSION}"'},
        )

    assert response.status_code == 200
    assert response.json()["version"] == VERSION
    assert response.json()["hazard_count"] == 1
    assert response.headers["etag"] == f'"{VERSION}"'
    assert response.headers["x-snapshot-freshness"] == "stale"
    assert "stale-while-revalidate=300" in response.headers["cache-control"]
    assert response.headers["vary"] == "Accept-Encoding"
    assert cached.status_code == 304


def test_immutable_version_has_long_lived_public_cache_policy() -> None:
    record = snapshot_record(
        region_id=REGION_ID,
        version=VERSION,
        generated_at=datetime.now(UTC),
    )
    with TestClient(
        create_app(
            Settings(environment="test"),
            snapshot_store=MemorySnapshotStore((record,)),
            security_service=MemorySecurityService(),
        ),
    ) as client:
        response = client.get(
            f"/v1/regions/{REGION_ID}/hazards",
            params={"version": VERSION},
        )

    assert response.status_code == 200
    assert response.headers["cache-control"] == "public, max-age=31536000, immutable"


def test_invalid_region_and_missing_immutable_version_are_distinct() -> None:
    with TestClient(
        create_app(
            Settings(environment="test"),
            snapshot_store=MemorySnapshotStore(),
            security_service=MemorySecurityService(),
        ),
    ) as client:
        invalid = client.get("/v1/regions/not-a-region/hazards")
        missing = client.get(
            f"/v1/regions/{REGION_ID}/hazards",
            params={"version": VERSION},
        )

    assert invalid.status_code == 400
    assert invalid.json()["code"] == "invalid_region_id"
    assert missing.status_code == 404
    assert missing.json()["code"] == "snapshot_version_not_found"


def test_snapshot_store_failure_returns_retryable_503() -> None:
    with TestClient(
        create_app(
            Settings(environment="test"),
            snapshot_store=MemorySnapshotStore(fail_reads=True),
            security_service=MemorySecurityService(),
        ),
    ) as client:
        response = client.get(f"/v1/regions/{REGION_ID}/hazards")

    assert response.status_code == 503
    assert response.json()["code"] == "hazard_snapshot_unavailable"
    assert response.headers["retry-after"] == "1"
