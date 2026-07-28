# ADR 0003: Durable and idempotent observation ingestion

- Status: Accepted
- Date: 2026-07-28

## Context

Phones may retry an observation after timeouts, process restarts or uncertain
network delivery. A server response must never claim acceptance when only part
of a batch is stored or when downstream work can be lost.

## Decision

- PostgreSQL/PostGIS is the observation system of record.
- `event_id` is the immutable idempotency key.
- The server stores a canonical payload hash with each event.
- An identical retry is a successful no-op and creates no second outbox item.
- Reusing an `event_id` with different content rejects the whole batch with
  `409 Conflict`.
- New observations and one outbox item per observation commit in the same
  database transaction.
- `202 Accepted` means that transaction committed. Database or outbox failure
  returns `503` and rolls back the batch.
- Schema migrations run as a separate controlled command under a PostgreSQL
  advisory lock. Application replicas never migrate on startup.
- Readiness requires a working connection and the expected migration checksum.

## Consequences

- Client retries after an ambiguous network failure are safe.
- Outbox consumers can be restarted without losing accepted events.
- Mutating an event while retaining its ID is explicitly unsupported.
- Production deployment must run and verify migrations before making new
  replicas ready.
- Outbox delivery and public hazard projection remain separate server slices.
