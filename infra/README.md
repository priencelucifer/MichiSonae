# Infrastructure

Infrastructure is separated by environment:

- `local`: reproducible developer dependencies;
- `staging`: production-like integration and migration testing;
- `production`: managed data services, backups, alerts and controlled deploys.

The ten-person alpha should stay inexpensive. Do not provision multi-region
production capacity before the transactional ingest and field-quality gates
pass.

## Planned services

- PostgreSQL/PostGIS as system of record;
- transactional outbox projector;
- Redis only as cache/transport optimization;
- object storage plus CDN for versioned regional hazard snapshots;
- centralized metrics, logs, traces and mobile crash reporting;
- managed secrets and backup/PITR.

No production environment is defined in the initial commit because provider,
region and data-residency decisions are still open.
