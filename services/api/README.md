# MichiSonae API

FastAPI service for anonymous installation/device authentication, durable road
observation ingestion and public hazard reads.

## Durable acceptance

`POST /v1/observations:batch` returns `202` only after one PostgreSQL
transaction has committed:

- every new observation;
- its exact canonical payload hash; and
- one corresponding `road-observation.v1` outbox record.

`event_id` is the idempotency key. An identical retry returns `202` with a
larger `duplicate_count` and creates no additional business effect. Reusing an
ID for different content returns `409` and rolls back the entire batch.
Database, pool, schema, or outbox failures return `503`; they are never
acknowledged as accepted.

The application never runs migrations automatically. A controlled migration
job must complete before deployment:

```powershell
$env:MICHI_DATABASE_URL = "postgresql://michisonae:michisonae@localhost:5432/michisonae"
python -m michisonae_api.migrate
```

`GET /health/live` checks only the process. `GET /health/ready` obtains a
database connection and verifies the checksum of the latest required migration.

## Hazard projection worker

`michi-project` leases committed outbox rows with PostgreSQL
`FOR UPDATE SKIP LOCKED`. It then updates the hazard projection and marks the
outbox row complete in one transaction. Processing is safe to retry:
`projection_processed_events` gives each observation one business effect.
Expired leases are recoverable, retries use bounded exponential backoff, and a
poison event moves to the dead-letter state after the configured attempt limit.

Consensus uses distinct anonymous installations, not packet count:

- one contributor is `community_unverified`;
- two contributors are `provisional`;
- three or more contributors are `confirmed`;
- severity is the median of each contributor's latest observation.

Clusters are currently deterministic geohash cells with `match_state=unmatched`.
They must not be described as road-matched. Map matching can later populate a
road segment without changing the ingestion or replay contract.

Run one batch, continuously consume, inspect status, or rebuild all derived
state from retained observations:

```powershell
.\.venv\Scripts\michi-project --once
.\.venv\Scripts\michi-project
.\.venv\Scripts\michi-project --status
.\.venv\Scripts\michi-project --rebuild
```

Rebuild takes an exclusive PostgreSQL advisory lock, clears only derived
projection tables, resets outbox delivery state, and drains retained source
observations. It does not delete accepted observations.

## Local development

Requirements:

- Python 3.12
- Docker with Compose for the PostgreSQL/PostGIS integration tests

Start the pinned local database from the repository root:

```powershell
docker compose -f infra/local/compose.yaml up -d postgres
```

Then install, migrate and test:

```powershell
cd services/api
python -m venv .venv
.\.venv\Scripts\pip install -e ".[dev]"
$env:MICHI_DATABASE_URL = "postgresql://michisonae:michisonae@localhost:5432/michisonae"
$env:MICHI_TEST_DATABASE_URL = $env:MICHI_DATABASE_URL
.\.venv\Scripts\python -m michisonae_api.migrate
.\.venv\Scripts\pytest
.\.venv\Scripts\ruff check .
.\.venv\Scripts\mypy
```

Run the API after migration:

```powershell
.\.venv\Scripts\uvicorn michisonae_api.main:app --reload
```

Environment variables use the `MICHI_` prefix. Pool size and acquisition
timeouts are configurable with:

- `MICHI_DATABASE_POOL_MIN_SIZE`
- `MICHI_DATABASE_POOL_MAX_SIZE`
- `MICHI_DATABASE_POOL_TIMEOUT_SECONDS`
- `MICHI_DATABASE_CONNECT_TIMEOUT_SECONDS`
- `MICHI_PROJECTION_BATCH_SIZE`
- `MICHI_PROJECTION_LEASE_SECONDS`
- `MICHI_PROJECTION_MAX_ATTEMPTS`
- `MICHI_PROJECTION_RETRY_BASE_SECONDS`
- `MICHI_PROJECTION_RETRY_MAX_SECONDS`
- `MICHI_PROJECTION_POLL_SECONDS`
- `MICHI_PROJECTION_GEOHASH_PRECISION`

Do not commit secrets in `.env` files.
