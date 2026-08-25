# FraudGuard – Real-Time Transaction Stream Processor

**Kafka · Spring Boot · Python · Redis · PostgreSQL**

Built a real-time fraud detection pipeline using Kafka, Spring Boot, Redis, FastAPI, and
PostgreSQL to score and store high-risk payment transactions, with a circuit breaker
(Resilience4j) so scoring outages don’t stall the consumer. Added a read-only MCP server
so an LLM can query fraud data in plain English without writing SQL.

Local stack is Docker Compose.

This sits next to my [distributed rate limiter](https://github.com/Lubhawani-Singh-Chouhan/Distributed-Rate-Limiter)
(Spring Boot + Redis). That project was "protect an HTTP API." This one is "process a stream."

## Architecture

```mermaid
flowchart LR
    Sim["transaction-simulator"] --> KafkaTx["Kafka: transactions"]
    GW["Gateway :8080"] --> KafkaTx
    KafkaTx --> Consumer["consumer-service<br/>Spring Boot"]
    Consumer --> Redis[("Redis features")]
    Consumer -->|"REST + Resilience4j CB"| Score["scoring-service<br/>FastAPI"]
    Consumer --> PG[("PostgreSQL")]
    Consumer --> KafkaAlert["Kafka: fraud-alerts"]
    MCP["mcp-server"] --> PG
```

```mermaid
sequenceDiagram
    participant Sim as Simulator
    participant K as Kafka
    participant C as consumer-service
    participant R as Redis
    participant S as scoring-service
    participant P as Postgres

    Sim->>K: JSON transaction (key=user_id)
    K->>C: consume
    C->>R: snapshot user features (avg, velocity, devices)
    C->>S: POST /score
    alt scoring-service healthy
        S-->>C: risk_score 0..1 + reasons
    else circuit open / timeout
        C->>C: LocalRulesFallback
    end
    C->>P: insert transactions (+ fraud_alerts if high)
    C->>R: record this tx into features
    opt score >= 0.70
        C->>K: fraud-alerts
    end
```

## Tech stack

Kafka · Spring Boot 3.3 (Java 21) · Python 3.11 / FastAPI · Redis · PostgreSQL ·
Resilience4j · MCP.

Scoring is **rules-based** (amount z-score, velocity, new-device, odd-hour). There is an
optional IsolationForest path behind `SCORING_MODE=ml` so the scorer can be swapped later —
it is not the default, and it is not an ML fraud model.

## Repo layout

```
fraudguard/
├── docker-compose.yml
├── services/
│   ├── gateway-service/          # inject / query HTTP API
│   ├── consumer-service/         # Kafka → Redis → score → Postgres
│   ├── scoring-service/          # FastAPI rules engine
│   ├── transaction-simulator/    # synthetic payment stream
│   └── mcp-server/               # read-only MCP tools for Cursor/Claude
├── docs/                         # JSON schema + MCP client setup
├── k8s/                          # optional kind/minikube manifests
├── infra/aws/                    # optional EC2 + S3 + ECR stretch
└── load-tests/k6/
```

JSON schema for the Kafka payload: [`docs/transaction-schema.md`](docs/transaction-schema.md).

## How to run (local)

Docker Desktop needs to be running. First time takes a while (Maven + pip inside image builds).

```bash
docker compose up --build
```

| What | URL |
|---|---|
| Gateway | http://localhost:8080 |
| Consumer API | http://localhost:8081 |
| Scoring | http://localhost:8000/health |
| MCP (LLM tools) | http://localhost:8090/health |
| Grafana (admin/admin) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

Give Kafka ~30s on a cold start, then:

```bash
# inject one transaction (or use docs/sample-high-risk.json)
curl -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -d "@docs/sample-high-risk.json"

# wait a second, then (use the transaction_id from the 202 body)
curl http://localhost:8080/transactions/<id>/score

curl "http://localhost:8080/alerts?since=2026-01-01T00:00:00Z"
```

Watch the simulator in logs:

```bash
docker logs -f fraudguard-simulator
docker logs -f fraudguard-consumer
```

Confirm Kafka has messages (optional):

```bash
docker exec -it fraudguard-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic transactions --from-beginning --max-messages 5
```

Grafana dashboards provision automatically (throughput, fraud alert rate, circuit breaker).
Take screenshots from a live run if you want them for a portfolio.

## MCP (ask the database in English)

`services/mcp-server` is a **separate** Python process using the official MCP SDK
(`FastMCP`). Cursor or Claude Desktop can call three read-only tools:

- `query_transactions` — filter `transactions` by user, dates, amount, min risk score
- `get_fraud_alerts` — filter `fraud_alerts` by user, dates, severity
- `get_user_risk_summary` — totals / flagged count / avg score / last flagged tx for one user

It comes up with the rest of the stack on **http://localhost:8090**. Cursor is already
wired via [`.cursor/mcp.json`](.cursor/mcp.json). Claude Desktop and the protocol
walkthrough: [`docs/mcp-setup.md`](docs/mcp-setup.md).

**Why read-only + parameterized SQL:** the model chooses *arguments* (`user_id`, dates),
not SQL. If we concatenated those strings into a query, a prompt like
`user_id = 1; DROP TABLE transactions` would be a real injection. Every value goes through
Pydantic then into a `%s` bind. The DB user is `fraudguard_readonly` (`SELECT` only), and
connections are opened with `readonly=True`. Full-table dumps are rejected (need at least
one filter; max 50 rows). Tool calls are capped at 30 / 60s per session.

Example prompts after Compose is up:

- Show me all high-risk transactions for user_042 in the last 7 days
- Any high-severity fraud alerts for user_001?
- Give me a risk summary for user_010

No extra UI — the chat client *is* the interface.

## Design decisions

- **Kafka instead of REST between producer and consumer.** The simulator should not care
  if scoring is slow. Kafka gives backpressure, replay (`--from-beginning`), and a place to
  hang a second consumer later (analytics) without touching the producer. Scoring is still
  a **sync REST** call because a request/reply topic plus correlation ids is a bigger
  follow-up; I'd do that next if scoring p95 started to hurt.
- **Circuit breaker on the scoring call.** If FastAPI is down, the Kafka consumer should
  still drain the topic. Resilience4j opens after 50% failures in a 10-call window;
  `LocalRulesFallback` does a simpler amount/device/velocity check so we don't just drop
  messages. Trade-off: fallback scores are coarser, which I accept for availability.
- **Rules engine as the real scorer.** I can explain every weight in
  `services/scoring-service/rules/scoring_rules.json`. The IsolationForest is behind a flag
  and trained on synthetic data — I would not put that on a resume as "ML fraud model."
- **MCP tools never take a SQL string.** The LLM only fills typed arguments. Queries are
  parameterized, the role is SELECT-only, and unfiltered dumps are rejected. That is the
  difference between "an LLM can query my fraud DB" and "an LLM can wreck my fraud DB."

## Tests

```bash
# scoring rules
cd services/scoring-service
pip install -r requirements.txt
PYTHONPATH=. pytest -q

# MCP validation / parameterized SQL / rate limit
cd services/mcp-server
pip install -r requirements.txt
pytest -q

# consumer fallback
cd services/consumer-service
mvn -q test

# gateway routes load
cd services/gateway-service
mvn -q test
```

CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs those on push/PR.

## Observability

- Spring: Micrometer → `/actuator/prometheus`
- FastAPI: `prometheus_fastapi_instrumentator` → `/metrics`
- Custom counters: `fraudguard_transactions_scored_total`,
  `fraudguard_alerts_published_total`, `fraudguard_transactions_fallback_total`
- JSON logs (`logstash-logback-encoder` / `python-json-logger`)

## Load tests

k6 scripts live in [`load-tests/`](load-tests/README.md).

```bash
k6 run load-tests/k6/scoring-latency.js
```

Target I set for myself: **p95 scoring < 200ms**. The rules engine is cheap; the number that
moves under load is consumer e2e (`fraudguard_pipeline_latency` on `:8081/actuator/prometheus`)
once Kafka + Redis + HTTP are in the path.

## Kubernetes (optional)

Compose is the path I actually iterate on. Manifests in `k8s/` describe the same topology
for kind/minikube, with an HPA on scoring-service only (CPU 70%, 1–3 replicas).

```bash
docker compose build
docker tag fraudguard-scoring-service fraudguard/scoring-service:1.0.0
docker tag fraudguard-consumer-service fraudguard/consumer-service:1.0.0
docker tag fraudguard-gateway-service fraudguard/gateway-service:1.0.0
docker tag fraudguard-transaction-simulator fraudguard/transaction-simulator:1.0.0

kind create cluster --name fraudguard
kind load docker-image fraudguard/scoring-service:1.0.0 --name fraudguard
kind load docker-image fraudguard/consumer-service:1.0.0 --name fraudguard
kind load docker-image fraudguard/gateway-service:1.0.0 --name fraudguard
kind load docker-image fraudguard/transaction-simulator:1.0.0 --name fraudguard

kubectl apply -k k8s/
kubectl -n fraudguard get pods
```

metrics-server must be installed in kind for the HPA to actually scale.

## AWS stretch (optional)

Terraform in `infra/aws/` creates ECR repos, an S3 bucket with the rules JSON, and one
EC2 instance with Docker. Set `AWS_S3_BUCKET` on scoring-service to load rules from S3
(`app/s3_loader.py`); it falls back to the image file if S3 isn't there.

I treat this as "deployed to validate," not "ran in production." Tear it down after
screenshots (`terraform destroy`). See [`infra/aws/README.md`](infra/aws/README.md).

## Future work

- Avro + Schema Registry instead of plain JSON
- Fully async scoring over a Kafka topic (the sync REST hop is the obvious bottleneck)
- A real feature store instead of a Redis hash
- Dedicated alert-service that pages / webhooks instead of logging in the same JVM
- Proper ML training pipeline if I ever get labelled data that isn't synthetic

## Resume

**FraudGuard – Real-Time Transaction Stream Processor | Kafka, Spring Boot, Python, Redis, PostgreSQL**

Built a real-time fraud detection pipeline using Kafka, Spring Boot, Redis, FastAPI, and
PostgreSQL to score and store high-risk payment transactions, with a circuit breaker
(Resilience4j) so scoring outages don’t stall the consumer. Added a read-only MCP server
so an LLM can query fraud data in plain English without writing SQL.

## 60-second walkthrough

"It's a small event-driven payments pipeline. A simulator publishes JSON transactions to
Kafka. A Spring Boot consumer looks up that user's recent spend in Redis — count, average,
stddev, devices, 5-minute velocity — then calls a Python scoring service. Scoring is
weighted rules I can explain: high amount, z-score vs the user's own history, velocity,
new device. If the Python service is slow I trip a circuit breaker and fall back to a
simpler local check so Kafka doesn't pile up. High scores go to Postgres and a fraud-alerts
topic. A read-only MCP server lets an LLM query that data in plain English without writing
SQL. I run it all in Docker Compose."

## License

MIT
