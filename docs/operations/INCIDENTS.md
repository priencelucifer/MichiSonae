# Backend incident runbooks

## Database or schema failure

Trigger: readiness is false, sustained pool waiters, or elevated 5xx responses.

1. Freeze deployments, migrations, rebuilds, retention, and dead-letter purge.
2. Compare liveness with readiness for the API and both workers.
3. Verify database provider health, connection limits, storage, locks, and the
   latest migration checksum.
4. Scale down excess replicas before raising PostgreSQL connection limits.
5. If a migration is incomplete, follow expand/migrate/contract rollback; do
   not edit `schema_migrations`.
6. If data recovery is required, follow `BACKUP_RESTORE.md`.
7. Validate register, idempotent upload, projection, and read in staging before
   reopening traffic.

## Queue lag or dead letters

Trigger: oldest pending age exceeds five minutes or dead-letter count is nonzero.

1. Check worker readiness, restart count, pool waiters, and sanitized failure
   codes.
2. Stop a crash loop before increasing replicas.
3. Inspect bounded status with `michi-project --status`,
   `michi-snapshot --status`, and `michi-maintain dead-letter status`.
4. Correct the dependency/schema/data-policy cause.
5. Retry one item and verify one business effect before a larger retry.
6. Quarantine a confirmed poison item; purge only after the quarantine window
   and reviewed production confirmation.
7. Use a regional rebuild for isolated derived corruption and a full rebuild
   only with an incident record.

## Bad projection or snapshot

Trigger: consistency failure, impossible aggregate, or wrong public snapshot.

1. Preserve the current snapshot version and correlation IDs; do not export raw
   observations into chat or tickets.
2. Stop publishers if invalid content is still being generated.
3. Run `michi-maintain check`.
4. Compare deterministic policy version and migration checksum with the last
   known good release.
5. Roll back application artifacts if code changed, then rebuild the affected
   region.
6. Confirm content hash stability and public payload minimization before
   restoring publishers.

## Suspected credential leak

Trigger: secret scanning, refresh-token reuse spike, leaked deployment secret,
or unexpected privileged database access.

1. Treat the credential as compromised; do not paste it into logs or issues.
2. Revoke/rotate the provider secret and invalidate affected anonymous token
   families.
3. Preserve security audit correlation IDs and HMAC-pseudonymized subjects.
4. Review trusted proxy configuration and deployment access logs.
5. Verify the old credential no longer works, deploy the replacement through
   the secret manager, and document blast radius and notifications.

## False road-alert spike

Trigger: validated tester feedback or aggregate false-alert monitoring exceeds
the release threshold.

1. Stop rollout of the detector/policy version; do not delete evidence.
2. Identify detector version, phone/device source mix, vehicle profile group,
   and broad region using aggregated data only.
3. Lower warning eligibility or withdraw affected snapshots through a reviewed
   deterministic policy change; never ask an on-device model to decide.
4. Re-run labeled replay and shadow evaluation, then regenerate the affected
   region.
5. Resume gradually only after precision, false alerts per 100 km, and warning
   timing return inside the documented gate.
