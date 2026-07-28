# Infrastructure

Infrastructure is separated by environment:

- `local`: reproducible developer dependencies;
- `staging`: production-like integration and migration testing;
- `production`: managed data services, backups, alerts and controlled deploys.
- `deploy`: provider-neutral role-separated runtime contract;
- `database`: least-privilege runtime role grants;
- `load`: alpha, retry and commute-spike performance gate;
- `sizing`: cost ceiling and horizontal scaling signals.

The ten-person alpha should stay inexpensive. Do not provision multi-region
production capacity before the transactional ingest and field-quality gates
pass.

## Services

- PostgreSQL/PostGIS as system of record;
- transactional outbox projector with database-backed leases, dead letters and
  deterministic rebuild;
- object storage plus CDN for versioned regional hazard snapshots;
- centralized metrics, logs, traces and mobile crash reporting;
- managed secrets and backup/PITR.

Backend v1 is packaged as one immutable image with separate migration, API,
projection, and snapshot roles. Runtime roles are non-root/read-only and use
file-mounted secrets. CI validates restore/rebuild, least privilege, SBOM,
dependency/secret/IaC/container scans, and the alpha load gate.

No cloud environment is provisioned by this repository state. Provider, region,
data residency, incident owner, actual monthly quote, credentials, DNS, TLS,
WAF, managed PostGIS, backups/PITR, object storage, and CDN remain explicit
deployment inputs. Redis is intentionally not required for correctness.
