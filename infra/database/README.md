# PostgreSQL role separation

Run migrations as the schema owner first, then apply
`least-privilege-roles.sql` in the target database. It creates five `NOLOGIN`
group roles:

- `michi_api` for anonymous auth, abuse limits, durable ingestion, and public
  snapshot reads;
- `michi_projection` for outbox leasing and derived hazard state;
- `michi_snapshot` for regional snapshot work and publication;
- `michi_maintenance` for guarded retention, dead-letter and rebuild commands;
- `michi_backup` for read-only logical backup access.

Create distinct provider-managed login principals and grant each only its
matching group role. Do not give runtime principals schema ownership,
`SUPERUSER`, `CREATEDB`, `CREATEROLE`, or the migration credential. Store their
TLS PostgreSQL URLs in the matching secret files named by the environment
template.

Every migration that introduces a table or sequence must update and re-test
this grant file before runtime rollout. The migration owner applies both in the
controlled release job; API and worker replicas never apply grants.
