# Load tests (k6)

Project 1 used Gatling against the rate limiter. For FraudGuard I used **k6** because
the interesting numbers here are HTTP scoring latency and gateway inject throughput,
and k6 is a bit less ceremony for a Python + Java stack.

## Scoring p95 (direct, no Kafka)

Needs scoring-service up (`http://localhost:8000`).

```bash
k6 run load-tests/k6/scoring-latency.js
```

Target I care about: **p95 scoring latency under 200ms**. On my machine this is usually
well under that (the rules engine is a handful of ifs).

## Gateway inject ramp

Needs the full compose stack. This is *not* end-to-end Kafka latency (k6 only sees the
202 from the gateway). For e2e I look at `fraudguard_pipeline_latency` in Grafana /
`/actuator/prometheus` on consumer-service.

```bash
k6 run load-tests/k6/inject-transactions.js
```

The simulator already pushes ~20 tx/s. This script is for bursting the inject API on
top of that. Stop the simulator first if you want a cleaner graph:

```bash
docker compose stop transaction-simulator
k6 run load-tests/k6/inject-transactions.js
```
