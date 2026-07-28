from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)

InstallationId = Annotated[str, StringConstraints(min_length=16, max_length=128)]


class DetectionSource(StrEnum):
    PHONE = "phone"
    ROADSENSE_DEVICE = "roadsense_device"
    FUSED = "fused"


class RoadObservation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: UUID
    installation_id: InstallationId
    detected_at: datetime
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    location_accuracy_m: float = Field(gt=0, le=500)
    speed_mps: float = Field(ge=0, le=100)
    kind: Literal["road_damage", "rough_road"]
    severity: float = Field(ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    source: DetectionSource
    detector_version: Annotated[str, StringConstraints(min_length=1, max_length=64)]

    @field_validator("detected_at")
    @classmethod
    def detected_at_must_include_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("detected_at must include a timezone")
        return value


class ObservationBatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["1.0"]
    observations: list[RoadObservation] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def event_ids_must_be_unique_within_batch(self) -> Self:
        event_ids = [observation.event_id for observation in self.observations]
        if len(event_ids) != len(set(event_ids)):
            raise ValueError("event_id values must be unique within a batch")
        return self


class ObservationBatchAccepted(BaseModel):
    schema_version: Literal["1.0"] = "1.0"
    received_count: int = Field(ge=1, le=100)
    stored_count: int = Field(ge=0, le=100)
    duplicate_count: int = Field(ge=0, le=100)

    @model_validator(mode="after")
    def counts_must_balance(self) -> Self:
        if self.stored_count + self.duplicate_count != self.received_count:
            raise ValueError("stored_count and duplicate_count must equal received_count")
        return self


class ApiError(BaseModel):
    code: str
    message: str


class HazardCoverage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal["unknown"]
    basis: Literal["community_observations_only", "no_published_snapshot"]


class PublicHazard(BaseModel):
    model_config = ConfigDict(extra="forbid")

    hazard_id: Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{24}$")]
    kind: Literal["road_damage", "rough_road"]
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    severity: float = Field(ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    contributor_count: int = Field(ge=2)
    lifecycle_state: Literal["provisional", "confirmed"]
    match_state: Literal["unmatched", "map_matched", "operator_verified"]
    road_segment_id: str | None
    first_detected_at: datetime
    last_detected_at: datetime
    policy_version: str


class RegionalHazardSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["1.0"] = "1.0"
    region_id: str
    version: Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")] | None
    generated_at: datetime | None
    source_updated_at: datetime | None
    hazard_count: int = Field(ge=0)
    coverage: HazardCoverage
    hazards: list[PublicHazard]

    @model_validator(mode="after")
    def hazard_count_must_match_payload(self) -> Self:
        if self.hazard_count != len(self.hazards):
            raise ValueError("hazard_count must equal the number of hazards")
        return self
