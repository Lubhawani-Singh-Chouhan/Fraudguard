"""
Synthetic payment traffic for FraudGuard.

Publishes JSON transactions to Kafka at a configurable rate. Most rows look like
normal UPI/card spend for a small set of returning users. A smaller slice is
deliberately "fraud-shaped" (new device + large amount, sometimes bursty) so the
rules engine actually fires and Grafana has something to show.

Env:
  KAFKA_BOOTSTRAP_SERVERS  default kafka:29092
  KAFKA_TOPIC              default transactions
  TX_RATE_PER_SEC          default 20
  FRAUD_RATIO              default 0.08
  USER_COUNT               default 80
"""

from __future__ import annotations

import json
import logging
import os
import random
import time
import uuid
from datetime import datetime, timezone

from kafka import KafkaProducer
from kafka.errors import NoBrokersAvailable

try:
    from pythonjsonlogger import jsonlogger
except ImportError:  # local run without the extra dep
    jsonlogger = None


MERCHANTS = [
    ("m_swiggy", "food"),
    ("m_zomato", "food"),
    ("m_amazon", "ecommerce"),
    ("m_flipkart", "ecommerce"),
    ("m_irctc", "travel"),
    ("m_makemytrip", "travel"),
    ("m_hpcl", "fuel"),
    ("m_bigbasket", "grocery"),
    ("m_dmart", "grocery"),
    ("m_bookmyshow", "other"),
]

CITIES = [
    "Mumbai",
    "Delhi",
    "Bengaluru",
    "Hyderabad",
    "Pune",
    "Chennai",
    "Kolkata",
    "Ahmedabad",
]

CHANNELS = ["upi", "card", "netbanking", "wallet"]


def setup_logging() -> logging.Logger:
    logger = logging.getLogger("simulator")
    logger.setLevel(logging.INFO)
    handler = logging.StreamHandler()
    if os.getenv("LOG_JSON", "true").lower() == "true" and jsonlogger is not None:
        handler.setFormatter(jsonlogger.JsonFormatter("%(asctime)s %(name)s %(levelname)s %(message)s"))
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.handlers.clear()
    logger.addHandler(handler)
    return logger


log = setup_logging()


def wait_for_producer(bootstrap: str) -> KafkaProducer:
    # Kafka is up according to compose healthchecks, but the first metadata fetch
    # still flakes on a cold broker. Retry instead of crashing the container.
    for attempt in range(1, 31):
        try:
            producer = KafkaProducer(
                bootstrap_servers=bootstrap.split(","),
                value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                key_serializer=lambda v: v.encode("utf-8") if v else None,
                linger_ms=20,
                acks=1,
            )
            return producer
        except NoBrokersAvailable:
            log.warning("kafka not ready yet, retry %s/30", attempt)
            time.sleep(2)
    raise RuntimeError(f"could not connect to kafka at {bootstrap}")


def build_users(n: int) -> list[dict]:
    users = []
    for i in range(n):
        home = random.choice(CITIES)
        users.append(
            {
                "user_id": f"user_{i:03d}",
                "home": home,
                "devices": [f"dev_user{i:03d}_a", f"dev_user{i:03d}_b"],
                "avg_amount": random.uniform(400, 2500),
            }
        )
    return users


def normal_tx(user: dict) -> dict:
    merchant_id, category = random.choice(MERCHANTS)
    amount = max(50.0, random.gauss(user["avg_amount"], user["avg_amount"] * 0.25))
    return {
        "transaction_id": str(uuid.uuid4()),
        "user_id": user["user_id"],
        "amount": round(amount, 2),
        "currency": "INR",
        "merchant_id": merchant_id,
        "merchant_category": category,
        "location": user["home"] if random.random() > 0.08 else random.choice(CITIES),
        "device_id": random.choice(user["devices"]),
        "channel": random.choices(CHANNELS, weights=[70, 18, 7, 5], k=1)[0],
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def fraud_like_tx(user: dict) -> dict:
    """Not labelled fraud — just the pattern the rules are meant to catch."""
    tx = normal_tx(user)
    tx["amount"] = round(random.uniform(78000, 140000), 2)
    tx["device_id"] = f"dev_unknown_{uuid.uuid4().hex[:8]}"
    tx["location"] = random.choice([c for c in CITIES if c != user["home"]] or CITIES)
    tx["channel"] = "card"
    tx["merchant_category"] = "ecommerce"
    tx["merchant_id"] = "m_amazon"
    return tx


def main() -> None:
    bootstrap = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
    topic = os.getenv("KAFKA_TOPIC", "transactions")
    rate = float(os.getenv("TX_RATE_PER_SEC", "20"))
    fraud_ratio = float(os.getenv("FRAUD_RATIO", "0.08"))
    user_count = int(os.getenv("USER_COUNT", "80"))

    users = build_users(user_count)
    producer = wait_for_producer(bootstrap)
    interval = 1.0 / max(rate, 0.1)
    sent = 0

    log.info(
        "simulator started rate=%s fraud_ratio=%s users=%s topic=%s",
        rate,
        fraud_ratio,
        user_count,
        topic,
    )

    while True:
        user = random.choice(users)
        event = fraud_like_tx(user) if random.random() < fraud_ratio else normal_tx(user)
        # key by user_id so one user's stream stays on the same partition
        producer.send(topic, key=event["user_id"], value=event)
        sent += 1
        if sent % 100 == 0:
            producer.flush()
            log.info("published=%s last_user=%s amount=%s", sent, event["user_id"], event["amount"])
        time.sleep(interval)


if __name__ == "__main__":
    main()
