# Contracts

Contracts are versioned independently from implementations.

- `events/road-observation.v1.schema.json`: minimized road observation accepted
  from a phone or fused phone/device detector.
- `ble/roadsense-frame.v2.schema.json`: logical RoadSense BLE frame envelope.
- `openapi/michisonae-api.v1.yaml`: initial public HTTP contract.

Observation ingestion returns `202` only after every new observation and its
transactional outbox record are committed. Retrying identical `event_id`
values is a successful no-op; reusing an ID for different content rejects the
entire batch with `409`.

Generated clients and golden vectors will be added after the first contract
review. Breaking changes create a new major schema rather than silently
changing an existing file.
