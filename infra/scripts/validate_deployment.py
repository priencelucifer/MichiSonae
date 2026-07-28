from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlparse

IMAGE_PATTERN = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")
PLACEHOLDER_PATTERN = re.compile(r"(example\.com|replace-me|change-me|<[^>]+>)", re.I)

REQUIRED_KEYS = {
    "MICHI_ENVIRONMENT",
    "MICHI_IMAGE",
    "MICHI_PUBLIC_BASE_URL",
    "MICHI_TRUSTED_PROXY_CIDRS",
    "MICHI_MIGRATION_DATABASE_URL_SECRET_FILE",
    "MICHI_API_DATABASE_URL_SECRET_FILE",
    "MICHI_PROJECTION_DATABASE_URL_SECRET_FILE",
    "MICHI_SNAPSHOT_DATABASE_URL_SECRET_FILE",
    "MICHI_RATE_LIMIT_HASH_SECRET_FILE",
    "MICHI_API_REPLICAS",
    "MICHI_PROJECTION_REPLICAS",
    "MICHI_SNAPSHOT_REPLICAS",
    "MICHI_API_DATABASE_POOL_MAX_SIZE",
    "MICHI_PROJECTION_DATABASE_POOL_MAX_SIZE",
    "MICHI_SNAPSHOT_DATABASE_POOL_MAX_SIZE",
    "MICHI_DATABASE_CONNECTION_BUDGET",
    "MICHI_OBJECT_STORAGE_BUCKET",
    "MICHI_CDN_DISTRIBUTION_ID",
    "MICHI_TLS_CERTIFICATE_ID_OR_PATH",
    "MICHI_WAF_POLICY_ID",
    "MICHI_BACKUP_RETENTION_DAYS",
    "MICHI_PITR_WINDOW_DAYS",
    "MICHI_MONTHLY_BUDGET_USD",
    "MICHI_BUDGET_ALERT_PERCENTAGES",
}


def read_environment(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{number}: expected KEY=VALUE")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in values:
            raise ValueError(f"{path}:{number}: duplicate key {key}")
        values[key] = value.strip().strip('"').strip("'")
    return values


def positive_integer(values: dict[str, str], key: str, *, minimum: int = 1) -> int:
    try:
        value = int(values[key])
    except (KeyError, ValueError) as error:
        raise ValueError(f"{key} must be an integer") from error
    if value < minimum:
        raise ValueError(f"{key} must be at least {minimum}")
    return value


def validate(values: dict[str, str], *, template: bool, check_secret_files: bool) -> dict[str, int | str]:
    missing = sorted(REQUIRED_KEYS - values.keys())
    if missing:
        raise ValueError(f"missing deployment keys: {', '.join(missing)}")

    environment = values["MICHI_ENVIRONMENT"]
    if environment not in {"staging", "production"}:
        raise ValueError("MICHI_ENVIRONMENT must be staging or production")
    if not IMAGE_PATTERN.fullmatch(values["MICHI_IMAGE"]):
        raise ValueError("MICHI_IMAGE must be an immutable image@sha256 reference")

    replicas = {
        "api": positive_integer(values, "MICHI_API_REPLICAS"),
        "projection": positive_integer(values, "MICHI_PROJECTION_REPLICAS"),
        "snapshot": positive_integer(values, "MICHI_SNAPSHOT_REPLICAS"),
    }
    pools = {
        "api": positive_integer(values, "MICHI_API_DATABASE_POOL_MAX_SIZE"),
        "projection": positive_integer(values, "MICHI_PROJECTION_DATABASE_POOL_MAX_SIZE"),
        "snapshot": positive_integer(values, "MICHI_SNAPSHOT_DATABASE_POOL_MAX_SIZE"),
    }
    connection_budget = positive_integer(values, "MICHI_DATABASE_CONNECTION_BUDGET")
    required_connections = sum(replicas[name] * pools[name] for name in replicas)
    if required_connections > connection_budget:
        raise ValueError(
            f"replica pools require {required_connections} connections, "
            f"above budget {connection_budget}"
        )

    backup_days = positive_integer(values, "MICHI_BACKUP_RETENTION_DAYS")
    pitr_days = positive_integer(values, "MICHI_PITR_WINDOW_DAYS")
    if backup_days < 7 or pitr_days < 7:
        raise ValueError("backup retention and PITR windows must each be at least 7 days")
    monthly_budget = positive_integer(values, "MICHI_MONTHLY_BUDGET_USD")
    alert_percentages = [
        int(item.strip()) for item in values["MICHI_BUDGET_ALERT_PERCENTAGES"].split(",")
    ]
    if alert_percentages != sorted(set(alert_percentages)) or any(
        item <= 0 or item > 100 for item in alert_percentages
    ):
        raise ValueError("budget alert percentages must be unique, increasing, and within 1..100")

    public_url = urlparse(values["MICHI_PUBLIC_BASE_URL"])
    if public_url.scheme != "https" or not public_url.netloc:
        raise ValueError("MICHI_PUBLIC_BASE_URL must be an absolute HTTPS URL")

    if not template:
        unresolved = sorted(
            key for key, value in values.items() if not value or PLACEHOLDER_PATTERN.search(value)
        )
        if unresolved:
            raise ValueError(f"unresolved deployment values: {', '.join(unresolved)}")

    secret_keys = (
        "MICHI_MIGRATION_DATABASE_URL_SECRET_FILE",
        "MICHI_API_DATABASE_URL_SECRET_FILE",
        "MICHI_PROJECTION_DATABASE_URL_SECRET_FILE",
        "MICHI_SNAPSHOT_DATABASE_URL_SECRET_FILE",
        "MICHI_RATE_LIMIT_HASH_SECRET_FILE",
    )
    if check_secret_files:
        for key in secret_keys:
            path = Path(values[key])
            if not path.is_file():
                raise ValueError(f"{key} does not point to a readable file")

    return {
        "environment": environment,
        "connection_budget": connection_budget,
        "required_connections": required_connections,
        "headroom_connections": connection_budget - required_connections,
        "backup_retention_days": backup_days,
        "pitr_window_days": pitr_days,
        "monthly_budget_usd": monthly_budget,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate a MichiSonae deployment environment.")
    parser.add_argument("environment_file", type=Path)
    parser.add_argument(
        "--template",
        action="store_true",
        help="Allow documented placeholder values while validating the complete schema.",
    )
    parser.add_argument(
        "--check-secret-files",
        action="store_true",
        help="Require every referenced secret file to exist.",
    )
    arguments = parser.parse_args()
    result = validate(
        read_environment(arguments.environment_file),
        template=arguments.template,
        check_secret_files=arguments.check_secret_files,
    )
    print(json.dumps({"valid": True, **result}, sort_keys=True))


if __name__ == "__main__":
    main()
