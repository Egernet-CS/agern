-- Image generation (docs/ARCHITECTURE.md §6 "Generated images"): one provider per user, its API key encrypted like
-- the Claude credential, and the images the agents generated with it.
ALTER TABLE users ADD COLUMN image_provider TEXT, ADD COLUMN image_model TEXT, ADD COLUMN image_key_enc BYTEA;

CREATE TABLE generated_images (
    id              UUID PRIMARY KEY,
    repo            TEXT NOT NULL,
    owner_id        UUID NOT NULL REFERENCES users(id),
    origin          TEXT NOT NULL CHECK (origin IN ('chat','run')),
    session_id      UUID NOT NULL,              -- deliberately no FK: a closed chat's selected images keep it
    run_id          UUID REFERENCES runs(id) ON DELETE CASCADE,
    request_id      UUID NOT NULL,              -- one tool call; its images share it
    provider        TEXT NOT NULL,
    model           TEXT NOT NULL,
    prompt          TEXT NOT NULL,
    purpose         TEXT NOT NULL,
    media_type      TEXT NOT NULL,              -- sniffed: png, jpeg or webp
    size_bytes      BIGINT NOT NULL,
    sha256          TEXT NOT NULL,
    width           INT NOT NULL,
    height          INT NOT NULL,
    transparent     BOOLEAN NOT NULL,
    data            BYTEA NOT NULL,
    issue_number    INT,                        -- issue_number, name, selected_at: all null = not selected
    name            TEXT,
    selected_at     TIMESTAMPTZ,
    used_by_run_id  UUID REFERENCES runs(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ordinal         SERIAL
);
CREATE INDEX generated_images_session ON generated_images(session_id, ordinal);
CREATE INDEX generated_images_run ON generated_images(run_id) WHERE run_id IS NOT NULL;
CREATE INDEX generated_images_issue ON generated_images(repo, issue_number) WHERE issue_number IS NOT NULL;
