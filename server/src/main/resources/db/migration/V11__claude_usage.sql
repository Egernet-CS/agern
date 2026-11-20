-- Each user's own Claude usage (docs/ARCHITECTURE.md §5 "Claude usage"). Private to that user: only
-- GET /api/me/usage reads it, for the caller.
--
-- claude_usage_windows: the latest rate-limit value per limit window, from the CLI's `rate_limit_event`s while the
-- user's agents ran on their OAuth token. Cleared when the credential is replaced or removed.
CREATE TABLE claude_usage_windows (
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_type  TEXT NOT NULL,                 -- e.g. five_hour, seven_day; unknown types are kept as they are
    utilization  DOUBLE PRECISION,              -- fraction used (1.0 = 100 %), may exceed 1; null = unknown
    status       TEXT,                          -- allowed | allowed_warning | rejected | …
    resets_at    TIMESTAMPTZ,
    observed_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, window_type)
);

-- claude_spend_daily: what the user's agents cost through Agern, per UTC day, both credential kinds. Kept when a chat is
-- closed or the credential changes.
CREATE TABLE claude_spend_daily (
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day       DATE NOT NULL,             -- UTC
    cost_usd  DOUBLE PRECISION NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day)
);

-- Existing sessions: each one's total cost on the UTC day it was created.
INSERT INTO claude_spend_daily(user_id, day, cost_usd)
SELECT owner_id, (created_at AT TIME ZONE 'UTC')::date, sum(cost_usd)
FROM agent_sessions WHERE cost_usd IS NOT NULL AND cost_usd > 0 GROUP BY 1, 2;
