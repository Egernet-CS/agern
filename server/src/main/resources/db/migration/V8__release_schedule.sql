-- Scheduled start of releases (docs/ARCHITECTURE.md §6 "Releases", "Scheduled start").
-- owner_id (V7) is set when the release is scheduled: the scheduler starts and builds it as that user.
ALTER TABLE releases ADD COLUMN scheduled_at TIMESTAMPTZ;
ALTER TABLE releases ADD COLUMN waiting_for UUID REFERENCES releases(id) ON DELETE SET NULL;
ALTER TABLE releases ADD COLUMN start_failed_at TIMESTAMPTZ;
ALTER TABLE releases ADD COLUMN start_failed_owner UUID REFERENCES users(id);
ALTER TABLE releases ADD COLUMN start_failed_reason TEXT;
CREATE INDEX releases_due ON releases(scheduled_at) WHERE status = 'scheduled';
