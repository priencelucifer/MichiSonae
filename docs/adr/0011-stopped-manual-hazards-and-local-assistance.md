# ADR 0011: Stopped manual hazards and local assistance boundaries

- Status: Accepted
- Date: 2026-07-31

## Context

The alpha must accept general road hazards without mislabelling them as
potholes. It also needs useful destination, fuel, service and diagnostic flows
before a live places provider, local language model or physical OBD fixture is
available.

## Decision

- The observation contract additively supports `obstruction`, `flooding`,
  `manhole_hazard`, `road_construction` and `disabled_vehicle` alongside road
  damage and rough road.
- Manual reports use the same durable offline queue and idempotent backend
  ingestion as automatic observations. The UI acknowledges a report only after
  local atomic storage succeeds.
- Manual reporting requires a fresh, accurate location that reports the
  vehicle stopped. It stores structured category, severity and event location
  only; it stores no image, route or trip history.
- `manual-v1` identifies the versioned manual taxonomy. Manual confidence is
  conservatively bounded and still requires community consensus before a
  public hazard becomes verified.
- Diagnostic cards contain derived codes, deterministic severity and safe
  actions only. They stay local, expire after 30 inactive days and can be
  deleted immediately. ECU clearing and other write commands remain absent.
- Destination and nearby-place types are provider-neutral. Until a reviewed
  live provider is connected, MichiSonae hands search/navigation to the
  installed map app and labels static place data as simulation.
- Fuel advice suppresses confident claims when fuel, station or opening-hour
  data is stale or unavailable.

## Consequences

- Migration `0007` expands database kind constraints without changing applied
  migration checksums.
- Android and public snapshot readers must recognize every contract kind.
- Physical ELM327 compatibility, station accuracy, route-ahead distance and
  road-detector calibration remain field-validation gates, not software claims.
- Local AI and wake-word model downloads remain optional later work; all
  severity and safe actions work without them.
