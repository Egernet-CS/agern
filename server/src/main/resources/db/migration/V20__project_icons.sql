-- Project icons (docs/ARCHITECTURE.md §8 "Project icons"): one per repository, set by an admin.
CREATE TABLE project_icons (
    repo        TEXT PRIMARY KEY,
    media_type  TEXT NOT NULL,            -- decided by sniffing the bytes, never by the client
    size_bytes  BIGINT NOT NULL,
    sha256      TEXT NOT NULL,
    width       INT NOT NULL,
    height      INT NOT NULL,
    data        BYTEA NOT NULL,
    updated_by  UUID NOT NULL REFERENCES users(id),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
