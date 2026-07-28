# Production

Production infrastructure is created only through reviewed IaC after provider,
region, retention, backup, cost and incident-ownership decisions are recorded.

The provider-neutral contract is `infra/deploy/compose.yaml`; the required
values and scaling knobs are in `.env.example`. Do not deploy the example
directly. The completed environment must pass:

```powershell
python infra/scripts/validate_deployment.py C:\secure\michi-production.env `
  --check-secret-files
```

Production requirements:

- immutable `image@sha256` reference promoted from the green staging artifact;
- distinct migration/API/projection/snapshot database logins;
- managed PostgreSQL 17/PostGIS 3.5 with encrypted backups and PITR;
- TLS-only public ingress, WAF/rate controls and trusted-proxy allowlist;
- CDN for public regional reads and object-storage binding for immutable
  distribution artifacts;
- secret manager materialized as read-only files, never source or image layers;
- budget alerts at 50, 80 and 100 percent of the approved monthly ceiling;
- release, rollback, backup restore and incident runbooks with named owners.

Follow `docs/operations/RELEASE.md`. Cloud creation and DNS/traffic changes
require separate owner authorization.
