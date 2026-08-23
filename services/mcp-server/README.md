# FraudGuard MCP server

Read-only Model Context Protocol server. An LLM (Cursor / Claude Desktop) can ask
natural-language questions; this process turns them into **parameterized** SELECTs
against Postgres. It cannot insert, update, or delete.

Details and client config: [`docs/mcp-setup.md`](../../docs/mcp-setup.md).

## Tools

| Tool | What it hits | Required filter |
|---|---|---|
| `query_transactions` | `transactions` | user_id **or** date range **or** amount range **or** min_risk_score |
| `get_fraud_alerts` | `fraud_alerts` | user_id **or** date range **or** severity (`low`/`medium`/`high`) |
| `get_user_risk_summary` | aggregates on `transactions` | `user_id` |

Hard cap: 50 rows. Rate limit: 30 calls / 60s per MCP session.

## Run

Compose (HTTP on `:8090`) comes up with the rest of the stack:

```bash
docker compose up --build
curl http://localhost:8090/health
```

Stdio (what Claude Desktop launches) against Postgres on localhost:

```bash
cd services/mcp-server
set DATABASE_URL=postgresql://fraudguard_readonly:fraudguard_readonly@localhost:5432/fraudguard
set MCP_TRANSPORT=stdio
python server.py
```

## Tests

```bash
cd services/mcp-server
pip install -r requirements.txt
pytest -q
```
