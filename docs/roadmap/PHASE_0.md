# Phase 0: Repository and first vertical slice

## Completed by the initial repository

- canonical monorepo boundaries;
- master plan and initial ADRs;
- versioned observation and BLE frame schemas;
- backend liveness/readiness endpoints;
- ingestion guard that refuses unsafe non-durable acknowledgements;
- Android, firmware, hardware, infrastructure and simulator scaffolds;
- CI for repository structure, contracts and backend tests.

## Next implementation slice

1. Add the Android Gradle wrapper and a signed-debug owner build.
2. Implement automatic/manual drive-session state with a visible foreground
   notification.
3. Capture calibrated phone IMU samples into a bounded local pipeline.
4. Persist a minimized road observation in Room before upload.
5. Submit the observation to a durable PostgreSQL/outbox implementation.
6. Prove reboot/offline/retry produces one business effect.

## Explicitly not in Phase 0

- LoRa mesh;
- crash/SOS;
- cloud AI;
- ECU writes;
- trip history;
- municipal dashboard;
- large-scale production infrastructure.
