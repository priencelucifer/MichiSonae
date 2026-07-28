# Infrastructure

Infrastructure is separated by environment:

- `local`: reproducible developer dependencies;
- `staging`: production-like integration and migration testing;
- `production`: managed data services, backups, alerts and controlled deploys.

The ten-person alpha should stay inexpensive. Do not provision multi-region
production capacity before the transactional ingest and field-quality gates
pass.

## Services

- PostgreSQL/PostGIS as system of record;
- transactional outbox projector with database-backed leases, dead letters and
  deterministic rebuild;
- Redis only as cache/transport optimization;
- object storage plus CDN for versioned regional hazard snapshots;
- centralized metrics, logs, traces and mobile crash reporting;
- managed secrets and backup/PITR.

Local PostgreSQL/PostGIS, controlled migrations and the early-scale projector
are now defined. Redis is intentionally not required for correctness. No
production environment is provisioned yet because provider, region,
data-residency, backup and incident-ownership decisions are still open.
