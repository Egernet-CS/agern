import type { AgentSession, Run, Stage, StageRole } from '../../api/types';

export const REVIEWERS: StageRole[] = ['security', 'ux', 'ui-design', 'code'];
export const DEFAULT_MODELS: Record<StageRole, string> = {
  'spec-writer': 'opus',
  builder: 'opus',
  security: 'opus',
  ux: 'sonnet',
  'ui-design': 'sonnet',
  code: 'sonnet',
  approver: 'opus',
};

export type NodeId = 'issue' | StageRole | 'gate' | 'merge' | 'human';
export type NodeState = 'pending' | 'running' | 'done' | 'failed' | 'skipped' | 'stale';

export type NodeView = {
  id: NodeId;
  title: string;
  sub: string | null;
  model: string | null;
  state: NodeState;
  verdict: string | null;
  tone: 'pass' | 'changes' | 'escalate' | 'fail' | null;
  stage: Stage | null;
  startedAt: string | null;
  endedAt: string | null;
  note: string | null;
  round: number | null;
};

export const ROLE_TITLE: Record<NodeId, string> = {
  issue: 'issue',
  'spec-writer': 'spec-writer',
  builder: 'builder',
  security: 'security',
  ux: 'ux',
  'ui-design': 'ui-design',
  code: 'code',
  gate: 'wait for all 4',
  approver: 'approver',
  merge: 'merge to main',
  human: 'needs-human',
};

export const ROLE_BLURB: Record<string, string> = {
  'spec-writer': 'Reads the issue and the repo, writes a spec and posts it on the issue.',
  builder: 'Implements the spec on the run branch, commits; resumed with findings on CHANGES.',
  security: 'Read-only review for vulnerabilities, auth and secrets.',
  ux: 'Read-only review of flows and behaviour from the user’s side.',
  'ui-design': 'Read-only review of visual consistency and states.',
  code: 'Read-only review of correctness, tests and maintainability.',
  approver: 'Final gate: APPROVE squash-merges, ESCALATE hands over to a human.',
};

const toneOf = (v: string | null): NodeView['tone'] =>
  v === 'PASS' || v === 'APPROVE' ? 'pass' : v === 'CHANGES' ? 'changes' : v === 'ESCALATE' ? 'escalate' : null;

/** Latest stage for a role (highest round, then last in array). */
export function latestStage(run: Run, role: StageRole): Stage | null {
  let best: Stage | null = null;
  for (const s of run.stages) if (s.role === role && (!best || s.round >= best.round)) best = s;
  return best;
}

/**
 * Why the approver escalated, when the run needs a human because of it (its latest approver verdict is ESCALATE): the
 * owner's merge then asks for an explicit confirmation. Null otherwise.
 */
export function approverEscalation(run: Run): string | null {
  if (run.status !== 'needs_human') return null;
  const approver = latestStage(run, 'approver');
  if (approver?.verdict !== 'ESCALATE') return null;
  return approver.summary?.trim() || 'The approver gave no reason.';
}

export function stagesFor(run: Run, role: StageRole): Stage[] {
  return run.stages.filter((s) => s.role === role).sort((a, b) => a.round - b.round);
}

const TERMINAL_FAIL = ['failed', 'cancelled'];

