-- The model a stage runs (or ran) with, so everyone can see it without reading the session.
ALTER TABLE stages ADD COLUMN model TEXT;
UPDATE stages st SET model = s.model FROM agent_sessions s WHERE st.session_id = s.id;
UPDATE stages SET model = '' WHERE model IS NULL;
ALTER TABLE stages ALTER COLUMN model SET NOT NULL;
