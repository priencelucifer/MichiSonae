# ADR 0005: Cacheable global regional hazard snapshots

- Status: Accepted
- Date: 2026-07-28

## Context

Many drivers in one area request substantially the same hazard data. Serving
each request by scanning observations or recalculating projections would expose
unnecessary data, couple read traffic to ingestion, and scale database work
linearly with users.

The product may launch outside Assam, so region identifiers cannot depend on a
single city's administrative boundaries. Coverage is initially sparse and an
empty result must not be presented as proof that a road is safe.

## Decision

- The global region ID is `gh<precision>:<geohash-cell>`. V1 defaults to
  precision 5, and every publisher and API replica must use the same configured
  precision.
- Projection transactions mark their coarse region dirty. Snapshot publishers
  lease dirty regions with PostgreSQL `SKIP LOCKED`, recover expired leases,
  retry with bounded backoff, and dead-letter poison work.
- Publishers read only current `provisional` and `confirmed` projections.
  `community_unverified` observations are not public.
- Snapshot content contains a pseudonymous hazard ID, aggregate location,
  severity, confidence, consensus state, match state, freshness, and policy
  version. It contains no installation identity or raw observation payload.
- Canonical public content is hashed with SHA-256. The digest is the immutable
  version and ETag. Identical regenerated content is not stored again.
- A small head table points to the current immutable version. Historical
  versions remain addressable for CDN and client-cache consistency.
- The current endpoint returns public cache headers and supports
  `If-None-Match`. An explicit version receives a one-year immutable cache
  policy.
- A region without a published snapshot returns an empty response with
  `coverage.status=unknown` and a short cache lifetime. No backend state claims
  community coverage where none has been measured.
- Redis and object storage are distribution optimizations, not correctness
  dependencies. The bounded database snapshot read remains the fallback.

## Consequences

- Repeated driver reads never query raw observations or contributor identities.
- CDN fan-out can serve large read concurrency without proportional PostgreSQL
  load.
- Changing region precision requires coordinated configuration and reseeding.
- Geohash boundaries are a cache partition, not road matching. The hazard's
  `match_state` remains authoritative.
- Snapshots report community hazards, not complete road safety or map coverage.
