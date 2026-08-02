# Contracts

Contracts are versioned independently from implementations.

- `events/road-observation.v1.schema.json`: minimized road observation accepted
  from a phone or fused phone/device detector.
- `ble/roadsense-frame.v2.schema.json`: logical RoadSense BLE frame envelope.
- `openapi/michisonae-api.v1.yaml`: public ingestion and regional hazard-read
  contract.

Observation ingestion returns `202` only after every new observation and its
transactional outbox record are committed. Retrying identical `event_id`
values is a successful no-op; reusing an ID for different content rejects the
entire batch with `409`.

The observation taxonomy also accepts stopped-user reports for obstruction,
flooding, manhole hazards, road construction and disabled vehicles. These use
the same minimized payload and durable acceptance rule; no image or trip
history is part of the contract.

Generated clients and golden vectors will be added after the first contract
review. Breaking changes create a new major schema rather than silently
changing an existing file.

Regional hazard reads are anonymous and publicly cacheable. Current snapshots
support `ETag`/`If-None-Match`; content-addressed versions are immutable. An
empty response reports unknown community coverage and must not be interpreted
as a hazard-free road.

Contribution also requires no user account, but it is bound to a registered
anonymous installation. Registration and refresh responses are never cacheable.
Access tokens authenticate ingestion; refresh tokens rotate once and reuse
revokes their token family. Attestation is an optional risk signal, never proof
that an observation is true.
