# ADR 0004: Retry-safe PostgreSQL hazard projection

- Status: Accepted
- Date: 2026-07-28

## Context

Accepted observations must become current hazard state despite worker crashes,
duplicate delivery, retries and concurrent consumers. The ten-person alpha
does not justify operating a separate broker, but the design must not require a
rewrite when traffic grows.

The current data has coordinates but no selected map-data provider or
road-segment matcher. Claiming that coordinate-only clusters are road-matched
would create unsafe precision.

## Decision

- PostgreSQL is both the authoritative observation store and the early-scale
  work queue.
- Workers lease ordered outbox batches with `FOR UPDATE SKIP LOCKED`. A crashed
  worker's lease expires and another worker may recover the item.
- Projection writes and outbox acknowledgement commit in one transaction.
- `projection_processed_events` makes repeat delivery a successful no-op.
- A cluster row lock serializes changes to the same cluster while unrelated
  clusters remain parallel.
- Processing uses a shared advisory transaction lock. A rebuild takes the
  exclusive form of the same lock before resetting derived state and delivery
  markers.
- Retry delay is bounded exponential backoff. Events that exhaust the configured
  attempt limit remain durably dead-lettered with a non-sensitive failure code.
- The first projection policy groups by observation kind and a configurable
  geohash cell. The cluster explicitly remains `unmatched` until a real
  road-segment matcher succeeds.
- Consensus counts distinct installation IDs. Repeated observations from the
  same installation update its latest evidence without increasing the
  contributor count.
- One contributor is `community_unverified`, two are `provisional`, and three or
  more are `confirmed`. Severity is the median of contributors' latest values.
- Every projection records `projection-v1`, so later policy changes can rebuild
  or roll back derived state.

## Consequences

- Queue correctness does not depend on Redis or an external broker.
- At-least-once processing has exactly one business effect.
- Projection state can be reproduced from retained source observations and the
  outbox.
- Multiple cells can process concurrently, but a very hot cell is serialized.
  Metrics and load tests must determine when the database queue needs replacing.
- Geohash boundaries may split a physical defect, and nearby parallel roads may
  share a cell. Public consumers must honor `match_state`; map matching and
  merge policy remain required before claiming road-level precision.
- Anonymous installation identity alone is not abuse-resistant. Authentication,
  replay windows and rate limits remain required before an open public launch.
