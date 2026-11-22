-- Talking to an agent while a run waits at needs_human (docs/ARCHITECTURE.md §6 "Talking to an agent at needs human").
-- head_sha: the branch head Agern knows; reviewed_sha: the head the last complete review round covered (both null for
-- runs from before this change, which counts as reviewed). assistant_session_id: the run's `assistant` conversation.
ALTER TABLE runs
    ADD COLUMN head_sha TEXT,
    ADD COLUMN reviewed_sha TEXT,
    ADD COLUMN assistant_session_id UUID REFERENCES agent_sessions(id) ON DELETE SET NULL;
