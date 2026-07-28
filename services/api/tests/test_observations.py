from datetime import UTC, datetime
from uuid import uuid4

from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.settings import Settings


def valid_batch() -> dict[str, object]:
    return {
        "schema_version": "1.0",
        "observations": [
            {
                "event_id": str(uuid4()),
                "installation_id": "anonymous-install-0001",
                "detected_at": datetime.now(UTC).isoformat(),
                "latitude": 26.1445,
                "longitude": 91.7362,
                "location_accuracy_m": 8.0,
                "speed_mps": 12.0,
                "kind": "rough_road",
                "severity": 0.7,
                "confidence": 0.8,
                "source": "phone",
                "detector_version": "phone-shadow-v1",
            }
        ],
    }


def test_valid_batch_is_not_acknowledged_without_durable_storage() -> None:
    client = TestClient(create_app(Settings(environment="test")))

    response = client.post("/v1/observations:batch", json=valid_batch())

    assert response.status_code == 503
    assert response.json()["code"] == "durable_ingestion_unavailable"


def test_invalid_location_is_rejected_before_ingestion_guard() -> None:
    client = TestClient(create_app(Settings(environment="test")))
    payload = valid_batch()
    observations = payload["observations"]
    assert isinstance(observations, list)
    observations[0]["latitude"] = 120.0

    response = client.post("/v1/observations:batch", json=payload)

    assert response.status_code == 422
