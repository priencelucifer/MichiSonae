from datetime import UTC, datetime
from uuid import uuid4

from fakes import MemoryObservationStore
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.settings import Settings


def valid_batch(*, event_id: str | None = None) -> dict[str, object]:
    return {
        "schema_version": "1.0",
        "observations": [
            {
                "event_id": event_id or str(uuid4()),
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
    assert response.headers["retry-after"] == "1"


def test_committed_batch_is_acknowledged_and_retry_is_idempotent() -> None:
    event_id = str(uuid4())
    payload = valid_batch(event_id=event_id)
    store = MemoryObservationStore()
    with TestClient(
        create_app(Settings(environment="test"), observation_store=store),
    ) as client:
        first = client.post(
            "/v1/observations:batch",
            json=payload,
        )
        retry = client.post(
            "/v1/observations:batch",
            json=payload,
        )

    assert first.status_code == 202
    assert first.json() == {
        "schema_version": "1.0",
        "received_count": 1,
        "stored_count": 1,
        "duplicate_count": 0,
    }
    assert retry.status_code == 202
    assert retry.json()["stored_count"] == 0
    assert retry.json()["duplicate_count"] == 1


def test_reused_event_id_with_different_content_rejects_entire_batch() -> None:
    event_id = str(uuid4())
    store = MemoryObservationStore()
    with TestClient(
        create_app(Settings(environment="test"), observation_store=store),
    ) as client:
        original = valid_batch(event_id=event_id)
        assert client.post("/v1/observations:batch", json=original).status_code == 202

        conflicting = valid_batch(event_id=event_id)
        observations = conflicting["observations"]
        assert isinstance(observations, list)
        observations[0]["severity"] = 0.1
        response = client.post("/v1/observations:batch", json=conflicting)

    assert response.status_code == 409
    assert response.json()["code"] == "event_id_conflict"


def test_store_failure_is_never_acknowledged() -> None:
    store = MemoryObservationStore(fail_ingestion=True)
    with TestClient(
        create_app(Settings(environment="test"), observation_store=store),
    ) as client:
        response = client.post("/v1/observations:batch", json=valid_batch())

    assert response.status_code == 503
    assert response.json()["code"] == "durable_ingestion_unavailable"


def test_duplicate_event_ids_inside_one_batch_are_rejected() -> None:
    event_id = str(uuid4())
    payload = valid_batch(event_id=event_id)
    observations = payload["observations"]
    assert isinstance(observations, list)
    observations.append(dict(observations[0]))

    client = TestClient(
        create_app(
            Settings(environment="test"),
            observation_store=MemoryObservationStore(),
        ),
    )
    response = client.post("/v1/observations:batch", json=payload)

    assert response.status_code == 422


def test_invalid_location_is_rejected_before_ingestion_guard() -> None:
    client = TestClient(create_app(Settings(environment="test")))
    payload = valid_batch()
    observations = payload["observations"]
    assert isinstance(observations, list)
    observations[0]["latitude"] = 120.0

    response = client.post("/v1/observations:batch", json=payload)

    assert response.status_code == 422
