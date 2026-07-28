import json
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from uuid import UUID, uuid4

from michisonae_api.models import ObservationBatch
from michisonae_api.security import (
    AuthenticatedInstallation,
    AuthenticationRejected,
    IssuedCredentials,
    RateLimitDecision,
)
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


class MemorySecurityService:
    def __init__(
        self,
        *,
        installation_id: str = "anonymous-install-0001",
    ) -> None:
        self.installation_id = installation_id
        self.access_token = "test-access-token-value"
        self.refresh_token = "test-refresh-token-value-with-enough-characters"
        self.opened = False
        self.closed = False
        self.revoked = False
        self.rate_counts: dict[tuple[str, str], int] = {}

    async def open(self) -> None:
        self.opened = True

    async def close(self) -> None:
        self.closed = True

    async def register(
        self,
        *,
        attestation: str | None,
        correlation_id: UUID,
        client_ip: str,
    ) -> IssuedCredentials:
        del attestation, correlation_id, client_ip
        self.installation_id = f"ins_{uuid4().hex}"
        self.access_token = f"test-access-{uuid4().hex}"
        self.refresh_token = f"test-refresh-{uuid4().hex}"
        self.revoked = False
        return self._credentials()

    async def refresh(
        self,
        *,
        refresh_token: str,
        correlation_id: UUID,
        client_ip: str,
    ) -> IssuedCredentials:
        del correlation_id, client_ip
        if self.revoked or refresh_token != self.refresh_token:
            raise AuthenticationRejected(
                "invalid_refresh_token",
                "The refresh credential is invalid or expired.",
            )
        self.access_token = f"test-access-{uuid4().hex}"
        self.refresh_token = f"test-refresh-{uuid4().hex}"
        return self._credentials()

    async def authenticate(self, access_token: str) -> AuthenticatedInstallation:
        if self.revoked or access_token != self.access_token:
            raise AuthenticationRejected(
                "invalid_access_token",
                "The access credential is invalid or expired.",
            )
        return AuthenticatedInstallation(
            installation_id=self.installation_id,
            family_id=uuid4(),
            access_expires_at=datetime.now(UTC) + timedelta(hours=1),
        )

    async def revoke(
        self,
        principal: AuthenticatedInstallation,
        *,
        correlation_id: UUID,
        client_ip: str,
    ) -> None:
        del principal, correlation_id, client_ip
        self.revoked = True

    async def check_rate_limit(
        self,
        *,
        scope: str,
        subject: str,
        limit: int,
        window_seconds: int,
    ) -> RateLimitDecision:
        del window_seconds
        key = (scope, subject)
        count = self.rate_counts.get(key, 0) + 1
        self.rate_counts[key] = count
        return RateLimitDecision(
            allowed=count <= limit,
            limit=limit,
            remaining=max(0, limit - count),
            retry_after_seconds=60,
        )

    def _credentials(self) -> IssuedCredentials:
        now = datetime.now(UTC)
        return IssuedCredentials(
            installation_id=self.installation_id,
            access_token=self.access_token,
            access_expires_at=now + timedelta(hours=1),
            refresh_token=self.refresh_token,
            refresh_expires_at=now + timedelta(days=30),
        )
