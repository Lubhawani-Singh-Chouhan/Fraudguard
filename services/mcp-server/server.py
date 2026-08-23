"""FraudGuard MCP server.

Exposes three read-only tools over the Model Context Protocol so Claude Desktop
or Cursor can ask natural-language questions about scored transactions.

Protocol recap (the interview version):
  1. Host (Cursor/Claude) starts this process (stdio) or connects to /mcp (HTTP).
  2. Host sends JSON-RPC `initialize`, then `tools/list`.
  3. FastMCP returns each @mcp.tool() — name, docstring, JSON Schema from type hints.
  4. The LLM picks a tool and arguments. Host sends `tools/call`.
  5. We validate, rate-limit, run a parameterized SELECT, return JSON.

The LLM never talks to Postgres. It only fills the tool argument slots we defined.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

# so `python C:\...\server.py` still finds sibling modules
sys.path.insert(0, str(Path(__file__).resolve().parent))

from starlette.requests import Request
from starlette.responses import JSONResponse

from mcp.server.fastmcp import Context, FastMCP

from pydantic import ValidationError as PydanticValidationError

from config import settings
from db import DatabaseError, fetch_all, fetch_one
from queries import alerts_sql, last_flagged_sql, transactions_sql, user_summary_sql
from ratelimit import RateLimitError, limiter, session_key
from validation import (
    AlertQuery,
    TransactionQuery,
    UserRiskQuery,
    ValidationError,
)

_TOOL_ERRORS = (ValidationError, PydanticValidationError, RateLimitError, DatabaseError)

try:
    from pythonjsonlogger import jsonlogger
except ImportError:
    jsonlogger = None


def _setup_logging() -> None:
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    handler = logging.StreamHandler()
    # stdio transport: never write logs to stdout (that's the protocol stream).
    if settings.mcp_transport == "stdio":
        handler = logging.StreamHandler(sys.stderr)
    if settings.log_json and jsonlogger is not None:
        handler.setFormatter(jsonlogger.JsonFormatter("%(asctime)s %(name)s %(levelname)s %(message)s"))
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    root.handlers.clear()
    root.addHandler(handler)


_setup_logging()
log = logging.getLogger("mcp-server")

mcp = FastMCP(
    "fraudguard",
    instructions=(
        "Read-only fraud-detection query API. Use query_transactions, get_fraud_alerts, "
        "and get_user_risk_summary. Always pass at least one filter. You cannot insert, "
        "update, or delete anything."
    ),
    host=settings.mcp_host,
    port=settings.mcp_port,
    stateless_http=True,
    json_response=True,
)


def _guard(ctx: Context) -> None:
    limiter.check(session_key(ctx))


@mcp.custom_route("/health", methods=["GET"])
async def health(_request: Request) -> JSONResponse:
    return JSONResponse({"status": "ok", "service": "fraudguard-mcp"})


@mcp.tool()
def query_transactions(
    ctx: Context,
    user_id: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    amount_min: float | None = None,
    amount_max: float | None = None,
    min_risk_score: float | None = None,
    limit: int = 25,
) -> dict:
    """Query scored payment transactions in Postgres.

    At least one filter is required (user_id, date range, amount range, or
    min_risk_score). Results are capped (default 25, max 50). Dates are ISO-8601.
    """
    try:
        _guard(ctx)
        query = TransactionQuery(
            user_id=user_id,
            date_from=date_from,
            date_to=date_to,
            amount_min=amount_min,
            amount_max=amount_max,
            min_risk_score=min_risk_score,
            limit=limit,
        )
        sql, params = transactions_sql(query)
        rows = fetch_all(sql, params)
        log.info("query_transactions filters=%s rows=%s", query.model_dump(exclude_none=True), len(rows))
        return {"row_count": len(rows), "limit": query.limit, "rows": rows}
    except _TOOL_ERRORS as exc:
        log.warning("query_transactions rejected: %s", exc)
        return {"error": str(exc)}


@mcp.tool()
def get_fraud_alerts(
    ctx: Context,
    user_id: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    severity: str | None = None,
    limit: int = 25,
) -> dict:
    """Query persisted fraud alerts.

    severity is one of: low, medium, high (mapped to risk_score bands:
    low < 0.40, medium < 0.70, high >= 0.70). At least one filter is required.
    """
    try:
        _guard(ctx)
        query = AlertQuery(
            user_id=user_id,
            date_from=date_from,
            date_to=date_to,
            severity=severity,  # type: ignore[arg-type]
            limit=limit,
        )
        sql, params = alerts_sql(query)
        rows = fetch_all(sql, params)
        log.info("get_fraud_alerts filters=%s rows=%s", query.model_dump(exclude_none=True), len(rows))
        return {"row_count": len(rows), "limit": query.limit, "rows": rows}
    except _TOOL_ERRORS as exc:
        log.warning("get_fraud_alerts rejected: %s", exc)
        return {"error": str(exc)}


@mcp.tool()
def get_user_risk_summary(ctx: Context, user_id: str) -> dict:
    """Aggregated risk profile for one user_id.

    Returns total transactions, flagged count (high risk), average score,
    last transaction time, and the most recent flagged transaction if any.
    """
    try:
        _guard(ctx)
        query = UserRiskQuery(user_id=user_id)
        summary = fetch_one(*user_summary_sql(query))
        if summary is None:
            return {"user_id": query.user_id, "total_transactions": 0, "flagged_count": 0, "message": "no transactions"}
        last_flagged = fetch_one(*last_flagged_sql(query))
        summary["last_flagged_transaction"] = last_flagged
        log.info("get_user_risk_summary user=%s", query.user_id)
        return summary
    except _TOOL_ERRORS as exc:
        log.warning("get_user_risk_summary rejected: %s", exc)
        return {"error": str(exc)}


def main() -> None:
    transport = settings.mcp_transport.strip().lower()
    if transport not in {"stdio", "streamable-http"}:
        raise SystemExit(f"unknown MCP_TRANSPORT={transport!r} (use stdio or streamable-http)")
    log.info("starting fraudguard MCP transport=%s port=%s", transport, settings.mcp_port)
    # streamable-http is what docker compose exposes; stdio is what Claude Desktop launches.
    mcp.run(transport=transport)  # type: ignore[arg-type]


if __name__ == "__main__":
    main()
