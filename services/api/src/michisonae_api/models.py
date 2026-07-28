from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator


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


class ApiError(BaseModel):
    code: str
    message: str