export function buildNodes(run: Run, sessions: Map<string, AgentSession>): Record<NodeId, NodeView> {
  const modelFor = (st: Stage | null, role: StageRole) => st?.model || (st?.sessionId && sessions.get(st.sessionId)?.model) || DEFAULT_MODELS[role];
  const nodes = {} as Record<NodeId, NodeView>;
  const base = (id: NodeId): NodeView => ({ id, title: ROLE_TITLE[id], sub: null, model: null, state: 'pending', verdict: null, tone: null, stage: null, startedAt: null, endedAt: null, note: null, round: null });

  nodes.issue = { ...base('issue'), title: `#${run.issueNumber}`, sub: run.issueTitle, state: 'done', note: run.issueKind, startedAt: run.createdAt };

  // Run.round counts fix rounds: 0 is the initial build, 1..maxRounds are fix rounds.
  const currentRound = Math.max(run.round, latestStage(run, 'builder')?.round ?? 0);

  for (const role of ['spec-writer', 'builder', ...REVIEWERS, 'approver'] as StageRole[]) {
    const st = latestStage(run, role);
    const n = base(role);
    n.model = modelFor(st, role);
    n.stage = st;
    if (st) {
      n.state = st.status;
      n.verdict = st.verdict;
      n.tone = st.status === 'failed' ? 'fail' : toneOf(st.verdict);
      n.startedAt = st.startedAt;
      n.endedAt = st.endedAt;
      n.round = st.round;
      // A reviewer verdict from an earlier round no longer counts once a fix round has started.
      const stale = REVIEWERS.includes(role) && st.round < currentRound && st.status === 'done';
      if (stale) {
        n.state = TERMINAL_FAIL.includes(run.status) ? 'skipped' : 'stale';
        n.note = `${roundName(st.round, run)}: ${st.verdict ?? st.status}`;
        n.verdict = null;
        n.tone = null;
      } else if (st.status === 'pending' && st.round > 0) {
        const prev = stagesFor(run, role).filter((x) => x.round < st.round && x.verdict).pop();
        if (prev) n.note = `${roundName(prev.round, run)}: ${prev.verdict}`;
      }
      if (st.status === 'running' && TERMINAL_FAIL.includes(run.status)) n.state = 'failed';
    } else if (TERMINAL_FAIL.includes(run.status)) n.state = 'skipped';
    if (role === 'builder' && st && st.round > 0) n.note = `fix round ${st.round}/${run.maxRounds}`;
    nodes[role] = n;
  }

  // gate: the four reviewers of the current round
  const cur = REVIEWERS.map((r) => run.stages.find((s) => s.role === r && s.round === currentRound) ?? null);
  const doneCount = cur.filter((s) => s?.status === 'done').length;
  const anyChanges = cur.some((s) => s?.verdict === 'CHANGES');
  const started = cur.some((s) => s && s.status !== 'pending');
  nodes.gate = {
    ...base('gate'),
    state: doneCount === 4 ? 'done' : started ? (TERMINAL_FAIL.includes(run.status) ? 'failed' : 'running') : TERMINAL_FAIL.includes(run.status) ? 'skipped' : 'pending',
    sub: `${doneCount}/4 in`,
    verdict: doneCount === 4 ? (anyChanges ? 'CHANGES' : 'ALL PASS') : null,
    tone: doneCount === 4 ? (anyChanges ? 'changes' : 'pass') : null,
    round: currentRound,
  };

  // Merge policy (ARCHITECTURE §6 "Merge policy"): `github` runs are merged on GitHub by the team, never by Agern.
  const onGitHub = run.mergePolicy === 'github';
  const mergedOnGitHub = run.status === 'merged' && !!run.mergedOnGitHub;
  const prSub = run.prNumber ? `PR #${run.prNumber}` : null;
  nodes.merge = {
    ...base('merge'),
    title: run.targetRepo && run.baseBranch ? `merge to ${run.baseBranch}` : run.baseBranch ? 'merge to release' : onGitHub ? 'merge on GitHub' : ROLE_TITLE.merge,
    state: run.status === 'merged' ? 'done' : TERMINAL_FAIL.includes(run.status) ? 'skipped' : 'pending',
    sub: mergedOnGitHub ? 'merged on GitHub' : run.status === 'ready' ? `${prSub ?? 'PR'} · waiting on GitHub` : prSub,
    tone: run.status === 'merged' ? 'pass' : null,
    verdict: run.status === 'merged' ? 'MERGED' : null,
    endedAt: run.status === 'merged' ? run.endedAt : null,
  };
  // Checks before the merge (docs/ARCHITECTURE.md §6 "Checks before a merge").
  if (run.status === 'waiting_for_checks') {
    nodes.merge = { ...nodes.merge, state: 'running', sub: 'waiting for checks' };
  } else if (run.status === 'needs_human' && run.failureKind === 'checks') {
    nodes.merge = { ...nodes.merge, state: 'failed', tone: 'fail', sub: 'checks not passed' };
  } else if (run.status === 'merged' && run.checks?.overriddenBy) {
    nodes.merge = { ...nodes.merge, note: 'despite checks' };
  }
  const approver = latestStage(run, 'approver');
  const review = run.conversation.review;
  const waitingSub = review === 'running' ? 'reviewing changes' : review === 'required' ? 'changes not reviewed' : 'waiting for owner';
  // Under `owner_approves` every merge is the owner's, also after an approver APPROVE; a merge on GitHub is nobody's in Agern.
  const approvedByHuman =
    run.status === 'merged' && !mergedOnGitHub && (run.mergePolicy === 'owner_approves' || approver?.verdict === 'ESCALATE' || approver?.status === 'skipped');
  nodes.human = {
    ...base('human'),
    state: run.status === 'needs_human' ? 'running' : approvedByHuman ? 'done' : TERMINAL_FAIL.includes(run.status) || run.status === 'merged' || run.status === 'ready' ? 'skipped' : 'pending',
    sub: run.status === 'needs_human' ? waitingSub : approvedByHuman ? 'approved by owner' : run.status === 'merged' || run.status === 'ready' ? 'not needed' : onGitHub ? 'review on GitHub' : '/factory approve',
    tone: run.status === 'needs_human' ? 'escalate' : approvedByHuman ? 'pass' : null,
    verdict: run.status === 'needs_human' ? 'WAITING' : approvedByHuman ? 'APPROVED' : null,
  };
  return nodes;
}

/** True for a round that reviewed changes made in the conversation while the run needed a human. */
export const isFollowUp = (round: number, run?: Pick<Run, 'conversation'>) =>
  run?.conversation.followUpFrom != null && round >= run.conversation.followUpFrom;

/** "build" for round 0, "fix round N" after; "follow-up N" for rounds that reviewed changes from the conversation. */
export const roundName = (round: number, run?: Pick<Run, 'conversation'>) =>
  isFollowUp(round, run) ? `follow-up ${round - run!.conversation.followUpFrom! + 1}` : round === 0 ? 'build' : `fix round ${round}`;

/** The run's selectable conversation node: while it needs a human, or once it has a conversation. */
export const humanSelectable = (run: Run) => run.status === 'needs_human' || run.conversation.sessionId !== null;

export function rounds(run: Run): { round: number; stages: Stage[] }[] {
  const by = new Map<number, Stage[]>();
  for (const s of run.stages) {
    if (s.role === 'spec-writer') continue;
    by.set(s.round, [...(by.get(s.round) ?? []), s]);
  }
  return [...by.entries()].sort((a, b) => a[0] - b[0]).map(([round, stages]) => ({ round, stages }));
}
