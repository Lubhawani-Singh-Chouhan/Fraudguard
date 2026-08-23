# Connecting an LLM to FraudGuard (MCP)

This is the setup + protocol note for `services/mcp-server`. The server is a
**new** Python process. It does not sit inside consumer-service or the gateway.

## How MCP works (interview version)

MCP is a JSON-RPC protocol. The LLM never opens a DB socket. Three roles:

1. **Host** — Cursor or Claude Desktop. Owns the chat UI and the model API calls.
2. **Client** — a library inside the host that speaks MCP.
3. **Server** — this repo's `server.py`. It advertises tools and executes them.

### Transport

Two ways the host talks to the server:

| Transport | When | What you see |
|---|---|---|
| **stdio** | Claude Desktop (and Cursor `command` configs) | Host **spawns** `python server.py`, then writes JSON-RPC on stdin and reads stdout. Logs must go to **stderr** or they corrupt the protocol. |
| **Streamable HTTP** | Docker Compose, `http://localhost:8090/mcp` | Server is already running. Host POSTs JSON-RPC to `/mcp`. This is what `docker compose up` exposes. |

SSE (`/sse`) is the old HTTP transport. We do not use it.

### Discovery and a tool call

```
Host                         MCP server                         Postgres
 |                                |                                |
 |  initialize                    |                                |
 |------------------------------->|                                |
 |  capabilities + server info    |                                |
 |<-------------------------------|                                |
 |  tools/list                    |                                |
 |------------------------------->|                                |
 |  [query_transactions, ...]     |                                |
 |   name, docstring, JSON Schema |                                |
 |<-------------------------------|                                |
 |                                |                                |
 |  (LLM sees the tool list,      |                                |
 |   picks one, fills arguments)  |                                |
 |                                |                                |
 |  tools/call                    |                                |
 |  name=query_transactions       |                                |
 |  args={user_id, min_risk_score}|                                |
 |------------------------------->|  SELECT ... WHERE user_id=$1   |
 |                                |------------------------------->|
 |                                |  rows                          |
 |                                |<-------------------------------|
 |  tool result JSON              |                                |
 |<-------------------------------|                                |
 |  (LLM writes a natural-language answer from the JSON)           |
```

`@mcp.tool()` on a Python function is what **registers** a tool. FastMCP (official
SDK, `mcp` package) turns the type hints into the JSON Schema the host puts in
front of the model. The docstring is the description the model reads. `Context` is
stripped out of that schema — it is injected by the SDK, not filled by the LLM.

That last point is the whole security model: the model can only pass values that
fit the schema. It cannot send a SQL string. We still validate those values
(regex on `user_id`, ISO dates, require a filter, cap `limit`) and bind them with
`%s` / psycopg parameters. The DB role is `SELECT`-only as a second fence.

## Compose

`docker compose up --build` starts `fraudguard-mcp` on **http://localhost:8090**.

- Health: `GET http://localhost:8090/health`
- MCP endpoint: `http://localhost:8090/mcp`

If Postgres was created **before** this service existed, the read-only user is
missing. Recreate the volume once:

```bash
docker compose down -v
docker compose up --build
```

(`-v` wipes local Kafka/Postgres data.)

## Cursor

Project file [`.cursor/mcp.json`](../.cursor/mcp.json) is already pointed at the
Compose HTTP endpoint. After the stack is up:

1. Cursor Settings → MCP
2. Confirm **fraudguard** is listed and green
3. In chat, ask something like: *Show me high-risk transactions for user_001*

If your Cursor build wants a command instead of a URL, use stdio (Postgres must
be reachable on `localhost:5432`):

```json
{
  "mcpServers": {
    "fraudguard": {
      "command": "python",
      "args": ["C:/Users/hp/fraudguard/services/mcp-server/server.py"],
      "env": {
        "MCP_TRANSPORT": "stdio",
        "DATABASE_URL": "postgresql://fraudguard_readonly:fraudguard_readonly@localhost:5432/fraudguard"
      }
    }
  }
}
```

## Claude Desktop

Edit `%APPDATA%\Claude\claude_desktop_config.json` (Windows) or
`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "fraudguard": {
      "command": "python",
      "args": ["C:/Users/hp/fraudguard/services/mcp-server/server.py"],
      "env": {
        "MCP_TRANSPORT": "stdio",
        "DATABASE_URL": "postgresql://fraudguard_readonly:fraudguard_readonly@localhost:5432/fraudguard",
        "LOG_JSON": "false"
      }
    }
  }
}
```

Restart Claude Desktop. You need Python 3.11+ with `pip install -r services/mcp-server/requirements.txt`,
and Compose Postgres up so `localhost:5432` works.

## Example prompts

- Show me all high-risk transactions for user_042 in the last 7 days
- Any fraud alerts for user_001 with severity high?
- Give me a risk summary for user_010
- List transactions over 50,000 INR with risk score at least 0.7

A dump of the whole table should be **rejected** ("pass at least one filter").
