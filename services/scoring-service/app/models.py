from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class ScoreRequest(BaseModel):
    transaction_id: str
    user_id: str
    amount: float
    merchant_category: str = "other"
    location: str = ""
    device_id: str = ""
    channel: str = "upi"
    timestamp: str

    tx_count: int = 0
    avg_amount: float = 0.0
    std_amount: float = 0.0
    tx_last_5m: int = 0
    is_new_device: bool = False
    last_tx_ts: Optional[str] = None


class ScoreResponse(BaseModel):
    transaction_id: str
    risk_score: float = Field(ge=0.0, le=1.0)
    risk_level: str
    reasons: list[str]
    mode: str
    latency_ms: int
