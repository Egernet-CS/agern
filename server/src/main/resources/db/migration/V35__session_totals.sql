-- Time and cost of runs and releases (docs/ARCHITECTURE.md §6 "Time and cost"): what a session needs so its cost can be
-- attributed to a kind of work and a credential kind. Both columns are written when the session is created or launched.
ALTER TABLE agent_sessions
    ADD COLUMN round     INT,   -- the stage round of a pipeline session; NULL for chats and advisors
    ADD COLUMN cred_kind TEXT CHECK (cred_kind IN ('oauth_token', 'api_key'));  -- credential of the last launch

-- Backfill: the round from the title suffix "round <n>" (titles of round 0 have none); the credential kind from the
-- owner's current one, which is wrong for sessions run before the owner switched kinds.
UPDATE agent_sessions SET round = COALESCE(CAST(substring(title from ' round ([0-9]+)$') AS INT), 0)
 WHERE run_id IS NOT NULL AND kind NOT IN ('chat', 'advisor');
UPDATE agent_sessions s SET cred_kind = u.claude_cred_kind FROM users u WHERE u.id = s.owner_id;
