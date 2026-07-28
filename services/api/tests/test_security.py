from datetime import UTC, datetime, timedelta

import pytest
from fakes import MemoryObservationStore, MemorySecurityService
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.security import (
    AuthenticationRejected,
    bearer_token,
    client_ip,
)
from michisonae_api.settings import Settings


def observation(installation_id: str, detected_at: datetime | None = None) -> dict[str, object]:
    return {
        "schema_version": "1.0",
        "observations": [
            {
                "event_id": "e79c2332-3c93-4c87-a1ab-95e9de6a03d1",
                "installation_id": installation_id,
                "detected_at": (detected_at or datetime.now(UTC)).isoformat(),
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
        ],
    }


def test_registration_refresh_and_revocation_need_no_user_account() -> None:
    security = MemorySecurityService()
    app = create_app(Settings(environment="test"), security_service=security)
    with TestClient(app) as client:
        registered = client.post(
            "/v1/installations:register",
            json={"schema_version": "1.0"},
        )
        refreshed = client.post(
            "/v1/auth:refresh",
            json={
                "schema_version": "1.0",
                "refresh_token": registered.json()["refresh_token"],
            },
        )
        revoked = client.delete(
            "/v1/installations/current",
            headers={"Authorization": f"Bearer {refreshed.json()['access_token']}"},
        )

    assert registered.status_code == 201
    assert registered.json()["installation_id"].startswith("ins_")
    assert registered.headers["cache-control"] == "no-store"
    assert registered.headers["x-correlation-id"]
    assert refreshed.status_code == 200
    assert refreshed.json()["refresh_token"] != registered.json()["refresh_token"]
    assert revoked.status_code == 204
    assert security.revoked


def test_ingestion_requires_bound_installation_identity() -> None:
    security = MemorySecurityService()
    store = MemoryObservationStore()
    with TestClient(
        create_app(
            Settings(environment="test"),
            observation_store=store,
            security_service=security,
        )
    ) as client:
        missing = client.post(
            "/v1/observations:batch",
            json=observation(security.installation_id),
        )
        spoofed = client.post(
            "/v1/observations:batch",
            json=observation("anonymous-install-spoofed"),
            headers={"Authorization": f"Bearer {security.access_token}"},
        )

    assert missing.status_code == 401
    assert missing.json()["code"] == "missing_access_token"
    assert spoofed.status_code == 403
    assert spoofed.json()["code"] == "installation_identity_mismatch"
    assert store.payload_hashes == {}


def test_old_and_future_observations_are_rejected_before_storage() -> None:
    security = MemorySecurityService()
    store = MemoryObservationStore()
    app_settings = Settings(
        environment="test",
        observation_maximum_age_seconds=3600,
        observation_future_skew_seconds=60,
    )
    with TestClient(
        create_app(
            app_settings,
            observation_store=store,
            security_service=security,
        )
    ) as client:
        old = client.post(
            "/v1/observations:batch",
            json=observation(
                security.installation_id,
                datetime.now(UTC) - timedelta(hours=2),
            ),
            headers={"Authorization": f"Bearer {security.access_token}"},
        )
        future = client.post(
            "/v1/observations:batch",
            json=observation(
                security.installation_id,
                datetime.now(UTC) + timedelta(minutes=2),
            ),
            headers={"Authorization": f"Bearer {security.access_token}"},
        )

    assert old.status_code == 422
    assert future.status_code == 422
    assert old.json()["code"] == "observation_time_out_of_bounds"
    assert store.payload_hashes == {}


def test_request_content_type_and_size_are_bounded() -> None:
    security = MemorySecurityService()
    app_settings = Settings(environment="test", maximum_request_bytes=4096)
    with TestClient(
        create_app(app_settings, security_service=security),
    ) as client:
        wrong_type = client.post(
            "/v1/installations:register",
            content="{}",
            headers={"Content-Type": "text/plain"},
        )
        oversized = client.post(
            "/v1/installations:register",
            content="x" * 4097,
            headers={"Content-Type": "application/json"},
        )

    assert wrong_type.status_code == 415
    assert oversized.status_code == 413


def test_trusted_proxy_chain_uses_rightmost_untrusted_address() -> None:
    assert (
        client_ip(
            peer_ip="203.0.113.10",
            forwarded_for="198.51.100.1",
            trusted_proxy_cidrs="10.0.0.0/8",
        )
        == "203.0.113.10"
    )
    assert (
        client_ip(
            peer_ip="10.0.0.2",
            forwarded_for="192.0.2.99, 198.51.100.7, 10.0.0.1",
            trusted_proxy_cidrs="10.0.0.0/8",
        )
        == "198.51.100.7"
    )


def test_bearer_parser_rejects_non_bearer_credentials() -> None:
    assert bearer_token("Bearer valid-token") == "valid-token"
    try:
        bearer_token("Basic abc")
    except AuthenticationRejected as error:
        assert error.code == "invalid_access_token"
    else:
        raise AssertionError("non-Bearer credential was accepted")


def test_production_rejects_default_hash_secret_and_invalid_proxy_network() -> None:
    with pytest.raises(ValueError):
        Settings(environment="production")
    with pytest.raises(ValueError):
        Settings(environment="test", trusted_proxy_cidrs="10.0.0.1/8")
