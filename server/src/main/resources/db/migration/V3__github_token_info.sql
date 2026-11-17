-- What the registered GitHub token can do (not secret): its kind and, for classic tokens, its scopes.
ALTER TABLE users ADD COLUMN github_token_kind TEXT;
ALTER TABLE users ADD COLUMN github_token_scopes TEXT;
