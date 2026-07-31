# MichiSonae

[![CI](https://github.com/priencelucifer/MichiSonae/actions/workflows/ci.yml/badge.svg)](https://github.com/priencelucifer/MichiSonae/actions/workflows/ci.yml)
[![Android](https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-3DDC84?logo=android&logoColor=white)](apps/android)
[![Backend](https://img.shields.io/badge/backend-FastAPI%20%2B%20PostGIS-009688)](services/api)
[![Status](https://img.shields.io/badge/status-owner%20alpha-orange)](docs/MASTER_PLAN.md)

MichiSonae is an Android-first road-hazard and vehicle-awareness platform. The
phone can detect road damage without external hardware, warn the driver in the
background, and optionally read supported values from a strictly read-only
Bluetooth OBD-II adapter.

> This project is under active owner-alpha development. It is not a replacement
> for attentive driving, professional vehicle diagnosis, or emergency services.

## Core capabilities

- Phone-only pothole and rough-road detection.
- Background English voice, sound and vibration warnings.
- Account-free, offline-first road observations and cached hazard warnings.
- Optional read-only ELM327 OBD-II values and deterministic fault explanations.
- Conservative fuel-range guidance and upcoming fuel-station warnings.
- A production-oriented FastAPI/PostgreSQL/PostGIS backend with idempotent
  ingestion, hazard projection, public regional snapshots and operational
  runbooks.

Crash/SOS automation and LoRa mesh communication are documented future phases;
they are not present in the current product.

## Monorepo layout

Each deployable component has one home and its own README. Shared data crosses
component boundaries only through `contracts`.

| Path | Owns |
|---|---|
| [`apps/android`](apps/android) | Native Kotlin/Jetpack Compose application |
| [`services/api`](services/api) | FastAPI ingestion, projection and snapshot services |
| [`contracts`](contracts) | Versioned API, event and device protocol contracts |
| [`infra`](infra) | Local stack, deployment contracts, observability and load tests |
| [`simulator`](simulator) | Deterministic test observations and device fixtures |
| [`firmware/roadsense`](firmware/roadsense) | Deferred optional ESP32 accessory scaffold |
| [`hardware`](hardware) | Deferred CAD, BOM and validation records |
| [`docs`](docs) | Architecture decisions, operations, roadmap and master plan |

This is intentionally a monorepo: Android, backend, contracts and future
hardware stay separated by directory while one CI pipeline checks integration.

## Safety and privacy boundaries

- Android remains native Kotlin/Jetpack Compose.
- Phone-only detection works without OBD-II or RoadSense hardware.
- OBD-II is read-only: no clearing, coding, actuation or ECU writes.
- Deterministic policy owns warning severity, safe actions and fuel reachability.
- No accounts or trip history.
- Raw microphone audio, raw OBD streams, local AI conversations and sensor
  tuning traces are not uploaded.
- A road observation is never acknowledged before durable storage succeeds.

See [AGENTS.md](AGENTS.md) for repository-enforced engineering constraints.

## Quick start

### Backend

```bash
cd services/api
python -m venv .venv
python -m pip install -e ".[dev]"
pytest
```

Local PostgreSQL/PostGIS and full integration instructions are in the
[backend README](services/api/README.md).

### Android

```bash
cd apps/android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows, use `gradlew.bat`. See the [Android README](apps/android/README.md)
for the required JDK/SDK versions and owner APK workflow.

## Documentation

- [Production master plan](docs/MASTER_PLAN.md)
- [Architecture overview](docs/architecture/README.md)
- [Architecture decision records](docs/README.md)
- [Deployment and operations](docs/operations)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

Changes are developed through focused pull requests; merged branches are
deleted automatically.
