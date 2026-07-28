from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from importlib import resources

import psycopg

MIGRATION_FILES = (
    "0001_durable_observation_ingestion.sql",
    "0002_hazard_projection_worker.sql",
)
MIGRATION_LOCK_ID = 4_885_343_490_975_695_649


class MigrationError(RuntimeError):
    """Raised when the recorded migration history is not trustworthy."""


@dataclass(frozen=True)
class Migration:
    version: str
    checksum: str
    sql: str


def load_migrations() -> tuple[Migration, ...]:
    migration_root = resources.files("michisonae_api.migrations")
    loaded: list[Migration] = []
    for filename in MIGRATION_FILES:
        sql = migration_root.joinpath(filename).read_text(encoding="utf-8")
        loaded.append(
            Migration(
                version=filename.removesuffix(".sql"),
                checksum=sha256(sql.encode("utf-8")).hexdigest(),
                sql=sql,
            )
        )
    return tuple(loaded)


def expected_migration() -> Migration:
    return load_migrations()[-1]


def run_migrations(database_url: str) -> tuple[str, ...]:
    applied: list[str] = []
    migrations = load_migrations()

    with psycopg.connect(database_url) as connection:
        connection.execute(
            "SELECT pg_advisory_xact_lock(%s)",
            (MIGRATION_LOCK_ID,),
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS public.schema_migrations (
                version text PRIMARY KEY,
                checksum text NOT NULL,
                applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
            )
            """
        )

        for migration in migrations:
            row = connection.execute(
                """
                SELECT checksum
                FROM public.schema_migrations
                WHERE version = %s
                """,
                (migration.version,),
            ).fetchone()
            if row is not None:
                if row[0] != migration.checksum:
                    raise MigrationError(
                        f"checksum mismatch for applied migration {migration.version}"
                    )
                continue

            connection.execute(migration.sql)
            connection.execute(
                """
                INSERT INTO public.schema_migrations (version, checksum)
                VALUES (%s, %s)
                """,
                (migration.version, migration.checksum),
            )
            applied.append(migration.version)

    return tuple(applied)
