from pathlib import Path

import pytest

from michisonae_api.settings import Settings


def test_production_secrets_can_be_loaded_from_read_only_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("MICHI_DATABASE_URL", raising=False)
    database_file = tmp_path / "database_url"
    rate_limit_file = tmp_path / "rate_limit_secret"
    database_file.write_text(
        "postgresql://api:password@database.example/michisonae\n",
        encoding="utf-8",
    )
    rate_limit_file.write_text("x" * 32 + "\n", encoding="utf-8")

    settings = Settings(
        environment="production",
        database_url_file=str(database_file),
        rate_limit_hash_secret_file=str(rate_limit_file),
    )

    assert settings.database_url == "postgresql://api:password@database.example/michisonae"
    assert settings.rate_limit_hash_secret == "x" * 32


def test_file_backed_secrets_reject_ambiguity_and_empty_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("MICHI_DATABASE_URL", raising=False)
    empty_file = tmp_path / "empty"
    empty_file.write_text("\n", encoding="utf-8")

    with pytest.raises(ValueError, match="mutually exclusive"):
        Settings(
            database_url="postgresql://direct",
            database_url_file=str(empty_file),
        )
    with pytest.raises(ValueError, match="must not be empty"):
        Settings(database_url_file=str(empty_file))
