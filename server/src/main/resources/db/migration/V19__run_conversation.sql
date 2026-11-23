-- The conversation with an agent while a run needs a human (docs/ARCHITECTURE.md §6 "Conversation at needs human").
-- advisor_session_id: the run's advisor session, once its owner has sent a message (a plain column, like
--   started_by_session_id). reviewed_sha: the worktree HEAD the last completed review saw. review_state: whether
--   changes made in the conversation still need a follow-up review. follow_up_from: the first follow-up round.
ALTER TABLE runs ADD COLUMN advisor_session_id UUID;
ALTER TABLE runs ADD COLUMN reviewed_sha TEXT;
ALTER TABLE runs ADD COLUMN review_state TEXT NOT NULL DEFAULT 'none' CHECK (review_state IN ('none', 'required', 'running'));
ALTER TABLE runs ADD COLUMN follow_up_from INT;
ALTER TABLE runs ADD CONSTRAINT runs_advisor_session_fk
    FOREIGN KEY (advisor_session_id) REFERENCES agent_sessions(id) ON DELETE SET NULL;
