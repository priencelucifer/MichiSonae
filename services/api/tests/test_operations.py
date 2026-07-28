from datetime import UTC, datetime

import pytest

from michisonae_api.maintenance import (
    RETENTION_CONFIRMATION,
    _cutoff,
    _require_production_confirmation,
)
from michisonae_api.operations import ConsistencyResult, PostgresMaintenance
from michisonae_api.settings import Settings


def maintenance(**overrides: object) -> PostgresMaintenance:
    return PostgresMaintenance(
        Settings(
            environment="test",
            database_url="postgresql://unused/maintenance",
            **overrides,
        )
    )


def test_cutoff_requires_timezone_and_normalizes_to_utc() -> None:
    parsed = _cutoff("2026-07-28T10:30:00+05:30", datetime.now(UTC))

    assert parsed == datetime(2026, 7, 28, 5, 0, tzinfo=UTC)
    with pytest.raises(ValueError, match="timezone"):
        _cutoff("2026-07-28T10:30:00", datetime.now(UTC))


def test_production_mutations_require_exact_confirmation() -> None:
    production = Settings(
        environment="production",
        rate_limit_hash_secret="a-production-secret-that-is-long-enough",
    )

    with pytest.raises(ValueError, match=RETENTION_CONFIRMATION):
        _require_production_confirmation(production, None, RETENTION_CONFIRMATION)
    _require_production_confirmation(
        production,
        RETENTION_CONFIRMATION,
        RETENTION_CONFIRMATION,
    )
    _require_production_confirmation(
        Settings(environment="test"),
        None,
        RETENTION_CONFIRMATION,
    )


def test_maintenance_rejects_unsafe_cutoffs_and_batch_sizes() -> None:
    service = maintenance()

    with pytest.raises(ValueError, match="timezone"):
        service.retention(
            cutoff=datetime(2026, 7, 28),
            dry_run=True,
        )
    with pytest.raises(ValueError, match="between 1 and 5000"):
        service.retention(
            cutoff=datetime.now(UTC),
            dry_run=True,
            batch_size=0,
        )


def test_consistency_result_reports_every_invariant() -> None:
    clean = ConsistencyResult(0, 0, 0, 0, 0, 0)
    broken = ConsistencyResult(0, 0, 1, 0, 0, 0)

    assert clean.is_consistent
    assert not broken.is_consistent
