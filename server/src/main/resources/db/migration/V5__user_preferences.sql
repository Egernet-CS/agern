-- Personal model and cost choices (docs/ARCHITECTURE.md §6 "Whose models"). Every column / key is optional:
-- what is not set falls back to the admin defaults in `settings`.
CREATE TABLE user_preferences (
    user_id          UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    chat_model       TEXT,
    pipeline_models  JSONB NOT NULL DEFAULT '{}'::jsonb,   -- role -> model, only the roles the user chose
    max_fix_rounds   INT CHECK (max_fix_rounds >= 0),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
