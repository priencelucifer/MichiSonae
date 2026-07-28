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
- Android SDK platform 36
- Android SDK build tools 36.0.0

The repository owns a Gradle 9.5.1 wrapper. Do not use a separately installed
Gradle version. From this directory, run:

```shell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The wrapper verifies the downloaded Gradle distribution with the SHA-256 value
published for Gradle 9.5.1. GitHub CI also validates the committed wrapper JAR.

## Owner debug APK

A successful Android CI job uploads `michisonae-debug-<commit>` as a build
artifact. Download and unzip it, then install the APK from Android Studio or
with Android Debug Bridge:

```powershell
adb install -r app-debug.apk
```

The APK is a development build signed with an automatically generated debug
key. It is suitable only for owner/alpha testing. If a later build was signed
by a different debug key, uninstall the existing app before installing it.

Local builds are written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```
