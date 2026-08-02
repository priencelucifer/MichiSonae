# MichiSonae Android

Native Kotlin/Jetpack Compose application.

## Implemented

- account-free privacy onboarding
- local-only installation identity and vehicle profile
- vehicle-size road sensitivity policy
- deterministic automatic-driving and phone road-detection demo
- atomic offline road-observation queue
- Android Keystore-encrypted anonymous credentials with registration, refresh and revocation
- native network-aware background upload with exponential retry
- persisted upload recovery after phone restart with battery and metered-network policy
- atomic regional hazard snapshots and offline direction-aware warnings
- background status, pending upload and snapshot freshness indicators
- strict read-only Bluetooth ELM327 client, connection state machine and simulator
- cheap-clone response normalization and supported-PID discovery
- deterministic diagnostic safe-action policy
- manual/OBD fuel range and open/closed/unknown route fuel-coverage scenarios
- screen-off foreground phone monitoring using GPS speed and linear acceleration
- local English TTS, alert tone, vibration and transient music pause/resume
- external Maps handoff for fuel pumps and selectable service-center searches
- local Gemma prompt/result boundary with deterministic policy ownership
- local wake-word command routing boundary with no microphone upload
- restart-safe deletion of profile, identity, consent, pending observations,
  hazard caches, diagnostic cards and sync state
- stopped-only structured reporting for every alpha road-hazard category
- local derived diagnostic cards with expiry and immediate deletion
- typed, shared and on-device favorite destinations with external Maps handoff
- stale-data safeguards for fuel readings, station data and opening hours
- shared Android/backend golden ingestion vectors and strict acknowledgement
  validation
- duplicate-ID prevention and one-record isolation for permanent `409`/`422`
  rejections so a bad record cannot starve later observations
- deterministic sensor, OBD, queue, cache, network-failure, corruption and
  fuzz simulations

The app does not store trip history. The local installation identity and vehicle
profile remain on the phone. Server-issued anonymous access and rotating refresh
credentials are encrypted with an AES-GCM key held by Android Keystore.

The phone detection screen currently uses simulated motion so the full
classification path can be tested without permissions or hardware. Real
location and accelerometer sampling will reuse the same deterministic policy.

Queued observations contain only the event contract fields, never raw sensor
samples or trip sequences. An item is removed only after the API returns a
balanced `202` durable-acceptance response. The API client is intentionally not
connected to a production endpoint until deployment supplies one.

When an endpoint is configured, Android selects a coarse global geohash region,
downloads the public aggregate snapshot with ETag caching, and warns from the
last valid atomic cache while offline. Ordinary location samples are not
uploaded. The foreground service schedules native background upload after a
new report is stored.

The OBD transport cannot send arbitrary commands: its typed allow-list contains
only adapter setup, live Mode 01 reads, and Mode 03 trouble-code reads. It
contains no Mode 04 clearing, coding, actuation, or manufacturer write path.
It normalizes bounded ELM327 clone responses, discovers supported PID bitmaps,
keeps multi-ECU/ISO-TP replies separate, enforces a five-second prompt timeout,
and skips values the car does not expose. Fuel calculations use a 20%
uncertainty buffer and are always labelled as estimates.

Android location permission is requested once before monitoring starts. A
low-priority foreground notification keeps detection alive with the screen off.
The service stores coordinates only for a detected hazard event; it does not
record ordinary locations or construct trip history. Pending uploads are
rescheduled after a phone restart. Android does not allow this location
foreground service to restart silently from the boot receiver, so MichiSonae
shows a notification, when notification permission is available, asking the
user to reopen monitoring when it was active before the restart.
Bluetooth remains optional, so phones without Bluetooth can still install and
run phone-only road detection. The vehicle screen requests Bluetooth only when
OBD-II is opened, lists already-paired adapters, and runs the reconnecting
read-only controller after deliberate selection. The simulator remains
available without physical hardware; compatibility claims still require
end-to-end evidence.

Delete-all cancels sync, blocks credential and snapshot recreation, attempts
anonymous backend revocation when an endpoint is configured, and always removes
local credentials, queued reports, cached hazards, consent and vehicle data.
The cache keeps the current coarse geohash plus up to eight adjacent regions.
It migrates the older single-region file, bounds every response and index, and
refuses unknown future schemas instead of silently downgrading them.

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

The repository owns a Gradle 9.6.1 wrapper. Do not use a separately installed
Gradle version. From this directory, run:

```shell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Deployment builds supply the HTTPS API without committing an endpoint:

```powershell
.\gradlew.bat -PMICHI_API_BASE_URL=https://api.example.com assembleRelease
```

With no property, the app builds safely with uploads and snapshot refresh
unconfigured; phone detection, local queueing and simulations still work.

The wrapper verifies the downloaded Gradle distribution with the SHA-256 value
published for Gradle 9.6.1. GitHub CI also validates the committed wrapper JAR,
rejects dynamic dependency versions, enforces a 25 MiB release APK budget, and
requires two clean unsigned release builds to be byte-for-byte identical.

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
