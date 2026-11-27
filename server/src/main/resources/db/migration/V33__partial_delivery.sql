-- Partial delivery (docs/ARCHITECTURE.md §6 "Partial delivery"): a run that covers only part of its issue merges without
-- closing it. `partial` is set from the spec's Delivery plan or by the run owner (`partial_set_by`, null when the spec set
-- it); `delivery` is the parsed plan: {"delivers": "...", "remaining": ["...", ...]}.
ALTER TABLE runs ADD COLUMN partial BOOLEAN NOT NULL DEFAULT false,
                 ADD COLUMN partial_set_by TEXT,
                 ADD COLUMN delivery JSONB;
