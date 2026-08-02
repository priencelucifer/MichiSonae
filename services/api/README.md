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

The backend and Android client consume the same committed golden ingestion
vectors. Integration tests also prove duplicate-only retries, mixed
new/duplicate batches, conflicting ID reuse and transaction rollback after a
partial failure. This keeps retry behavior compatible without coupling either
component to the other's implementation. The committed/generated OpenAPI test
also compares ingestion schema literals, types and collection/numeric bounds,
not only field names.

Stopped-user manual hazards use the same transaction and retry contract as
automatic phone observations. Migration `0007` adds obstruction, flooding,
manhole, construction and disabled-vehicle kinds without rewriting any
previously applied migration.

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

## Regional snapshot publisher

`michi-snapshot` converts current `provisional` and `confirmed` projections
into compact, immutable regional snapshots. Regions use a global geohash key
such as `gh5:wh9hx`; the precision is configurable but must be consistent
between publishers and API replicas.

```powershell
.\.venv\Scripts\michi-snapshot --once
.\.venv\Scripts\michi-snapshot
.\.venv\Scripts\michi-snapshot --status
.\.venv\Scripts\michi-snapshot --seed
```

Snapshots are content-addressed. Regenerating identical public content does not
create another version. Public payloads contain aggregate hazard locations and
confidence only; installation IDs and raw observations are never read by the
public endpoint.

`GET /v1/regions/{region_id}/hazards` returns the current snapshot with `ETag`,
`Last-Modified`, public `Cache-Control`, and an explicit freshness header. Send
`If-None-Match` for `304 Not Modified`, or add `?version=<sha256>` for an
immutable version suitable for a CDN. A region with no published snapshot
returns an empty list with `coverage.status=unknown`; it does not claim the
roads are hazard-free.

## Anonymous installation security

Core use requires no person account. The app registers one anonymous
installation and stores the returned opaque credentials in Android secure
storage:

- `POST /v1/installations:register` issues a server-generated installation ID,
  short-lived access token, and rotating refresh token;
- `POST /v1/auth:refresh` consumes each refresh token exactly once;
- `DELETE /v1/installations/current` revokes the installation and every token
  family;
- `POST /v1/observations:batch` requires a Bearer access token and rejects any
  body installation ID that differs from the authenticated installation.

Only SHA-256 credential hashes are stored. A reused refresh token revokes its
entire family, including the successor access token. Android must therefore
single-flight refresh: only one refresh request may be active for an
installation at a time. Token responses use `Cache-Control: no-store`.

Observations older than the configured offline window or too far in the future
are rejected before storage. Atomic PostgreSQL limits protect registration,
refresh, ingestion, and origin snapshot reads by HMAC-pseudonymized
installation/IP subjects. `X-Forwarded-For` is ignored unless the direct peer
is in `MICHI_TRUSTED_PROXY_CIDRS`; trusted chains are evaluated from right to
left so an attacker-supplied leftmost value cannot bypass an IP limit.

All responses carry a validated or server-generated `X-Correlation-ID`.
JSON content type and request size are enforced before endpoint processing.
Security audit rows contain event codes, pseudonymized IPs, and minimized
details—never credentials, raw request bodies, or trip sequences.

## Lifecycle and recovery operations

`michi-maintain` is the supported operator interface for retention,
dead-letter handling, consistency checks, and projection recovery.

```powershell
# Preview is the default and never deletes.
michi-maintain retention

# Apply after reviewing the preview.
michi-maintain retention --apply --confirm DELETE-EXPIRED-OBSERVATIONS

michi-maintain dead-letter status --kind outbox
michi-maintain dead-letter retry --kind outbox --key 123
michi-maintain dead-letter quarantine --kind outbox --key 123 `
    --reason confirmed_poison_event
michi-maintain dead-letter purge --kind outbox `
    --confirm PURGE-QUARANTINED-WORK

michi-maintain check
michi-maintain rebuild --confirm REBUILD-DERIVED-STATE
michi-maintain rebuild --region gh5:wh9hx `
    --confirm REBUILD-DERIVED-STATE
