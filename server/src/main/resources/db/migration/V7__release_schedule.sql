-- Scheduled start of a release (docs/ARCHITECTURE.md §6 "Releases", "Scheduled start").
-- owner_id: the user whose credentials start (and later build) the release; step 2 (#19) plans the same column.
ALTER TABLE releases ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES users(id);
ALTER TABLE releases ADD COLUMN scheduled_at TIMESTAMPTZ;
ALTER TABLE releases ADD COLUMN waiting_for UUID REFERENCES releases(id) ON DELETE SET NULL;
ALTER TABLE releases ADD COLUMN start_failed_at TIMESTAMPTZ;
ALTER TABLE releases ADD COLUMN start_failed_owner UUID REFERENCES users(id);
ALTER TABLE releases ADD COLUMN start_failed_reason TEXT;
CREATE INDEX releases_due ON releases(scheduled_at) WHERE status = 'scheduled';
