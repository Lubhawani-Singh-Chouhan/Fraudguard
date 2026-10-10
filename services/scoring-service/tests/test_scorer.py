from app.models import ScoreRequest
from app.scorer import risk_level, score, score_rules


def _req(**overrides) -> ScoreRequest:
    base = dict(
        transaction_id="tx-1",
        user_id="user_001",
        amount=800.0,
        merchant_category="food",
        location="Mumbai",
        device_id="dev_a",
        channel="upi",
        timestamp="2026-08-18T12:00:00Z",
        tx_count=20,
        avg_amount=900.0,
        std_amount=150.0,
        tx_last_5m=1,
        is_new_device=False,
    )
    base.update(overrides)
    return ScoreRequest(**base)


def test_normal_spend_stays_low():
    value, reasons = score_rules(_req())
    assert value < 0.40
    assert reasons == []
    assert risk_level(value) == "low"


def test_high_amount_and_new_device_is_high():
    value, reasons = score_rules(
        _req(amount=92000, is_new_device=True, tx_last_5m=9, std_amount=200.0, avg_amount=900.0)
    )
    assert "high_amount" in reasons
    assert "new_device" in reasons
    assert "velocity_spike" in reasons
    assert value >= 0.70
    assert risk_level(value) == "high"


def test_zscore_flags_outlier():
    value, reasons = score_rules(_req(amount=5000, avg_amount=800, std_amount=100, tx_count=20))
    assert "amount_zscore" in reasons
    assert value >= 0.30


def test_score_is_rules_only():
    value, reasons, mode = score(
        _req(amount=92000, is_new_device=True, tx_last_5m=9, std_amount=200.0, avg_amount=900.0)
    )
    assert mode == "rules"
    assert "high_amount" in reasons
    assert value >= 0.70
