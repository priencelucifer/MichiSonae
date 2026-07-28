from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict, make_conninfo

from michisonae_api.migration import expected_migration, run_migrations
from michisonae_api.operations import PostgresMaintenance
from michisonae_api.settings import Settings

VERIFIED_TABLES = (
    "road_observations",
    "observation_outbox",
    "projection_processed_events",
    "hazard_clusters",
    "hazard_contributors",
    "hazard_projections",
    "retained_contributor_rollups",
    "regional_hazard_snapshots",
    "regional_snapshot_heads",
)


def run(
    command: list[str],
    *,
    stdin: Any = None,
    stdout: Any = subprocess.PIPE,
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        command,
        stdin=stdin,
        stdout=stdout,
        stderr=subprocess.PIPE,
        check=True,
    )


def database_connection(source: str, database_name: str) -> str:
    parameters = conninfo_to_dict(source)
    parameters["dbname"] = database_name
    return make_conninfo(**parameters)


def table_counts(database_url: str) -> dict[str, int]:
    with psycopg.connect(database_url) as connection:
        return {
            table: int(
                connection.execute(
                    sql.SQL("SELECT count(*) FROM public.{}").format(
                        sql.Identifier(table)
                    )
                ).fetchone()[0]
            )
            for table in VERIFIED_TABLES
        }


def verify_migration(database_url: str) -> None:
    migration = expected_migration()
    with psycopg.connect(database_url) as connection:
        row = connection.execute(
            """
            SELECT checksum
            FROM public.schema_migrations
            WHERE version = %s
            """,
            (migration.version,),
        ).fetchone()
    if row != (migration.checksum,):
        raise RuntimeError("restored database does not have the expected migration checksum")


def restore_drill(
    *,
    database_url: str,
    container: str,
    maximum_seconds: float,
) -> dict[str, Any]:
    run_migrations(database_url)
    source_parameters = conninfo_to_dict(database_url)
    source_database = source_parameters.get("dbname")
    source_user = source_parameters.get("user")
    if not source_database or not source_user:
        raise ValueError("database URL must include a database name and user")

    restored_database = f"michisonae_restore_drill_{uuid4().hex[:10]}"
    administration_url = database_connection(database_url, "postgres")
    restored_url = database_connection(database_url, restored_database)
    started = time.monotonic()
    dump_path: Path | None = None
    created = False
    try:
        with tempfile.NamedTemporaryFile(
            prefix="michisonae-restore-",
            suffix=".dump",
            delete=False,
        ) as dump_file:
            dump_path = Path(dump_file.name)
            run(
                [
                    "docker",
                    "exec",
                    container,
                    "pg_dump",
                    "--format=custom",
                    "--no-owner",
                    "--no-privileges",
                    "--username",
                    source_user,
                    "--dbname",
                    source_database,
                ],
                stdout=dump_file,
            )

        with psycopg.connect(administration_url, autocommit=True) as connection:
            connection.execute(
                sql.SQL("CREATE DATABASE {}").format(
                    sql.Identifier(restored_database)
                )
            )
        created = True

        with dump_path.open("rb") as dump_file:
            run(
                [
                    "docker",
                    "exec",
                    "--interactive",
                    container,
                    "pg_restore",
                    "--exit-on-error",
                    "--no-owner",
                    "--no-privileges",
                    "--username",
                    source_user,
                    "--dbname",
                    restored_database,
                ],
                stdin=dump_file,
            )

        verify_migration(restored_url)
        source_counts = table_counts(database_url)
        restored_counts = table_counts(restored_url)
        if restored_counts != source_counts:
            raise RuntimeError("restored table counts differ from the backup source")

        source_consistency = PostgresMaintenance(
            Settings(environment="test", database_url=database_url)
        ).consistency()
        restored_consistency = PostgresMaintenance(
            Settings(environment="test", database_url=restored_url)
        ).consistency()
        if restored_consistency != source_consistency:
            raise RuntimeError("restored consistency result differs from the backup source")

        elapsed_seconds = time.monotonic() - started
        if elapsed_seconds > maximum_seconds:
            raise RuntimeError(
                f"restore drill exceeded RTO budget: {elapsed_seconds:.3f}s"
            )
        return {
            "backup_source": source_database,
            "restored_database": restored_database,
            "elapsed_seconds": round(elapsed_seconds, 3),
            "rto_budget_seconds": maximum_seconds,
            "counts": restored_counts,
            "consistency": asdict(restored_consistency),
            "verified": True,
        }
    finally:
        if created:
            with psycopg.connect(administration_url, autocommit=True) as connection:
                connection.execute(
                    """
                    SELECT pg_terminate_backend(pid)
                    FROM pg_stat_activity
                    WHERE datname = %s
                      AND pid <> pg_backend_pid()
                    """,
                    (restored_database,),
                )
                connection.execute(
                    sql.SQL("DROP DATABASE IF EXISTS {}").format(
                        sql.Identifier(restored_database)
                    )
                )
        if dump_path is not None:
            dump_path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Dump PostgreSQL and restore it into an isolated drill database.",
    )
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--maximum-seconds", type=float, default=300.0)
    arguments = parser.parse_args()
    if arguments.maximum_seconds <= 0:
        parser.error("--maximum-seconds must be positive")

    try:
        result = restore_drill(
            database_url=arguments.database_url,
            container=arguments.container,
            maximum_seconds=arguments.maximum_seconds,
        )
    except subprocess.CalledProcessError as error:
        message = error.stderr.decode("utf-8", errors="replace").strip()
        raise SystemExit(f"backup/restore command failed: {message}") from error
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
