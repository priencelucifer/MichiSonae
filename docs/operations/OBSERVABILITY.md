# Backend observability guide

## Endpoints

The API exposes:

- `/health/live`: process-only liveness; never checks PostgreSQL.
- `/health/startup`: startup lifecycle completion.
- `/health/ready`: all configured pools are reachable and the latest migration
  checksum is present.
- `/metrics`: Prometheus text exposition.

The continuous projection and snapshot processes expose the same paths on
`MICHI_PROJECTION_HEALTH_PORT` (default `9101`) and
`MICHI_SNAPSHOT_HEALTH_PORT` (default `9102`). Bind these ports only to an
internal monitoring network.

## Metric policy

Allowed labels are fixed route templates, HTTP methods, status classes, known
rate-limit scopes, fixed component/queue names, and enumerated outcomes.
Never add installation, event, correlation, region, IP, token, exception
message, coordinate, or arbitrary request-path labels.

Important series:

- `michisonae_http_requests_total`
- `michisonae_http_request_duration_seconds`
- `michisonae_ingestion_observations_total`
- `michisonae_rate_limit_decisions_total`
- `michisonae_worker_items_total`
- `michisonae_queue_pending`
- `michisonae_queue_dead_letters`
- `michisonae_queue_oldest_pending_seconds`
- `michisonae_database_pool_connections`
- `michisonae_errors_total`
- `michisonae_process_started`
- `michisonae_process_ready`

Provider-neutral alert rules are in
`infra/observability/prometheus-alerts.yml`.

## Log policy

Staging and production require JSON logging. The formatter includes a timestamp,
level, logger, event message, correlation ID, and a small allowlist of
operational fields. It redacts token/password fields, Bearer values, MichiSonae
tokens, installation IDs, UUIDs embedded in messages, and coordinate forms.

Do not log request/response bodies, authorization headers, observation objects,
snapshot payloads, SQL parameter values, raw exception input, IP addresses, or
worker claim IDs. Use bounded failure codes from the projector and publisher.

## Synthetic staging probe

With the staging API running and the staging database configured:

```powershell
$env:MICHI_ENVIRONMENT = "staging"
$env:MICHI_DATABASE_URL = "<managed secret>"
michi-probe --base-url https://staging-api.example.invalid
```

The probe is intentionally rejected when `MICHI_ENVIRONMENT=production`. It
uploads one point, not a route. Its low-confidence observation follows the
normal raw retention policy.