```

Production mutations require the exact confirmation shown above. Retention
deletes in bounded batches only when projection work completed and the event is
not current contributor evidence. Before deletion, repeated historical evidence
is folded into a compact rollup so a rebuild preserves the public hazard's
original detection time. Pending and quarantined work is never removed by the
retention command.

The raw-observation default is 90 days and can be configured from 30 to 90
days. Every mutation is recorded in `operations_audit_events` with a command
ID, bounded action/result fields, and counts. See the
[backup and recovery runbook](../../docs/operations/BACKUP_RESTORE.md) for RPO,
RTO, isolated restore, and projection recovery procedures.

## Observability and shutdown

The API exposes independent `/health/live`, `/health/startup`,
`/health/ready`, and `/metrics` endpoints. Readiness verifies every configured
PostgreSQL pool and the latest migration checksum; liveness never depends on
PostgreSQL.

Continuous `michi-project` and `michi-snapshot` processes expose the same
operational endpoints on ports `9101` and `9102`. Their Prometheus labels are
bounded enums and route templates. They never contain event, installation,
correlation, region, coordinate, token, IP, or exception-message values.

Staging and production require newline-delimited JSON logs. Correlation IDs
flow from API requests through outbox and snapshot work, while the formatter
redacts credentials, anonymous IDs, UUIDs embedded in messages, and precise
coordinate forms. Raw bodies and observation/snapshot payloads are never
logged.

SIGINT/SIGTERM stops new claims after the current bounded worker item finishes,
then marks readiness false and closes the pool within the configured timeout.
An abnormal kill remains safe through transactional work and expiring leases.

The staging-only synthetic probe verifies anonymous registration, an idempotent
one-point upload, projection, snapshot publication/read, and revocation:

```powershell
michi-probe --base-url https://staging-api.example.invalid
```

It is rejected in production and never uploads a route or trip sequence. See
the [observability guide](../../docs/operations/OBSERVABILITY.md) and
[incident runbooks](../../docs/operations/INCIDENTS.md).

## Deployment artifact

The repository-root `Dockerfile` packages API v1.0.0 once for four explicit
roles: `michi-migrate`, the Uvicorn API, `michi-project`, and
`michi-snapshot`. The runtime user is UID/GID 10001 and the deployment contract
adds a read-only filesystem, bounded `/tmp`, dropped capabilities, and
file-mounted PostgreSQL/rate-limit secrets.

Staging and production must use an immutable `image@sha256` reference.
Migrations are a one-shot release job and are never part of API startup.
Database principals are separated by role using
`infra/database/least-privilege-roles.sql`. See the
[release runbook](../../docs/operations/RELEASE.md) and
[load gate](../../docs/operations/LOAD_TESTING.md).

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
- `MICHI_DATABASE_URL_FILE`
- `MICHI_DATABASE_POOL_TIMEOUT_SECONDS`
- `MICHI_DATABASE_CONNECT_TIMEOUT_SECONDS`
- `MICHI_PROJECTION_BATCH_SIZE`
- `MICHI_PROJECTION_LEASE_SECONDS`
- `MICHI_PROJECTION_MAX_ATTEMPTS`
- `MICHI_PROJECTION_RETRY_BASE_SECONDS`
- `MICHI_PROJECTION_RETRY_MAX_SECONDS`
- `MICHI_PROJECTION_POLL_SECONDS`
- `MICHI_PROJECTION_GEOHASH_PRECISION`
- `MICHI_SNAPSHOT_REGION_GEOHASH_PRECISION`
- `MICHI_SNAPSHOT_BATCH_SIZE`
- `MICHI_SNAPSHOT_LEASE_SECONDS`
- `MICHI_SNAPSHOT_MAX_ATTEMPTS`
- `MICHI_SNAPSHOT_RETRY_BASE_SECONDS`
- `MICHI_SNAPSHOT_RETRY_MAX_SECONDS`
- `MICHI_SNAPSHOT_POLL_SECONDS`
- `MICHI_SNAPSHOT_CACHE_MAX_AGE_SECONDS`
- `MICHI_SNAPSHOT_STALE_WHILE_REVALIDATE_SECONDS`
- `MICHI_SNAPSHOT_STALE_AFTER_SECONDS`
- `MICHI_ACCESS_TOKEN_TTL_SECONDS`
- `MICHI_REFRESH_TOKEN_TTL_SECONDS`
- `MICHI_TOKEN_FAMILY_TTL_SECONDS`
- `MICHI_MAXIMUM_ACTIVE_ACCESS_TOKENS`
- `MICHI_OBSERVATION_MAXIMUM_AGE_SECONDS`
- `MICHI_OBSERVATION_FUTURE_SKEW_SECONDS`
- `MICHI_MAXIMUM_REQUEST_BYTES`
- `MICHI_TRUSTED_PROXY_CIDRS`
- `MICHI_RATE_LIMIT_HASH_SECRET`
- `MICHI_RATE_LIMIT_HASH_SECRET_FILE`
- `MICHI_REGISTRATION_RATE_LIMIT_PER_HOUR`
- `MICHI_REFRESH_RATE_LIMIT_PER_MINUTE`
- `MICHI_INGESTION_RATE_LIMIT_PER_MINUTE`
- `MICHI_PUBLIC_READ_RATE_LIMIT_PER_MINUTE`
- `MICHI_OBSERVATION_RETENTION_DAYS`
- `MICHI_MAINTENANCE_BATCH_SIZE`
- `MICHI_DEAD_LETTER_QUARANTINE_DAYS`
- `MICHI_LOG_LEVEL`
- `MICHI_JSON_LOGGING_ENABLED`
- `MICHI_WORKER_HEALTH_HOST`
- `MICHI_PROJECTION_HEALTH_PORT`
- `MICHI_SNAPSHOT_HEALTH_PORT`
- `MICHI_WORKER_SHUTDOWN_TIMEOUT_SECONDS`

`MICHI_RATE_LIMIT_HASH_SECRET` must be a separately managed secret with at
least 32 characters in staging/production. Do not commit secrets in `.env`
files.
