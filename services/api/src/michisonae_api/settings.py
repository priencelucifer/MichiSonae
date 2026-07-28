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

    @model_validator(mode="after")
    def pool_maximum_must_cover_minimum(self) -> Self:
        if self.database_pool_max_size < self.database_pool_min_size:
            raise ValueError("database_pool_max_size must be >= database_pool_min_size")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
