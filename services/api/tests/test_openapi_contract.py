from pathlib import Path
from typing import Any

import yaml

from michisonae_api.main import create_app
from michisonae_api.settings import Settings

CONTRACT_PATH = Path(__file__).parents[3] / "contracts" / "openapi" / "michisonae-api.v1.yaml"


def test_committed_openapi_operations_match_generated_application_contract() -> None:
    committed: dict[str, Any] = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    generated = create_app(Settings(environment="test")).openapi()

    assert committed["info"] == {
        "title": generated["info"]["title"],
        "version": generated["info"]["version"],
    }
    assert set(committed["paths"]) == set(generated["paths"])
    for path, committed_path in committed["paths"].items():
        assert set(committed_path) == set(generated["paths"][path])
        for method, committed_operation in committed_path.items():
            generated_operation = generated["paths"][path][method]
            assert committed_operation["operationId"] == generated_operation["operationId"]
            assert set(committed_operation["responses"]) == set(generated_operation["responses"])


def test_committed_schemas_match_generated_required_fields() -> None:
    committed: dict[str, Any] = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    generated = create_app(Settings(environment="test")).openapi()

    for schema_name in (
        "AnonymousCredentials",
        "HazardCoverage",
        "InstallationRegistration",
        "ObservationBatch",
        "ObservationBatchAccepted",
        "PublicHazard",
        "RefreshCredentialRequest",
        "RegionalHazardSnapshot",
    ):
        committed_schema = committed["components"]["schemas"][schema_name]
        generated_schema = generated["components"]["schemas"][schema_name]
        assert set(committed_schema["required"]) == set(generated_schema["required"])
        assert set(committed_schema["properties"]) == set(generated_schema["properties"])
        assert committed_schema.get("additionalProperties") == generated_schema.get(
            "additionalProperties"
        )


def test_ingestion_schema_literals_bounds_and_types_match() -> None:
    committed: dict[str, Any] = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    generated = create_app(Settings(environment="test")).openapi()

    def signature(schema: dict[str, Any]) -> dict[str, Any]:
        properties = schema["properties"]
        return {
            name: {
                key: value
                for key, value in definition.items()
                if key in {"const", "type", "minimum", "maximum", "minItems", "maxItems"}
            }
            for name, definition in properties.items()
        }

    for schema_name in ("ObservationBatch", "ObservationBatchAccepted"):
        committed_schema = committed["components"]["schemas"][schema_name]
        generated_schema = generated["components"]["schemas"][schema_name]
        assert signature(committed_schema) == signature(generated_schema)
