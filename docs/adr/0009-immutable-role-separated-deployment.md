# ADR 0009: Immutable role-separated backend deployment

- Status: accepted
- Date: 2026-07-28

## Decision

MichiSonae backend v1 is packaged as one immutable, digest-addressed container
image. The same artifact is started as exactly one of four roles: controlled
migration job, stateless HTTP API, projection worker, or snapshot worker.

Runtime containers use UID/GID 10001, a read-only root filesystem, a bounded
temporary filesystem, no added Linux capabilities, and file-mounted secrets.
API and worker roles use different PostgreSQL login principals granted
non-login group roles. The migration principal is not available to normal
runtime roles.

PostgreSQL/PostGIS remains the only correctness dependency. Regional snapshots
are cacheable at a CDN; object storage is an optional distribution binding,
not a second source of truth. Redis is not required.

Production migration is an explicit one-shot release step. It never runs in
API startup and never runs once per replica. Releases follow
expand/migrate/canary/contract sequencing and can roll back only to an artifact
compatible with the migrated schema.

## Consequences

- one SBOM and vulnerability result applies to every process role;
- replicas can scale independently without duplicate migration attempts;
- credential compromise is bounded by role;
- the provider and region can be selected later without changing application
  source;
- actual cloud creation still requires an approved provider-specific IaC
  change, credentials, data-residency decision, and owner authorization.
