from __future__ import annotations

import subprocess
import sys
import tomllib
from pathlib import Path

import yaml

ROOT = Path(__file__).parents[3]


def test_container_is_non_root_and_has_no_source_copy() -> None:
    dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
    dockerignore = (ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines()

    assert "USER 10001:10001" in dockerfile
    assert "HEALTHCHECK" in dockerfile
    assert "COPY . " not in dockerfile
    assert ".env" in dockerignore
    assert "**/secrets" in dockerignore


def test_every_direct_runtime_dependency_is_exactly_locked() -> None:
    project = tomllib.loads(
        (ROOT / "services" / "api" / "pyproject.toml").read_text(encoding="utf-8")
    )
    lock_lines = {
        line.split(";", 1)[0].strip().lower().replace("_", "-")
        for line in (ROOT / "services" / "api" / "requirements.lock")
        .read_text(encoding="utf-8")
        .splitlines()
        if line and not line.startswith("#")
    }

    for dependency in project["project"]["dependencies"]:
        name, version = dependency.split("==", 1)
        normalized_name = name.split("[", 1)[0].lower().replace("_", "-")
        assert f"{normalized_name}=={version.lower()}" in lock_lines


def test_deployed_roles_are_isolated_and_migration_is_explicit() -> None:
    deployment = yaml.safe_load(
        (ROOT / "infra" / "deploy" / "compose.yaml").read_text(encoding="utf-8")
    )
    services = deployment["services"]

    assert set(services) == {"migrate", "api", "projection", "snapshot"}
    assert services["migrate"]["profiles"] == ["release"]
    assert services["migrate"]["restart"] == "no"
    assert "michi-migrate" not in " ".join(services["api"]["command"])
    for name in ("api", "projection", "snapshot"):
        assert services[name]["read_only"] is True
        assert services[name]["user"] == "10001:10001"
        assert services[name]["cap_drop"] == ["ALL"]


def test_staging_and_production_templates_pass_schema_validation() -> None:
    script = ROOT / "infra" / "scripts" / "validate_deployment.py"
    for environment in ("staging", "production"):
        result = subprocess.run(
            [
                sys.executable,
                str(script),
                str(ROOT / "infra" / environment / ".env.example"),
                "--template",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
        assert '"valid": true' in result.stdout
