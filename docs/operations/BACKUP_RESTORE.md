# PostgreSQL backup and recovery runbook

## Objectives

- Data-loss objective (RPO): at most 24 hours for the invited alpha; reduce to
  15 minutes before a broad public launch by enabling managed PostgreSQL PITR.
- Service recovery objective (RTO): four hours for the invited alpha; the
  automated logical-restore drill itself must finish within five minutes in CI.
- Restore into isolation first. Never test a backup by restoring over the
  active database.

## Scheduled controls

1. Enable encrypted daily managed backups with at least 14 retained restore
   points for alpha.
2. Enable point-in-time recovery and cross-zone storage before public beta.
3. Record the provider backup job ID, start/end timestamps, size, checksum when
   available, and alert on a missed backup.
4. Run the isolated restore drill in CI for every backend change.
5. Run a provider-native staging restore monthly and after material database or
   provider changes.

## CI logical restore drill

The service image and dependencies must be installed and the source database
must be migrated. From `services/api`, run:

```powershell
$container = docker ps --filter ancestor=postgis/postgis:17-3.5 --format "{{.ID}}" |
    Select-Object -First 1
python ../../infra/scripts/verify_postgres_restore.py `
    --database-url $env:MICHI_TEST_DATABASE_URL `
    --container $container `
    --maximum-seconds 300
```

The drill creates a uniquely named database, restores a custom-format dump,
checks the latest migration checksum, compares critical table counts, compares
the lifecycle consistency result, prints a JSON result, and drops the isolated
database in a `finally` cleanup.

## Provider recovery procedure

1. Declare an incident and freeze migrations and destructive maintenance.
2. Identify the recovery timestamp and document the expected data-loss window.
3. Restore the selected backup/PITR point into a new isolated database.
4. Run the migration readiness check without applying new migrations.
5. Run `michi-maintain check`; investigate every non-zero invariant.
6. Compare accepted-observation, outbox, projection, and snapshot counts with
   the last known operational metrics.
7. Start one projector and one snapshot publisher against the isolated
   database. If derived state is suspect, run the guarded full rebuild.
8. Validate anonymous registration, credential refresh, one idempotent
   observation retry, and one regional snapshot read.
9. Change application database routing only through the reviewed deployment
   mechanism. Keep the former database read-only until the incident is closed.
10. Record actual RPO/RTO, missing data, validation evidence, and follow-up work.

## Projection-only recovery

When accepted observations and outbox rows are healthy but derived state is
not, use:

```powershell
michi-maintain check
michi-maintain rebuild --confirm REBUILD-DERIVED-STATE
```

For one region, add `--region gh5:<cell>`. In production the confirmation is
mandatory. A successful command replays retained observations, restores
retained contributor rollups, regenerates snapshots, and finishes with a clean
consistency result.
