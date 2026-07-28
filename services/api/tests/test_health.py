from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.settings import Settings


def test_liveness_is_independent_of_dependencies() -> None:
    client = TestClient(create_app(Settings(environment="test")))

    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json()["status"] == "live"


def test_readiness_fails_without_durable_store() -> None:
    client = TestClient(create_app(Settings(environment="test")))

    response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["code"] == "durable_ingestion_unavailable"
