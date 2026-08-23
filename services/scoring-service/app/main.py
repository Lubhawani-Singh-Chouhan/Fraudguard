from __future__ import annotations

import logging
import time

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app.config import settings
from app.models import ScoreRequest, ScoreResponse
from app.scorer import risk_level, score

try:
    from pythonjsonlogger import jsonlogger
except ImportError:
    jsonlogger = None


def _setup_logging() -> None:
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    handler = logging.StreamHandler()
    if settings.log_json and jsonlogger is not None:
        handler.setFormatter(jsonlogger.JsonFormatter("%(asctime)s %(name)s %(levelname)s %(message)s"))
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    root.handlers.clear()
    root.addHandler(handler)


_setup_logging()
log = logging.getLogger("scoring")

app = FastAPI(title="FraudGuard Scoring Service", version="1.0.0")
Instrumentator().instrument(app).expose(app, endpoint="/metrics")


@app.get("/health")
def health():
    return {"status": "ok", "mode": settings.scoring_mode}


@app.post("/score", response_model=ScoreResponse)
def score_transaction(req: ScoreRequest) -> ScoreResponse:
    started = time.perf_counter()
    value, reasons, mode = score(req)
    latency_ms = int((time.perf_counter() - started) * 1000)
    resp = ScoreResponse(
        transaction_id=req.transaction_id,
        risk_score=round(value, 4),
        risk_level=risk_level(value),
        reasons=reasons,
        mode=mode,
        latency_ms=latency_ms,
    )
    log.info(
        "scored tx=%s user=%s score=%s level=%s reasons=%s mode=%s latency_ms=%s",
        req.transaction_id,
        req.user_id,
        resp.risk_score,
        resp.risk_level,
        reasons,
        mode,
        latency_ms,
    )
    return resp
