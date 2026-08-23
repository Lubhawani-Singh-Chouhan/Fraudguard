"""Build SELECT statements from validated filters.

Clause *strings* are constants. User values only ever go in the params tuple
that psycopg sends separately (the %s placeholders). Never f-string or + a
value into SQL — that is how LLM-supplied input becomes SQL injection.

The f-strings below only interpolate TX_COLUMNS / ALERT_COLUMNS, which are
constants in this file, never request data.
"""

from __future__ import annotations

from validation import SEVERITY_BOUNDS, AlertQuery, TransactionQuery, UserRiskQuery
from config import settings

TX_COLUMNS = """
    id, user_id, amount, currency, merchant_id, merchant_category,
    location, device_id, channel, transaction_ts, risk_score, risk_level,
    scoring_reasons, scoring_mode, created_at
"""

ALERT_COLUMNS = """
    id, transaction_id, user_id, risk_score, reasons, amount, created_at
"""


def transactions_sql(q: TransactionQuery) -> tuple[str, tuple]:
    clauses: list[str] = []
    params: list = []

    if q.user_id:
        clauses.append("user_id = %s")
        params.append(q.user_id)
    if q.date_from_ts:
        clauses.append("created_at >= %s")
        params.append(q.date_from_ts)
    if q.date_to_ts:
        clauses.append("created_at <= %s")
        params.append(q.date_to_ts)
    if q.amount_min is not None:
        clauses.append("amount >= %s")
        params.append(q.amount_min)
    if q.amount_max is not None:
        clauses.append("amount <= %s")
        params.append(q.amount_max)
    if q.min_risk_score is not None:
        clauses.append("risk_score >= %s")
        params.append(q.min_risk_score)

    where = " AND ".join(clauses)
    sql = (
        f"SELECT {TX_COLUMNS} FROM transactions WHERE {where} "
        "ORDER BY created_at DESC LIMIT %s"
    )
    params.append(q.limit)
    return sql, tuple(params)


def alerts_sql(q: AlertQuery) -> tuple[str, tuple]:
    clauses: list[str] = []
    params: list = []

    if q.user_id:
        clauses.append("user_id = %s")
        params.append(q.user_id)
    if q.date_from_ts:
        clauses.append("created_at >= %s")
        params.append(q.date_from_ts)
    if q.date_to_ts:
        clauses.append("created_at <= %s")
        params.append(q.date_to_ts)
    if q.severity:
        low, high = SEVERITY_BOUNDS[q.severity]
        clauses.append("risk_score >= %s AND risk_score < %s")
        params.extend([low, high])

    where = " AND ".join(clauses)
    sql = (
        f"SELECT {ALERT_COLUMNS} FROM fraud_alerts WHERE {where} "
        "ORDER BY created_at DESC LIMIT %s"
    )
    params.append(q.limit)
    return sql, tuple(params)


def user_summary_sql(q: UserRiskQuery) -> tuple[str, tuple]:
    sql = """
        SELECT
            user_id,
            COUNT(*)::int AS total_transactions,
            COUNT(*) FILTER (
                WHERE risk_level = 'high' OR risk_score >= %s
            )::int AS flagged_count,
            COALESCE(AVG(risk_score), 0)::float AS avg_risk_score,
            MAX(created_at) AS last_transaction_at
        FROM transactions
        WHERE user_id = %s
        GROUP BY user_id
    """
    return sql, (settings.high_risk_threshold, q.user_id)


def last_flagged_sql(q: UserRiskQuery) -> tuple[str, tuple]:
    sql = f"""
        SELECT {TX_COLUMNS}
        FROM transactions
        WHERE user_id = %s
          AND (risk_level = 'high' OR risk_score >= %s)
        ORDER BY created_at DESC
        LIMIT 1
    """
    return sql, (q.user_id, settings.high_risk_threshold)
