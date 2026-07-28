from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MICHI_", extra="ignore")

    environment: Literal["local", "test", "staging", "production"] = "local"
    database_url: str | None = None

    @property
    def durable_ingestion_configured(self) -> bool:
        return bool(self.database_url)


@lru_cache
def get_settings() -> Settings:
    return Settings()
