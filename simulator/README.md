# Simulator

The simulator produces deterministic, synthetic inputs for contracts, backend
load tests and Android/firmware replay. Synthetic data is visibly labeled and
must never be mixed into real road consensus.

Generate JSON Lines observations:

```text
python generate_road_observations.py --count 10 --seed 7
```

The Android JVM suite also provides dependency-free deterministic simulators
for noisy and malformed phone samples, ELM327/ISO-TP corruption and PID
spoofing, offline-queue truncation and seeded corruption, hazard-cache clock
rollback, bounded-input attacks, network failures and upload-response fuzzing.
These simulations use synthetic values only and do not upload raw traces.

Run them with the normal Android unit suite:

```text
cd apps/android
./gradlew testDebugUnitTest
```
