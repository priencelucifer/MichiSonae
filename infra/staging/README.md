# Staging

Staging must use the same migrations, queue semantics and release artifacts as
production. Secrets and state are never copied from production.

## Bring-up contract

1. Copy `.env.example` to a Git-ignored location and replace every placeholder.
2. Create five secret files: migration, API, projection and snapshot PostgreSQL
   URLs plus a random rate-limit hash secret of at least 32 characters.
3. Provision PostgreSQL 17/PostGIS 3.5 with encrypted storage, daily backups,
   PITR, TLS, and the connection budget stated in the environment.
4. Run migrations with the migration owner, then apply
   `infra/database/least-privilege-roles.sql` and grant each runtime login only
   its matching group role.
5. Validate before starting anything:

```powershell
python infra/scripts/validate_deployment.py C:\secure\michi-staging.env `
  --check-secret-files
docker compose --env-file C:\secure\michi-staging.env `
  -f infra/deploy/compose.yaml config
```

Run the controlled migration exactly once:

```powershell
docker compose --env-file C:\secure\michi-staging.env `
  -f infra/deploy/compose.yaml --profile release run --rm migrate
```

Then start the runtime roles without the `release` profile:

```powershell
docker compose --env-file C:\secure\michi-staging.env `
  -f infra/deploy/compose.yaml up -d api projection snapshot
```

The provider ingress terminates TLS, attaches the reviewed WAF policy, forwards
only from the documented proxy CIDRs, and routes regional snapshot reads
through the CDN. The object-storage binding may mirror immutable snapshots but
is not the source of truth.
