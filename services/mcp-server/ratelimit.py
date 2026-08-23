"""In-process rate limit for MCP tool calls.

One FastMCP process ≈ one Cursor/Claude connection. We key by the MCP
session object when the SDK gives us a Context, otherwise a single bucket.
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict

from config import settings


class RateLimitError(ValueError):
    pass


class SessionRateLimiter:
    def __init__(self, max_calls: int, window_seconds: int) -> None:
        self.max_calls = max_calls
        self.window = window_seconds
        self._hits: dict[str, list[float]] = defaultdict(list)
        self._lock = threading.Lock()

    def check(self, session_id: str) -> None:
        now = time.monotonic()
        with self._lock:
            recent = [t for t in self._hits[session_id] if now - t < self.window]
            if len(recent) >= self.max_calls:
                raise RateLimitError(
                    f"rate limit: max {self.max_calls} tool calls per {self.window}s for this session"
                )
            recent.append(now)
            self._hits[session_id] = recent


limiter = SessionRateLimiter(settings.mcp_rate_limit_max, settings.mcp_rate_limit_window_sec)


def session_key(ctx: object | None) -> str:
    if ctx is None:
        return "default"
    session = getattr(ctx, "session", None)
    if session is not None:
        return f"session:{id(session)}"
    request_id = getattr(ctx, "request_id", None)
    if request_id:
        return f"req:{request_id}"
    return "default"
