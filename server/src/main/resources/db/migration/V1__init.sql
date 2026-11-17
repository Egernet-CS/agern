CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    username            TEXT NOT NULL UNIQUE,
    display_name        TEXT NOT NULL,
    is_admin            BOOLEAN NOT NULL DEFAULT FALSE,
    github_login        TEXT,
    github_token_enc    BYTEA,
    claude_cred_kind    TEXT CHECK (claude_cred_kind IN ('oauth_token', 'api_key')),
    claude_cred_enc     BYTEA,
    totp_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    totp_secret_enc     BYTEA,               -- confirmed secret
    totp_pending_enc    BYTEA,               -- secret offered during enrolment, not yet confirmed
    totp_last_step      BIGINT,              -- replay protection: last accepted time step
    totp_failures       INT NOT NULL DEFAULT 0,
    totp_locked_until   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at       TIMESTAMPTZ
);

CREATE TABLE web_sessions (
    id          TEXT PRIMARY KEY,           -- SHA-256 of the cookie value, hex
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stage       TEXT NOT NULL CHECK (stage IN ('totp', 'enroll', 'full')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX web_sessions_user ON web_sessions(user_id);

CREATE TABLE runs (
    id                      UUID PRIMARY KEY,
    repo                    TEXT NOT NULL,
    issue_number            INT NOT NULL,
    issue_title             TEXT NOT NULL,
    issue_kind              TEXT NOT NULL,
    status                  TEXT NOT NULL,
    round                   INT NOT NULL DEFAULT 0,
    max_rounds              INT NOT NULL,
    branch                  TEXT,
    pr_number               INT,
    pr_url                  TEXT,
    owner_id                UUID NOT NULL REFERENCES users(id),
    started_by_session_id   UUID,
    error                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at                TIMESTAMPTZ
);
CREATE INDEX runs_repo ON runs(repo, created_at DESC);
CREATE INDEX runs_status ON runs(status);

CREATE TABLE agent_sessions (
    id                  UUID PRIMARY KEY,
    repo                TEXT NOT NULL,
    kind                TEXT NOT NULL,
    model               TEXT NOT NULL,
    status              TEXT NOT NULL,
    owner_id            UUID NOT NULL REFERENCES users(id),
    title               TEXT,
    run_id              UUID REFERENCES runs(id) ON DELETE CASCADE,
    parent_session_id   UUID REFERENCES agent_sessions(id) ON DELETE SET NULL,
    issue_number        INT,
    claude_session_id   TEXT,
    cost_usd            DOUBLE PRECISION,
    num_turns           INT,
    last_activity       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ
);
CREATE INDEX agent_sessions_repo ON agent_sessions(repo, kind, created_at DESC);
CREATE INDEX agent_sessions_status ON agent_sessions(status);
CREATE INDEX agent_sessions_run ON agent_sessions(run_id);

ALTER TABLE runs ADD CONSTRAINT runs_started_by_fk
    FOREIGN KEY (started_by_session_id) REFERENCES agent_sessions(id) ON DELETE SET NULL;

CREATE TABLE stages (
    id          UUID PRIMARY KEY,
    run_id      UUID NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    role        TEXT NOT NULL,
    round       INT NOT NULL,
    status      TEXT NOT NULL,
    verdict     TEXT,
    summary     TEXT,
    findings    JSONB NOT NULL DEFAULT '[]'::jsonb,
    session_id  UUID REFERENCES agent_sessions(id) ON DELETE SET NULL,
    started_at  TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ,
    ordinal     SERIAL
);
CREATE INDEX stages_run ON stages(run_id, ordinal);

CREATE TABLE events (
    session_id  UUID NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    seq         BIGINT NOT NULL,
    ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
    type        TEXT NOT NULL,
    payload     JSONB NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE TABLE settings (
    id      INT PRIMARY KEY CHECK (id = 1),
    value   JSONB NOT NULL
);

CREATE TABLE software_requests (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL,
    reason          TEXT NOT NULL,
    repo            TEXT NOT NULL,
    requested_by    TEXT NOT NULL,
    session_id      UUID REFERENCES agent_sessions(id) ON DELETE SET NULL,
    status          TEXT NOT NULL DEFAULT 'pending',
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);

CREATE TABLE recovery_codes (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT NOT NULL,              -- SHA-256 hex of the normalised code
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX recovery_codes_user ON recovery_codes(user_id);

CREATE TABLE auth_audit (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    username    TEXT,
    event       TEXT NOT NULL,              -- login_failed, totp_failed, totp_locked, totp_enrolled, recovery_used, recovery_regenerated, totp_reset
    detail      TEXT,
    remote      TEXT
);
CREATE INDEX auth_audit_user ON auth_audit(user_id, ts DESC);
