from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from fakes import MemoryObservationStore, MemorySecurityService
from fastapi.testclient import TestClient
from pydantic import ValidationError

import michisonae_api.main as main_module
from michisonae_api.main import create_app
from michisonae_api.models import ObservationBatch, ObservationBatchAccepted
from michisonae_api.settings import Settings

ROOT = Path(__file__).parents[3]
VECTOR_PATH = ROOT / "contracts" / "test-vectors" / "observation-ingestion.v1.json"
SCHEMA_PATH = ROOT / "contracts" / "events" / "road-observation.v1.schema.json"
FIXED_NOW = datetime(2026, 1, 2, 3, 5, tzinfo=UTC)


def vectors() -> dict[str, Any]:
    return json.loads(VECTOR_PATH.read_text(encoding="utf-8"))


def test_shared_vectors_match_backend_request_and_response_models() -> None:
    golden = vectors()
    event_schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    for name in ("initial", "overlap"):
        request = golden[name]["request"]
        validated = ObservationBatch.model_validate(request)
        assert validated.schema_version == golden["vector_version"]
        for observation in request["observations"]:
            assert set(observation) == set(event_schema["required"])
            assert set(observation) == set(event_schema["properties"])

        for response_name in ("acceptance", "identical_retry_acceptance"):
            if response_name in golden[name]:
                accepted = ObservationBatchAccepted.model_validate(golden[name][response_name])
                assert accepted.stored_count + accepted.duplicate_count == accepted.received_count


def test_shared_vectors_cover_partial_and_identical_retries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FixedDatetime(datetime):
        @classmethod
        def now(cls, tz: object = None) -> datetime:
            return FIXED_NOW if tz is not None else FIXED_NOW.replace(tzinfo=None)

    monkeypatch.setattr(main_module, "datetime", FixedDatetime)
    golden = vectors()
    store = MemoryObservationStore()
    security = MemorySecurityService(installation_id=golden["installation_id"])

    with TestClient(
        create_app(
            Settings(environment="test"),
            observation_store=store,
            security_service=security,
        )
    ) as client:
        headers = {"Authorization": f"Bearer {security.access_token}"}
        initial = client.post(
            "/v1/observations:batch",
            json=golden["initial"]["request"],
            headers=headers,
        )
        overlap = client.post(
            "/v1/observations:batch",
            json=golden["overlap"]["request"],
            headers=headers,
        )
        retry = client.post(
            "/v1/observations:batch",
            json=golden["overlap"]["request"],
            headers=headers,
        )

    assert initial.status_code == overlap.status_code == retry.status_code == 202
    assert initial.json() == golden["initial"]["acceptance"]
    assert overlap.json() == golden["overlap"]["acceptance"]
    assert retry.json() == golden["overlap"]["identical_retry_acceptance"]
    assert len(store.payload_hashes) == 3


def test_acceptance_model_rejects_uncontracted_fields() -> None:
    payload = vectors()["initial"]["acceptance"] | {"acknowledged_event_ids": []}

    with pytest.raises(ValidationError):
        ObservationBatchAccepted.model_validate(payload)
