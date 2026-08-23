from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    database_url: str = (
        "postgresql://fraudguard_readonly:fraudguard_readonly@localhost:5432/fraudguard"
    )
    mcp_transport: str = "streamable-http"  # streamable-http | stdio
    mcp_host: str = "0.0.0.0"
    mcp_port: int = 8090
    mcp_rate_limit_max: int = 30
    mcp_rate_limit_window_sec: int = 60
    mcp_max_rows: int = 50
    high_risk_threshold: float = 0.70
    log_json: bool = True


settings = Settings()
