import ipaddress
from functools import lru_cache
from typing import Literal, Self

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_RATE_LIMIT_HASH_SECRET = "local-development-rate-limit-key"


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
    access_token_ttl_seconds: int = Field(default=3600, ge=60, le=3600)
    refresh_token_ttl_seconds: int = Field(
        default=2_592_000,
        ge=3600,
        le=7_776_000,
    )
    token_family_ttl_seconds: int = Field(
        default=7_776_000,
        ge=86_400,
        le=31_536_000,
    )
    maximum_active_access_tokens: int = Field(default=5, ge=1, le=20)
    observation_maximum_age_seconds: int = Field(
        default=604_800,
        ge=3600,
        le=2_592_000,
    )
    observation_future_skew_seconds: int = Field(default=300, ge=0, le=3600)
    maximum_request_bytes: int = Field(default=131_072, ge=4096, le=1_048_576)
    trusted_proxy_cidrs: str = ""
    rate_limit_hash_secret: str = DEFAULT_RATE_LIMIT_HASH_SECRET
    registration_rate_limit_per_hour: int = Field(default=10, ge=1, le=1000)
    refresh_rate_limit_per_minute: int = Field(default=30, ge=1, le=1000)
    ingestion_rate_limit_per_minute: int = Field(default=120, ge=1, le=10_000)
    public_read_rate_limit_per_minute: int = Field(default=300, ge=1, le=100_000)
    observation_retention_days: int = Field(default=90, ge=30, le=90)
    maintenance_batch_size: int = Field(default=500, ge=1, le=5000)
    dead_letter_quarantine_days: int = Field(default=7, ge=1, le=90)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    json_logging_enabled: bool = True
    worker_health_host: str = "0.0.0.0"
    projection_health_port: int = Field(default=9101, ge=0, le=65_535)
    snapshot_health_port: int = Field(default=9102, ge=0, le=65_535)
    worker_shutdown_timeout_seconds: float = Field(default=30.0, gt=0, le=120)

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
        if self.environment in {"staging", "production"} and (
            len(self.rate_limit_hash_secret) < 32
            or self.rate_limit_hash_secret == DEFAULT_RATE_LIMIT_HASH_SECRET
        ):
            raise ValueError(
                "rate_limit_hash_secret must have at least 32 characters outside local/test"
            )
        if self.environment in {"staging", "production"} and not self.json_logging_enabled:
            raise ValueError("json_logging_enabled must remain true in staging/production")
        if self.token_family_ttl_seconds < self.refresh_token_ttl_seconds:
            raise ValueError("token_family_ttl_seconds must be >= refresh_token_ttl_seconds")
        try:
            for item in self.trusted_proxy_cidrs.split(","):
                if item.strip():
                    ipaddress.ip_network(item.strip(), strict=True)
        except ValueError as error:
            raise ValueError("trusted_proxy_cidrs contains an invalid network") from error
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
