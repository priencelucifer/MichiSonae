# Local infrastructure

The local environment runs PostgreSQL 17 with PostGIS 3.5, matching the
recommended stable PostGIS image line for PostgreSQL 17.

From the repository root, start the complete backend:

```powershell
docker compose -f infra/local/compose.yaml up --build -d
docker compose -f infra/local/compose.yaml ps
```

Compose runs one controlled migration job, then the non-root/read-only API,
projection worker, and snapshot worker. The API is available at
`http://127.0.0.1:8000`. The development-only database connection URL is:

```text
postgresql://michisonae:michisonae@localhost:5432/michisonae
```

To apply a new schema change explicitly after the stack already exists:

```powershell
docker compose -f infra/local/compose.yaml run --rm migrate
```

Stop the container without removing its data:

```powershell
docker compose -f infra/local/compose.yaml down
```

For a deliberate destructive reset, first confirm no local data is needed,
then use `docker compose -f infra/local/compose.yaml down --volumes` and rerun
the migrations.
