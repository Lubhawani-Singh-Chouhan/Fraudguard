"""Pydantic models for LLM-supplied tool arguments.

The LLM chooses *values*. It never gets to choose SQL. Allowed identifiers
(column lists, sort, severity buckets) are constants in this file.
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from config import settings

# user_id coming from the simulator looks like user_042 / load_12.
# Reject anything that looks like SQL fragments.
_USER_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_\-]{0,63}$")

Severity = Literal["low", "medium", "high"]

# Maps to the same bands the scoring service uses (low < 0.40 <= medium < 0.70 <= high).
SEVERITY_BOUNDS = {
    "low": (0.0, 0.40),
    "medium": (0.40, 0.70),
    "high": (0.70, 1.01),  # upper exclusive in SQL as <
}


class ValidationError(ValueError):
    pass


def _parse_ts(raw: str, field: str) -> datetime:
    text = raw.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(text)
    except ValueError as exc:
        raise ValidationError(f"{field} must be ISO-8601, got {raw!r}") from exc


class TransactionQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_id: Optional[str] = Field(default=None, max_length=64)
    date_from: Optional[str] = None
    date_to: Optional[str] = None
    amount_min: Optional[float] = Field(default=None, ge=0)
    amount_max: Optional[float] = Field(default=None, ge=0)
    min_risk_score: Optional[float] = Field(default=None, ge=0, le=1)
    limit: int = Field(default=25, ge=1)

    date_from_ts: Optional[datetime] = None
    date_to_ts: Optional[datetime] = None

    @field_validator("user_id")
    @classmethod
    def user_id_safe(cls, value: Optional[str]) -> Optional[str]:
        if value is None or value == "":
            return None
        if not _USER_ID_RE.match(value):
            raise ValidationError("user_id contains unsupported characters")
        return value

    @model_validator(mode="after")
    def require_filter_and_cap(self) -> "TransactionQuery":
        if self.date_from:
            self.date_from_ts = _parse_ts(self.date_from, "date_from")
        if self.date_to:
            self.date_to_ts = _parse_ts(self.date_to, "date_to")
        if self.date_from_ts and self.date_to_ts and self.date_from_ts > self.date_to_ts:
            raise ValidationError("date_from must be <= date_to")
        if self.amount_min is not None and self.amount_max is not None and self.amount_min > self.amount_max:
            raise ValidationError("amount_min must be <= amount_max")

        has_filter = any(
            [
                self.user_id,
                self.date_from_ts,
                self.date_to_ts,
                self.amount_min is not None,
                self.amount_max is not None,
                self.min_risk_score is not None,
            ]
        )
        if not has_filter:
            raise ValidationError(
                "refusing full-table dump: pass at least one of user_id, date_from/date_to, "
                "amount_min/amount_max, min_risk_score"
            )
        self.limit = min(self.limit, settings.mcp_max_rows)
        return self


class AlertQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_id: Optional[str] = Field(default=None, max_length=64)
    date_from: Optional[str] = None
    date_to: Optional[str] = None
    severity: Optional[Severity] = None
    limit: int = Field(default=25, ge=1)

    date_from_ts: Optional[datetime] = None
    date_to_ts: Optional[datetime] = None

    @field_validator("user_id")
    @classmethod
    def user_id_safe(cls, value: Optional[str]) -> Optional[str]:
        if value is None or value == "":
            return None
        if not _USER_ID_RE.match(value):
            raise ValidationError("user_id contains unsupported characters")
        return value

    @field_validator("severity", mode="before")
    @classmethod
    def severity_lower(cls, value: Optional[str]) -> Optional[str]:
        if value is None or value == "":
            return None
        return str(value).strip().lower()

    @model_validator(mode="after")
    def require_filter_and_cap(self) -> "AlertQuery":
        if self.date_from:
            self.date_from_ts = _parse_ts(self.date_from, "date_from")
        if self.date_to:
            self.date_to_ts = _parse_ts(self.date_to, "date_to")
        if self.date_from_ts and self.date_to_ts and self.date_from_ts > self.date_to_ts:
            raise ValidationError("date_from must be <= date_to")

        has_filter = any([self.user_id, self.date_from_ts, self.date_to_ts, self.severity])
        if not has_filter:
            raise ValidationError(
                "refusing full-table dump: pass at least one of user_id, date_from/date_to, severity"
            )
        self.limit = min(self.limit, settings.mcp_max_rows)
        return self


class UserRiskQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_id: str = Field(min_length=1, max_length=64)

    @field_validator("user_id")
    @classmethod
    def user_id_safe(cls, value: str) -> str:
        if not _USER_ID_RE.match(value):
            raise ValidationError("user_id contains unsupported characters")
        return value
