from __future__ import annotations

import json
import logging
from pathlib import Path

import boto3
from botocore.exceptions import BotoCoreError, ClientError

from app.config import settings

log = logging.getLogger("scoring")


def load_rules() -> dict:
    """Local file by default. If AWS_S3_BUCKET is set, try S3 first (Phase 5)."""
    if settings.aws_s3_bucket:
        try:
            client = boto3.client("s3", region_name=settings.aws_region)
            obj = client.get_object(Bucket=settings.aws_s3_bucket, Key=settings.aws_s3_key)
            body = obj["Body"].read().decode("utf-8")
            log.info("loaded scoring rules from s3://%s/%s", settings.aws_s3_bucket, settings.aws_s3_key)
            return json.loads(body)
        except (BotoCoreError, ClientError) as exc:
            log.warning("s3 rules load failed (%s), falling back to %s", exc, settings.rules_path)

    path = Path(settings.rules_path)
    if not path.exists():
        # running tests / local uvicorn from the service folder
        path = Path(__file__).resolve().parent.parent / "rules" / "scoring_rules.json"
    with path.open("r", encoding="utf-8") as f:
        return json.loads(f.read())
