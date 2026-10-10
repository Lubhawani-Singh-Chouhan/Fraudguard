from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    rules_path: str = "/app/rules/scoring_rules.json"
    log_json: bool = True


settings = Settings()
