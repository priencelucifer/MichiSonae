# Local infrastructure

The local environment runs PostgreSQL 17 with PostGIS 3.5, matching the
recommended stable PostGIS image line for PostgreSQL 17.

From the repository root:

```powershell
docker compose -f infra/local/compose.yaml up -d postgres
docker compose -f infra/local/compose.yaml ps
```

The development-only connection URL is:

```text
postgresql://michisonae:michisonae@localhost:5432/michisonae
```

Apply schema changes explicitly; the API does not migrate on startup:

```powershell
$env:MICHI_DATABASE_URL = "postgresql://michisonae:michisonae@localhost:5432/michisonae"
services\api\.venv\Scripts\python -m michisonae_api.migrate
```

Stop the container without removing its data:

```powershell
docker compose -f infra/local/compose.yaml down
```

For a deliberate destructive reset, first confirm no local data is needed,
then use `docker compose -f infra/local/compose.yaml down --volumes` and rerun
the migrations.
