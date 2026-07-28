# ADR 0001: Canonical monorepo and component boundaries

- Status: Accepted
- Date: 2026-07-28

## Context

RoadSense was split across several repositories and duplicate mobile clients.
That made contracts, security fixes and release state difficult to coordinate.

## Decision

Use `priencelucifer/MichiSonae` as the canonical public monorepo.

Top-level product components are:

- `apps/android`
- `services/api`
- `firmware/roadsense`
- `hardware`
- `contracts`
- `infra`
- `simulator`
- `docs`

The Android application is native Kotlin/Jetpack Compose. The first backend is
FastAPI. The RoadSense accessory uses an ESP32-oriented PlatformIO layout.

Older repositories remain migration sources until unique assets and history are
inventoried, then become read-only archives.

## Consequences

- Contract changes can be reviewed with all consumers.
- One CI workflow can enforce repository-wide safety boundaries.
- Component builds stay independent; the repository is not a single deployable.
- Code owners and path-based CI may be introduced as the contributor group
  grows.
- Flutter and LoRa implementation are not copied into active v1 code.
