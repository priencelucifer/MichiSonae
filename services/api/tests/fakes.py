import json
from hashlib import sha256
from uuid import UUID

from michisonae_api.models import ObservationBatch
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
            if event_id in self.payload_hashes
            and self.payload_hashes[event_id] != payload_hash
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
