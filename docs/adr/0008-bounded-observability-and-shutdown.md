# ADR 0008: Bounded observability and shutdown

- Status: Accepted
- Date: 2026-07-28

## Context

The API, projection worker, and snapshot publisher must be diagnosable without
turning logs or metrics into a location, credential, or installation tracking
system. Operators also need to distinguish a live process from one that has not
started or cannot safely serve traffic.

Immediate cancellation of a worker can interrupt a database transaction. The
database transaction already prevents a partial acknowledgement, but normal
termination should allow the current bounded batch item to finish before pool
shutdown.

## Decision

- Every API request has a validated or generated UUID correlation ID.
  Ingestion stores it on the outbox record, projection copies it to regional
  snapshot work, security/operations audits store it, and worker logs bind it
  through a context variable.
- Logs are newline-delimited JSON in staging/production. A strict formatter
  exposes only bounded operational fields and redacts credentials, anonymous
  installation IDs, UUIDs in messages, and precise coordinate forms. Raw
  request bodies are never logged.
- Prometheus metrics use only fixed component, route template, method, status
  class, scope, queue, and outcome labels. Installation IDs, event IDs,
  correlation IDs, arbitrary paths, region IDs, and exception messages are not
  labels.
- The API exposes process-only liveness, startup completion, dependency/schema
  readiness, and `/metrics` separately. Readiness checks every configured
  PostgreSQL-backed dependency against the latest migration checksum.
- Each continuous worker exposes its own liveness, startup, readiness, and
  metrics HTTP listener. A worker is ready only after its pool opens and the
  required migration checksum is present.
- SIGINT/SIGTERM set a stop event. The continuous loop finishes its current
  `run_once` call, stops claiming new work, marks readiness false, and closes
  the pool within a configurable bound. Database leases still recover work
  after abnormal termination.
- A staging-only synthetic probe registers an anonymous installation, uploads
  one low-confidence point twice, verifies idempotency, projects and publishes,
  reads a regional snapshot, and revokes the installation. It never uploads a
  route or trip sequence and is forbidden in production.

## Consequences

- Metrics remain safe to aggregate and inexpensive as users and regions grow.
- Logs can be correlated across ingress and workers without logging event or
  installation identity in messages.
- The synthetic probe leaves one minimized staging observation until normal
  retention; it does not create or retain a trip sequence.
- Health ports must be bound to an internal operational network. TLS and
  scraper authentication are infrastructure responsibilities.
- A hard kill can still leave a lease until expiry, but cannot commit a partial
  business transaction.
