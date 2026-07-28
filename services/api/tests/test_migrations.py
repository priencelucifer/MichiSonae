from hashlib import sha256

from michisonae_api.migration import expected_migration, load_migrations


def test_packaged_migrations_are_ordered_and_checksummed() -> None:
    migrations = load_migrations()

    assert migrations
    assert tuple(migration.version for migration in migrations) == tuple(
        sorted(migration.version for migration in migrations)
    )
    assert all(
        migration.checksum == sha256(migration.sql.encode("utf-8")).hexdigest()
        for migration in migrations
    )
    assert expected_migration() == migrations[-1]
