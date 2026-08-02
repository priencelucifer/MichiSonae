# Phase 0: Repository and first vertical slice

## Completed

- canonical monorepo boundaries;
- master plan and initial ADRs;
- versioned observation and BLE frame schemas;
- backend liveness/readiness endpoints;
- PostgreSQL-backed durable and idempotent ingestion with a transactional
  outbox;
- Android, firmware, hardware, infrastructure and simulator scaffolds;
- native Android automatic/manual drive state and a visible foreground
  notification;
- bounded phone-sensor classification and durable minimized observations;
- restart-safe offline upload and regional hazard snapshot caches;
- strict read-only ELM327 discovery and deterministic fuel/diagnostic policy;
- shared Android/backend golden vectors plus retry, idempotency, corruption,
  network-failure, fuzz and security simulators;
- CI for structure, contracts, backend, load, supply chain, Android release
  reproducibility and firmware compilation.

## Remaining owner-alpha gates

1. Install the owner debug build on the supported phone and validate
   screen-off/OEM process behavior.
2. Collect consented labelled road runs and calibrate phone mounting,
   false-positive and false-negative thresholds.
3. Validate at least one inexpensive ELM327 clone and vehicle combination
   without enabling any write command.
4. Integrate and review a live fuel/service place provider, including offline,
   stale-hours and route-failure behavior.
5. Produce a signed internal release and complete privacy/Play declarations.
6. Repeat the offline/reboot/retry proof against the deployed alpha service.

## Explicitly not in Phase 0

- LoRa mesh;
- crash/SOS;
- cloud AI;
- ECU writes;
- trip history;
- municipal dashboard;
- large-scale production infrastructure.
