import argparse

from michisonae_api.migration import run_migrations
from michisonae_api.settings import get_settings


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Apply MichiSonae PostgreSQL migrations under an advisory lock.",
    )
    parser.add_argument(
        "--database-url",
        help="Overrides MICHI_DATABASE_URL for this controlled migration run.",
    )
    arguments = parser.parse_args()
    database_url = arguments.database_url or get_settings().database_url
    if not database_url:
        parser.error("database URL required via --database-url or MICHI_DATABASE_URL")

    applied = run_migrations(database_url)
    if applied:
        print(f"Applied migrations: {', '.join(applied)}")
    else:
        print("Database schema is current.")


if __name__ == "__main__":
    main()
