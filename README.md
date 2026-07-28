# MichiSonae

MichiSonae is an Android-first road-hazard and vehicle-awareness system.

The phone works independently: it detects potholes and rough roads, warns the
driver in the background, and connects to a read-only Bluetooth OBD-II adapter
for simple vehicle-condition and conservative fuel-range guidance. An optional
RoadSense sensor may improve road-event confidence. Crash detection and LoRa
emergency mesh are explicitly deferred future work.

## Repository map

| Path | Responsibility | Current state |
|---|---|---|
| `apps/android` | Native Kotlin/Jetpack Compose driver application | Architecture scaffold |
| `services/api` | FastAPI ingestion and hazard projection services | Durable, secured, rebuildable backend |
| `firmware/roadsense` | ESP32 RoadSense accessory firmware | Safe no-network scaffold |
| `hardware` | Electrical, mechanical, BOM, manufacturing and validation records | Documentation scaffold |
| `contracts` | Versioned API, event and device protocol contracts | Initial observation and frame schemas |
| `infra` | Local, staging and production infrastructure definitions | Boundary scaffold |
| `simulator` | Synthetic road/OBD/device test data | Boundary scaffold |
| `docs` | Architecture, decisions, roadmap and master plan | Active |

## Product boundaries

- Android only; Kotlin/Jetpack Compose.
- Account-free core experience.
- Phone-only road detection is mandatory.
- OBD-II is read-only and capability-driven.
- Deterministic policy owns warnings, severity, fuel reachability and safety
  actions. Local AI may explain verified facts but cannot change them.
- No trip history.
- No cloud upload of raw microphone, IMU tuning traces, OBD traces, diagnostic
  prompts or model responses.
- LoRa mesh and crash/SOS are future work, not launch dependencies.

## Server foundation

The backend foundation exposes:

- `GET /health/live`
- `GET /health/ready`
- `POST /v1/observations:batch` with atomic PostgreSQL/PostGIS storage,
  `event_id` idempotency and a transactional outbox
- a leased, retry-safe projection worker with distinct-installation consensus,
  dead letters and deterministic rebuilds
- content-addressed global regional snapshots and an ETag-enabled public read
  endpoint designed for CDN fan-out
- account-free anonymous installation credentials, replay-safe refresh
  rotation, authenticated contribution, trusted-proxy handling, and atomic
  abuse limits
- guarded raw-observation retention, audited dead-letter operations,
  deterministic full/regional rebuilds, consistency checks, and a real
  isolated-database restore drill

The endpoint returns `202` only after the database transaction commits.

## Development

See each component README for its toolchain. The immediate order is:

1. contracts and repository rules;
2. phone-only Android drive-session vertical slice;
3. read-only ELM327 adapter fingerprinting;
4. durable observation ingestion with PostgreSQL/outbox;
5. optional RoadSense BLE sensor.

The detailed implementation baseline is in [docs/MASTER_PLAN.md](docs/MASTER_PLAN.md).
