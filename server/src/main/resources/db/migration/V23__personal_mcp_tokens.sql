-- Personal MCP tokens (docs/ARCHITECTURE.md §7 "Personal tokens"): a user's bearer token for POST /mcp from Claude on
-- their own computer, bound to one project. Only the SHA-256 of the token is stored. Revoked rows are kept for 30 days
-- (so a stale client configuration is "known" and does not count as guessing), then deleted at startup.
-- auth_audit gains the events mcp_token_created, mcp_token_revoked and mcp_token_locked.
CREATE TABLE mcp_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    repo         TEXT NOT NULL,
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX mcp_tokens_user ON mcp_tokens(user_id, created_at DESC);
