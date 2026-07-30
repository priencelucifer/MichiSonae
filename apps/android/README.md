# MichiSonae Android

Native Kotlin/Jetpack Compose application.

## Implemented

- account-free privacy onboarding
- local-only installation identity and vehicle profile
- vehicle-size road sensitivity policy
- deterministic automatic-driving and phone road-detection demo
- atomic offline road-observation queue
- HTTPS anonymous registration, single-flight refresh and durable upload client
- strict read-only ELM327 command parser and simulator
- deterministic diagnostic safe-action policy
- manual/OBD fuel range and route fuel-coverage simulation
- screen-off foreground phone monitoring using GPS speed and linear acceleration
- local English TTS, alert tone, vibration and transient music pause/resume
- external Maps handoff for fuel pumps and selectable service-center searches
- local Gemma prompt/result boundary with deterministic policy ownership
- local wake-word command routing boundary with no microphone upload
- two-step deletion of profile, identity, consent and pending observations

The app does not store trip history. The installation identity and vehicle
profile remain on the phone until the backend authentication slice explicitly
uses the identity.

The phone detection screen currently uses simulated motion so the full
classification path can be tested without permissions or hardware. Real
location and accelerometer sampling will reuse the same deterministic policy.

Queued observations contain only the event contract fields, never raw sensor
samples or trip sequences. An item is removed only after the API returns a
balanced `202` durable-acceptance response. The API client is intentionally not
connected to a production endpoint until deployment supplies one.

The OBD demo cannot send arbitrary commands: its enum contains only adapter
setup, live Mode 01 reads, and Mode 03 trouble-code reads. It contains no Mode
04 clearing, coding, actuation, or manufacturer write path. Fuel calculations
use a 20% uncertainty buffer and are always labelled as estimates.

Android location permission is requested once before monitoring starts. A
low-priority foreground notification keeps detection alive with the screen off.
The service stores coordinates only for a detected hazard event; it does not
record ordinary locations or construct trip history. Android may require the
app to be opened once again after a phone restart before monitoring resumes.

The current service-center cards and local explanation are honest simulations.
Maps supplies live search, navigation, and opening-hour details without copying
Google Maps into MichiSonae. The Gemma and wake-word boundaries do not load a
model or capture audio yet; all prompt/result handling is designed to stay on
the phone. Emergency contacts remain deferred with crash/SOS and LoRa until
their required future ADR is approved.

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
