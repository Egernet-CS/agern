-- Screenshots the UX and UI design reviewers attach to their stage (docs/ARCHITECTURE.md §6 "Screenshots").
-- Bound to the stage row, so to its role and round; removed with the stage and with the run.
CREATE TABLE stage_screenshots (
    id          UUID PRIMARY KEY,
    run_id      UUID NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    stage_id    UUID NOT NULL REFERENCES stages(id) ON DELETE CASCADE,
    caption     TEXT NOT NULL,                  -- sanitised: one line, at most 200 characters
    media_type  TEXT NOT NULL,                  -- decided by sniffing the bytes: png, jpeg, gif or webp
    size_bytes  BIGINT NOT NULL,
    sha256      TEXT NOT NULL,
    width       INT NOT NULL,
    height      INT NOT NULL,
    data        BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ordinal     SERIAL
);
CREATE INDEX stage_screenshots_run ON stage_screenshots(run_id, ordinal);
CREATE INDEX stage_screenshots_stage ON stage_screenshots(stage_id);
