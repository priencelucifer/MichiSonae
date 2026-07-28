# MichiSonae Android

Native Kotlin/Jetpack Compose application.

## Planned modules

- `app`: composition and release configuration
- `core-model`: immutable domain/contracts
- `core-database`: Room queues and cached snapshots
- `core-location`: location quality and policy
- `core-sensors`: phone IMU/calibration
- `core-hardware`: RoadSense BLE
- `core-obd`: read-only adapter state machine
- `core-diagnostic-policy`: deterministic DTC/value policy
- `core-local-ai`: optional constrained local explanation
- `core-local-speech`: local wake word and command speech
- `core-local-voice`: offline TTS and audio focus
- `feature-drive`: session, detection and warnings
- `feature-vehicle`: OBD, fuel and service choices

The first commit intentionally contains one `app` module. Split modules only as
the first vertical slice is implemented; do not create empty Gradle modules.

## Build

Requirements:

- JDK 17
- Android SDK platform 37
- Gradle 9.5

From this directory:

```text
gradle testDebugUnitTest lintDebug assembleDebug
```

A checked-in Gradle wrapper is the first Android setup issue. CI provisions the
same Gradle version until the wrapper is committed.
