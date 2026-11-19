-- Files and images attached to chat messages (docs/ARCHITECTURE.md §8 "Attachments").
-- The bytes live here; the copy under $FACTORY_DATA_DIR/attachments/ that agents read is a cache.
CREATE TABLE attachments (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    uploader_id     UUID NOT NULL REFERENCES users(id),
    name            TEXT NOT NULL,                  -- sanitised display name
    media_type      TEXT NOT NULL,                  -- decided by sniffing the bytes, never by the client
    kind            TEXT NOT NULL CHECK (kind IN ('image', 'file')),
    size_bytes      BIGINT NOT NULL,
    sha256          TEXT NOT NULL,
    width           INT,
    height          INT,
    data            BYTEA NOT NULL,
    -- How the image reaches the model inline: original bytes, a downscaled copy, or not at all (file only).
    inline_mode     TEXT NOT NULL CHECK (inline_mode IN ('original', 'downscaled', 'none')),
    inline_data     BYTEA,                          -- only for 'downscaled'
    inline_media_type TEXT,
    inline_width    INT,
    inline_height   INT,
    note            TEXT,                           -- human-readable reason for 'downscaled' / 'none'
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ                     -- null until sent with a message
);
CREATE INDEX attachments_session ON attachments(session_id, created_at);
CREATE INDEX attachments_unsent ON attachments(created_at) WHERE sent_at IS NULL;
