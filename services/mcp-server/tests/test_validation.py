from validation import AlertQuery, TransactionQuery, UserRiskQuery, ValidationError
import pytest
from pydantic import ValidationError as PydanticValidationError


def test_transactions_require_a_filter():
    with pytest.raises((ValidationError, PydanticValidationError)):
        TransactionQuery()


def test_transactions_user_id_ok():
    q = TransactionQuery(user_id="user_042", limit=99)
    assert q.user_id == "user_042"
    assert q.limit == 50  # capped by mcp_max_rows default


def test_transactions_reject_sql_in_user_id():
    with pytest.raises((ValidationError, PydanticValidationError)):
        TransactionQuery(user_id="user_1; DROP TABLE transactions")


def test_alert_severity_and_filter():
    q = AlertQuery(severity="HIGH")
    assert q.severity == "high"


def test_alerts_require_a_filter():
    with pytest.raises((ValidationError, PydanticValidationError)):
        AlertQuery()


def test_user_summary_requires_user():
    with pytest.raises((ValidationError, PydanticValidationError)):
        UserRiskQuery(user_id="")
