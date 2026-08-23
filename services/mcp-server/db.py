from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from decimal import Decimal
from datetime import datetime
from typing import Any

import psycopg
from psycopg.rows import dict_row

from config import settings


class DatabaseError(RuntimeError):
    pass


@contextmanager
def read_only_connection() -> Iterator[psycopg.Connection]:
    """Always open READ ONLY. The DB user is SELECT-only as a second layer."""
    try:
        conn = psycopg.connect(settings.database_url, readonly=True, row_factory=dict_row)
    except psycopg.Error as exc:
        raise DatabaseError(
            "could not connect to postgres as the read-only MCP user. "
            "If this DB volume was created before the MCP service existed, run "
            "`docker compose down -v` once (wipes local data) or apply "
            "services/mcp-server/sql/readonly-user.sql by hand."
        ) from exc
    try:
        yield conn
    finally:
        conn.close()


def jsonable(row: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in row.items():
        if isinstance(value, Decimal):
            out[key] = float(value)
        elif isinstance(value, datetime):
            out[key] = value.isoformat()
        else:
            out[key] = value
    return out


def fetch_all(sql: str, params: tuple) -> list[dict[str, Any]]:
    with read_only_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return [jsonable(r) for r in cur.fetchall()]


def fetch_one(sql: str, params: tuple) -> dict[str, Any] | None:
    rows = fetch_all(sql, params)
    return rows[0] if rows else None
