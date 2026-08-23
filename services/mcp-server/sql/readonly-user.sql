-- Read-only Postgres role for the MCP server.
-- Init scripts in docker-entrypoint-initdb.d run once on an empty volume.
-- If this DB volume already exists, either: docker compose down -v
-- or run this file by hand against the existing DB.

CREATE USER fraudguard_readonly WITH PASSWORD 'fraudguard_readonly';

GRANT CONNECT ON DATABASE fraudguard TO fraudguard_readonly;
GRANT USAGE ON SCHEMA public TO fraudguard_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO fraudguard_readonly;

-- tables created later still get SELECT
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO fraudguard_readonly;
