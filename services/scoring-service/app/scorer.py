"""
Rules-based risk scorer.

Each rule adds a weight from scoring_rules.json. Scores are clamped to [0, 1].
There is also an optional IsolationForest path behind SCORING_MODE=ml — I trained
it on synthetic data only, so I still treat the rules engine as the thing I can
actually defend in an interview.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from app.config import settings
from app.models import ScoreRequest
from app.s3_loader import load_rules

log = logging.getLogger("scoring")

_rules = load_rules()
_ml_model = None


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


def _feature_vector(req: ScoreRequest) -> np.ndarray:
    hour = _parse_ts(req.timestamp).hour
    return np.array(
        [
            [
                req.amount,
                req.tx_count,
                req.avg_amount,
                req.std_amount,
                req.tx_last_5m,
                1.0 if req.is_new_device else 0.0,
                hour,
            ]
        ],
        dtype=float,
    )


def _ensure_ml_model():
    """Load pickle if present; otherwise train a tiny IsolationForest on synthetic data."""
    global _ml_model
    if _ml_model is not None:
        return _ml_model

    import joblib
    from sklearn.ensemble import IsolationForest

    path = Path(settings.model_path)
    if path.exists():
        _ml_model = joblib.load(path)
        log.info("loaded IsolationForest from %s", path)
        return _ml_model

    rng = np.random.default_rng(42)
    n = 4000
    X = np.column_stack(
        [
            rng.normal(1200, 400, n).clip(50, 20000),
            rng.integers(5, 80, n),
            rng.normal(1100, 300, n).clip(50, 15000),
            rng.normal(250, 80, n).clip(10, 2000),
            rng.integers(0, 3, n),
            rng.integers(0, 2, n) * 0,  # mostly known devices
            rng.integers(7, 23, n),
        ]
    )
    model = IsolationForest(n_estimators=80, contamination=0.08, random_state=42)
    model.fit(X)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        joblib.dump(model, path)
    except OSError:
        log.warning("could not persist model to %s (read-only fs?)", path)
    _ml_model = model
    log.info("trained IsolationForest in-process (synthetic data, n=%s)", n)
    return _ml_model


def score_ml(req: ScoreRequest) -> tuple[float, list[str]]:
    model = _ensure_ml_model()
    raw = model.decision_function(_feature_vector(req))[0]
    # decision_function: higher = more normal. Flip and squash into 0-1.
    risk = float(1.0 / (1.0 + np.exp(raw * 3.0)))
    reasons = ["isolation_forest"]
    if req.is_new_device:
        reasons.append("new_device")
    return min(max(risk, 0.0), 1.0), reasons


def score(req: ScoreRequest) -> tuple[float, list[str], str]:
    mode = settings.scoring_mode.lower().strip()
    if mode == "ml":
        value, reasons = score_ml(req)
        return value, reasons, "ml"
    value, reasons = score_rules(req)
    return value, reasons, "rules"


def risk_level(value: float) -> str:
    if value >= _rules["thresholds"]["alert_score"]:
        return "high"
    if value >= _rules["thresholds"]["medium_score"]:
        return "medium"
    return "low"
