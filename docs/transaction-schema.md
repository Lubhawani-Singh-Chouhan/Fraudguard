# Transaction JSON schema

Messages on the `transactions` topic are **plain JSON** (UTF-8), one object per Kafka record.
The Kafka key is `user_id`, so one user's events stay on the same partition.
A record that is not valid JSON, or that omits `transaction_id` or `user_id`, is retried and then published to `transactions.DLT`.

## `transactions` topic

| Field | Type | Example | Notes |
|---|---|---|---|
| `transaction_id` | string (UUID) | `"c8e1..."` | Producer-generated. Stored as the primary key of `transactions`. A duplicate delivery of the same id is skipped. |
| `user_id` | string | `"user_042"` | Synthetic user. Simulator keeps ~80 regular users. |
| `amount` | number | `2499.00` | INR. |
| `currency` | string | `"INR"` | Always INR in the simulator. |
| `merchant_id` | string | `"m_swiggy"` | |
| `merchant_category` | string | `"food"` | `food`, `ecommerce`, `travel`, `fuel`, `grocery`, `other` |
| `location` | string | `"Mumbai"` | City name. |
| `device_id` | string | `"dev_user042_a"` | Regular users reuse 1-2 devices; fraud-like rows often use a new one. |
| `channel` | string | `"upi"` | `upi`, `card`, `netbanking`, `wallet` |
| `timestamp` | string (ISO-8601) | `"2026-08-18T18:12:03.441Z"` | Event time, UTC. |

Example:

```json
{
  "transaction_id": "7d2c1a90-4b11-4e2a-9c0f-1a2b3c4d5e6f",
  "user_id": "user_042",
  "amount": 2499.00,
  "currency": "INR",
  "merchant_id": "m_swiggy",
  "merchant_category": "food",
  "location": "Mumbai",
  "device_id": "dev_user042_a",
  "channel": "upi",
  "timestamp": "2026-08-18T18:12:03.441Z"
}
```

## Scoring request (consumer → scoring-service)

The consumer enriches the event with Redis features and POSTs to `POST /score`:

```json
{
  "transaction_id": "7d2c1a90-4b11-4e2a-9c0f-1a2b3c4d5e6f",
  "user_id": "user_042",
  "amount": 2499.00,
  "merchant_category": "food",
  "location": "Mumbai",
  "device_id": "dev_user042_a",
  "channel": "upi",
  "timestamp": "2026-08-18T18:12:03.441Z",
  "tx_count": 37,
  "avg_amount": 812.40,
  "std_amount": 220.11,
  "tx_last_5m": 1,
  "is_new_device": false,
  "last_tx_ts": "2026-08-18T17:01:11.002Z"
}
```

## Scoring response

```json
{
  "transaction_id": "7d2c1a90-4b11-4e2a-9c0f-1a2b3c4d5e6f",
  "risk_score": 0.18,
  "risk_level": "low",
  "reasons": [],
  "mode": "rules",
  "latency_ms": 4
}
```

`risk_level` is `low` (< 0.40), `medium` (< 0.70), or `high` (>= 0.70). High scores are also published to the `fraud-alerts` topic.

## `fraud-alerts` topic

```json
{
  "transaction_id": "7d2c1a90-...",
  "user_id": "user_042",
  "risk_score": 0.83,
  "reasons": ["high_amount", "new_device", "velocity_spike"],
  "amount": 92000.00,
  "created_at": "2026-08-18T18:12:03.801Z"
}
```
