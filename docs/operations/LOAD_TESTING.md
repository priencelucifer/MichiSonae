# Backend load and cost gate

The committed k6 workload models the invited ten-person alpha without creating
cloud resources. It runs four paths against a disposable PostgreSQL/PostGIS
database:

- normal authenticated observation ingestion;
- cacheable regional snapshot reads;
- a commute spike ramping from 2 to 10 accepted requests per second;
- immediate retries of the same event ID to prove duplicate safety under load.

## Pass/fail contract

The CI run fails unless:

- at least 99 percent of checks pass;
- HTTP failure rate is below 1 percent;
- ingest p95 is below 500 ms and p99 below 1 second;
- public read p95 is below 250 ms and p99 below 500 ms;
- the arrival-rate executor drops no iterations.

These are alpha gates, not a claim that the backend supports every future
worldwide user. Before each scale step, run a longer staging soak using the
same immutable image, real provider network path, CDN, managed database, and
the target regional traffic mix.

## Run

Start the full local stack from the repository root, then:

```powershell
New-Item -ItemType Directory -Force load-results | Out-Null
docker run --rm --network host `
  -e BASE_URL=http://127.0.0.1:8000 `
  -v "${PWD}/infra/load:/scripts:ro" `
  -v "${PWD}/load-results:/results" `
  grafana/k6:2.0.0 run /scripts/backend.js
```

The JSON result is written to `load-results/backend-load-summary.json`. CI
uploads it for every backend change.

## Cost inputs

The invited-alpha defaults are in `infra/sizing/capacity.yaml`: one small API,
one projector, one snapshot worker, a small PostGIS database, a 20 GB data
volume, daily backups, and a hard monthly budget target of USD 10 with alerts
at 50, 80, and 100 percent. Provider quotes, taxes, bandwidth, object storage,
CDN egress, logs, backup storage, and currency conversion must be filled in
before deployment. If the selected managed database cannot fit the budget,
use a reviewed free tier or one small owner-operated host for the invited
alpha; do not silently disable backups or security controls.
