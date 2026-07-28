# ADR 0007: Guarded data lifecycle and rebuilds

- Status: Accepted
- Date: 2026-07-28

## Context

Accepted road observations contain precise location and must not be retained
without a limit. Deleting source events carelessly can strand outbox rows,
remove the latest evidence behind a projection, or make a later rebuild produce
different public hazards. Dead-letter deletion and manual SQL also need an
operator trail.

Projection and snapshot tables are derived state. Operators need a repeatable
way to rebuild all regions or one damaged region without losing accepted
source events or serving snapshots built from mixed generations.

## Decision

- Raw accepted observations have a configurable 30–90 day retention window;
  the initial default is 90 days.
- Retention is dry-run by default, reports the exact eligible count and time
  range, deletes in bounded resumable batches, and requires an exact
  confirmation phrase in production.
- An event is eligible only after its outbox work completed and it is no longer
  the latest event referenced by a contributor. Pending, failed, quarantined,
  and latest contributor events are retained.
- Before an older repeated observation is deleted, its count and earliest
  detection time are folded into a compact contributor rollup. The rollup adds
  no location beyond the current aggregate cluster and lets a rebuild reproduce
  public first-detection time and projection revision.
- Dead-letter work supports status, explicit retry, quarantine with a bounded
  reason code, and delayed purge. Repeated retry, quarantine, and purge
  commands are safe no-ops.
- Every retention, retry, quarantine, and purge command writes a minimized
  operations audit record with a command ID and counts. It never stores a raw
  request, credential, or diagnostic trace.
- Full rebuilds invalidate all public snapshot heads and versions, reset only
  derived projection state and delivery state, replay retained observations,
  apply retained rollups, and regenerate content-addressed snapshots.
- Regional rebuilds perform the same sequence for one validated geohash region.
- A consistency command checks source/outbox pairing, processing completion,
  projection contributor counts, token limits, and retained rollup attachment.
- CI performs a real custom-format PostgreSQL dump and restores it into a new
  isolated database, verifies migration checksums, compares critical table
  counts and consistency results, enforces a drill RTO, then removes the drill
  database.

## Consequences

- Retention cannot erase an event that still defines current contributor state.
- Interrupted batch deletion can resume without double-counting rollups.
- A full rebuild after retention reproduces the same public snapshot content.
- Raw deletion does not mean all aggregate hazard data is deleted; aggregate
  retention and installation revocation remain separate policy concerns.
- Production operators must schedule the dry run, review it, then issue the
  guarded apply command. Direct ad-hoc deletion is outside the supported path.
- The CI restore drill proves logical dump recovery for this schema. A managed
  provider's point-in-time recovery and regional failure behavior still need
  provider-specific staging drills.
