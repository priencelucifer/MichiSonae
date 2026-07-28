import json
from datetime import datetime
from hashlib import sha256
from uuid import UUID

from michisonae_api.models import ObservationBatch
from michisonae_api.snapshots import SnapshotRecord, SnapshotUnavailable
from michisonae_api.store import (
    EventIdConflict,
    IngestionResult,
    StoreUnavailable,
)


class MemoryObservationStore:
    def __init__(
        self,
        *,
        ready: bool = True,
        fail_ingestion: bool = False,
    ) -> None:
        self.is_ready = ready
        self.fail_ingestion = fail_ingestion
        self.opened = False
        self.closed = False
        self.payload_hashes: dict[UUID, str] = {}

    async def open(self) -> None:
        self.opened = True

    async def close(self) -> None:
        self.closed = True

    async def ready(self) -> bool:
        return self.is_ready

    async def ingest(self, batch: ObservationBatch) -> IngestionResult:
        if self.fail_ingestion:
            raise StoreUnavailable("forced failure")

        incoming = {
            observation.event_id: _payload_hash(observation.model_dump(mode="json"))
            for observation in batch.observations
        }
        conflicts = tuple(
            event_id
            for event_id, payload_hash in incoming.items()
            if event_id in self.payload_hashes and self.payload_hashes[event_id] != payload_hash
        )
        if conflicts:
            raise EventIdConflict(conflicts)

        stored_count = 0
        for event_id, payload_hash in incoming.items():
            if event_id not in self.payload_hashes:
                self.payload_hashes[event_id] = payload_hash
                stored_count += 1

        return IngestionResult(
            received_count=len(incoming),
            stored_count=stored_count,
        )


def _payload_hash(payload: dict[str, object]) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return sha256(encoded).hexdigest()


class MemorySnapshotStore:
    def __init__(
        self,
        records: tuple[SnapshotRecord, ...] = (),
        *,
        fail_reads: bool = False,
    ) -> None:
        self.records = {(record.region_id, record.version): record for record in records}
        self.heads = {record.region_id: record for record in records}
        self.fail_reads = fail_reads
        self.opened = False
        self.closed = False

    async def open(self) -> None:
        self.opened = True

    async def close(self) -> None:
        self.closed = True

    async def get(
        self,
        region_id: str,
        version: str | None = None,
    ) -> SnapshotRecord | None:
        if self.fail_reads:
            raise SnapshotUnavailable("forced snapshot read failure")
        if version is None:
            return self.heads.get(region_id)
        return self.records.get((region_id, version))


def snapshot_record(
    *,
    region_id: str,
    version: str,
    generated_at: datetime,
) -> SnapshotRecord:
    return SnapshotRecord(
        region_id=region_id,
        version=version,
        generated_at=generated_at,
        source_updated_at=generated_at,
        hazard_count=1,
        payload={
            "schema_version": "1.0",
            "region_id": region_id,
            "coverage": {
                "status": "unknown",
                "basis": "community_observations_only",
            },
            "hazards": [
                {
                    "hazard_id": "a" * 24,
                    "kind": "road_damage",
                    "latitude": 26.1445,
                    "longitude": 91.7362,
                    "severity": 0.7,
                    "confidence": 0.8,
                    "contributor_count": 3,
                    "lifecycle_state": "confirmed",
                    "match_state": "unmatched",
                    "road_segment_id": None,
                    "first_detected_at": generated_at.isoformat(),
                    "last_detected_at": generated_at.isoformat(),
                    "policy_version": "projection-v1",
                }
            ],
        },
    )
