"""
Rules-based risk scorer.

Each rule adds a weight from scoring_rules.json. Scores are clamped to [0, 1].
Rules are loaded from the JSON file bundled in the image (or the repo copy when
running tests locally).
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path

from app.config import settings
from app.models import ScoreRequest

log = logging.getLogger("scoring")


def load_rules() -> dict:
    path = Path(settings.rules_path)
    if not path.exists():
        path = Path(__file__).resolve().parent.parent / "rules" / "scoring_rules.json"
    with path.open("r", encoding="utf-8") as f:
        rules = json.load(f)
    log.info("loaded scoring rules from %s", path)
    return rules


_rules = load_rules()


def _parse_ts(raw: str) -> datetime:
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(raw)
    except ValueError:
        return datetime.now(timezone.utc)


def score_rules(req: ScoreRequest) -> tuple[float, list[str]]:
    w = _rules["weights"]
    t = _rules["thresholds"]
    score = 0.0
    reasons: list[str] = []

    if req.amount >= t["high_amount_inr"]:
        score += w["high_amount"]
        reasons.append("high_amount")
    elif req.amount >= t["elevated_amount_inr"]:
        score += w["elevated_amount"]
        reasons.append("elevated_amount")

    if req.tx_count >= t["min_history_for_zscore"] and req.std_amount > 0:
        z = abs(req.amount - req.avg_amount) / req.std_amount
        if z >= t["zscore_high"]:
            score += w["amount_zscore"]
            reasons.append("amount_zscore")
        elif z >= t["zscore_medium"]:
            score += w["amount_unusual"]
            reasons.append("amount_unusual")

    if req.tx_last_5m >= t["velocity_spike_5m"]:
        score += w["velocity_spike"]
        reasons.append("velocity_spike")
    elif req.tx_last_5m >= t["velocity_elevated_5m"]:
        score += w["velocity_elevated"]
        reasons.append("velocity_elevated")

    if req.is_new_device:
        score += w["new_device"]
        reasons.append("new_device")

    hour = _parse_ts(req.timestamp).hour
    if hour < 5 and req.amount >= t["odd_hour_amount_inr"]:
        score += w["odd_hour"]
        reasons.append("odd_hour")

    return min(score, 1.0), reasons


def score(req: ScoreRequest) -> tuple[float, list[str], str]:
    value, reasons = score_rules(req)
    return value, reasons, "rules"


def risk_level(value: float) -> str:
    if value >= _rules["thresholds"]["alert_score"]:
        return "high"
    if value >= _rules["thresholds"]["medium_score"]:
        return "medium"
    return "low"
