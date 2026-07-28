from functools import lru_cache
from typing import Literal, Self

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MICHI_", extra="ignore")

    environment: Literal["local", "test", "staging", "production"] = "local"
    database_url: str | None = None
    database_pool_min_size: int = Field(default=1, ge=0, le=20)
    database_pool_max_size: int = Field(default=10, ge=1, le=100)
    database_pool_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    database_connect_timeout_seconds: int = Field(default=5, ge=1, le=30)
    projection_batch_size: int = Field(default=25, ge=1, le=100)
    projection_lease_seconds: int = Field(default=60, ge=5, le=600)
    projection_max_attempts: int = Field(default=5, ge=1, le=20)
    projection_retry_base_seconds: float = Field(default=2.0, ge=0, le=60)
    projection_retry_max_seconds: float = Field(default=300.0, ge=0, le=3600)
    projection_poll_seconds: float = Field(default=1.0, ge=0.05, le=30)
    projection_geohash_precision: int = Field(default=8, ge=5, le=12)
    snapshot_region_geohash_precision: int = Field(default=5, ge=3, le=8)
    snapshot_batch_size: int = Field(default=10, ge=1, le=50)
    snapshot_lease_seconds: int = Field(default=60, ge=5, le=600)
    snapshot_max_attempts: int = Field(default=5, ge=1, le=20)
    snapshot_retry_base_seconds: float = Field(default=2.0, ge=0, le=60)
    snapshot_retry_max_seconds: float = Field(default=300.0, ge=0, le=3600)
    snapshot_poll_seconds: float = Field(default=1.0, ge=0.05, le=30)
    snapshot_cache_max_age_seconds: int = Field(default=60, ge=1, le=3600)
    snapshot_stale_while_revalidate_seconds: int = Field(
        default=300,
        ge=0,
        le=86_400,
    )
    snapshot_stale_after_seconds: int = Field(default=900, ge=30, le=86_400)

    @model_validator(mode="after")
    def pool_maximum_must_cover_minimum(self) -> Self:
        if self.database_pool_max_size < self.database_pool_min_size:
            raise ValueError("database_pool_max_size must be >= database_pool_min_size")
        if self.projection_retry_max_seconds < self.projection_retry_base_seconds:
            raise ValueError(
                "projection_retry_max_seconds must be >= projection_retry_base_seconds"
            )
        if self.snapshot_retry_max_seconds < self.snapshot_retry_base_seconds:
            raise ValueError("snapshot_retry_max_seconds must be >= snapshot_retry_base_seconds")
        if self.snapshot_region_geohash_precision > self.projection_geohash_precision:
            raise ValueError(
                "snapshot_region_geohash_precision must be <= projection_geohash_precision"
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
