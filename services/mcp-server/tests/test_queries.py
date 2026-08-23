from queries import alerts_sql, last_flagged_sql, transactions_sql, user_summary_sql
from validation import AlertQuery, TransactionQuery, UserRiskQuery


def test_transaction_sql_uses_placeholders_not_values():
    q = TransactionQuery(user_id="user_042", min_risk_score=0.7, limit=10)
    sql, params = transactions_sql(q)
    assert "user_042" not in sql
    assert "0.7" not in sql
    assert "%s" in sql
    assert params[0] == "user_042"
    assert params[1] == 0.7
    assert params[-1] == 10
    assert "INSERT" not in sql.upper()
    assert sql.strip().upper().startswith("SELECT")


def test_alert_sql_severity_is_bound():
    q = AlertQuery(severity="high", user_id="user_001")
    sql, params = alerts_sql(q)
    assert "user_001" not in sql
    assert params[0] == "user_001"
    assert params[1] == 0.70
    assert params[2] == 1.01


def test_summary_sql_parameterized():
    q = UserRiskQuery(user_id="user_009")
    sql, params = user_summary_sql(q)
    assert "user_009" not in sql
    assert params[-1] == "user_009"
    flagged_sql, flagged_params = last_flagged_sql(q)
    assert flagged_sql.strip().upper().startswith("SELECT")
    assert flagged_params[0] == "user_009"
