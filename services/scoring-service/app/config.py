from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    scoring_mode: str = "rules"  # rules | ml
    fraud_threshold: float = 0.70
    rules_path: str = "/app/rules/scoring_rules.json"
    model_path: str = "/app/models/isolation_forest.pkl"
    aws_s3_bucket: str = ""
    aws_s3_key: str = "scoring/scoring_rules.json"
    aws_region: str = "ap-south-1"
    log_json: bool = True


settings = Settings()
