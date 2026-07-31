# Contributing

MichiSonae is safety-adjacent software. Start with an issue describing the user
outcome, failure modes, privacy impact and validation evidence.

## Workflow

1. Branch from `main`.
2. Use a short-lived branch named by outcome, such as `feature/offline-queue`
   or `fix/obd-timeout`.
3. Keep changes inside one component unless a contract change requires a
   coordinated update.
4. Add or update tests.
5. Run the relevant component checks.
6. Open a pull request explaining behavior, safety/privacy effects, rollout and
   rollback.
7. Squash-merge after required CI passes. GitHub deletes the merged branch
   automatically.

Do not commit build output, IDE state, local SDK paths, credentials, model
weights, APKs, firmware binaries or dependency caches.

## Component checks

- Android: `cd apps/android && ./gradlew testDebugUnitTest lintDebug assembleDebug`
- Backend: `cd services/api && ruff check . && mypy && pytest`
- Firmware: `cd firmware/roadsense && pio run`

The repository CI remains the final integration gate.

Safety-relevant changes to warning timing, diagnostic severity, fuel
reachability or future emergency behavior require an ADR and explicit review.
