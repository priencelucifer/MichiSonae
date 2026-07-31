# ADR 0010: Android resilience and read-only OBD boundary

## Status

Accepted

## Context

The owner alpha needs phone-only road detection, optional inexpensive Bluetooth
ELM327 support, offline reporting and useful status information without
requiring deployment or hardware. Android also restricts silently restarting a
location foreground service after reboot.

## Decision

- A road detection is acknowledged only after its minimized observation is
  durably written. Queue corruption recovery salvages valid records, input is
  bounded, and an oversized legacy queue can drain without accepting more.
- Upload jobs persist across reboot. The boot receiver reschedules uploads but
  only posts an honest reopen-app notification for monitoring; it does not
  bypass Android foreground-service restrictions.
- The same user-selected battery and metered-network policy governs upload jobs
  and regional hazard refreshes.
- The hazard cache stores the current geohash and bounded adjacent cells. Queue
  and cache formats are versioned and unknown future schemas are not
  downgraded.
- Bluetooth is optional. The app requests `BLUETOOTH_CONNECT` only for OBD,
  lists already-paired devices, and executes the typed ELM327 reconnect state
  machine on one worker.
- The ELM327 boundary accepts only the reviewed enum of read-only
  initialization, capability, Mode 01 live-value and Mode 03 trouble-code
  commands. There is no arbitrary command API.
- Diagnostic severity and safe action remain deterministic. Local generative AI
  may explain a reviewed finding later but cannot change it.
- Runtime status uses in-process listeners and sanitized categories; raw OBD
  responses, ordinary locations and exception details are not recorded or
  uploaded.
- Backend responses and bearer credentials are bounded before use; control
  characters are rejected before any `Authorization` header is created.
- Data deletion persists a pending marker before any clear, gates service
  restart and sync scheduling, stops monitoring, durably removes the saved
  endpoint, and disables queue/cache acceptance. The marker is cleared only
  after every local store succeeds; startup retries an interrupted deletion.

## Consequences

Phone-only behavior remains available without Bluetooth, internet or hardware.
Some recovery requires an explicit app open after reboot. A physical adapter
matrix and on-road calibration remain owner-alpha activities, not claims made by
the software-only test suite.
