-- Project files (docs/ARCHITECTURE.md §5 "Project files"): files a project needs but must not keep in git. The bytes
-- live on disk under $FACTORY_DATA_DIR/project-files/<repo>/; only metadata is stored here.
CREATE TABLE project_files (
    id           UUID PRIMARY KEY,                 -- stable across replacements
    repo         TEXT NOT NULL,                    -- the repository the files belong to (never a user)
    name         TEXT NOT NULL,                    -- path-like, validated by the server
    size_bytes   BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256       TEXT NOT NULL,                    -- lower-case hex of the current version, computed by the server
    version      INT NOT NULL DEFAULT 1,           -- 1, +1 per replacement (only the current version's bytes are kept)
    note         TEXT,
    downloadable BOOLEAN NOT NULL DEFAULT false,
    uploaded_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX project_files_repo_name ON project_files (repo, lower(name));
CREATE INDEX project_files_repo_sha ON project_files (repo, sha256);

-- Uploads in flight (resumable): the bytes so far are in uploads/<id>.part; received = that file's length.
CREATE TABLE project_file_uploads (
    id          UUID PRIMARY KEY,
    repo        TEXT NOT NULL,
    name        TEXT NOT NULL,
    size_bytes  BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256      TEXT,                              -- declared by the uploader, checked at completion
    note        TEXT,
    created_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    touched_at  TIMESTAMPTZ NOT NULL DEFAULT now()  -- last write; expires 24 hours later
);
CREATE UNIQUE INDEX project_file_uploads_repo_name ON project_file_uploads (repo, lower(name));

-- Who besides admins may upload, replace and delete a repository's files; set by admins.
CREATE TABLE project_file_managers (
    repo      TEXT NOT NULL,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    added_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, user_id)
);

-- The project files a run got (RunProjectFilesDto): names, sizes, hashes, versions; null for runs from before.
ALTER TABLE runs ADD COLUMN project_files JSONB;
