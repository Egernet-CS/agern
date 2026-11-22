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

export function stagesFor(run: Run, role: StageRole): Stage[] {
  return run.stages.filter((s) => s.role === role).sort((a, b) => a.round - b.round);
}

const TERMINAL_FAIL = ['failed', 'cancelled'];

export function buildNodes(run: Run, sessions: Map<string, AgentSession>): Record<NodeId, NodeView> {
  const modelFor = (st: Stage | null, role: StageRole) => st?.model || (st?.sessionId && sessions.get(st.sessionId)?.model) || DEFAULT_MODELS[role];
  const nodes = {} as Record<NodeId, NodeView>;
  const base = (id: NodeId): NodeView => ({ id, title: ROLE_TITLE[id], sub: null, model: null, state: 'pending', verdict: null, tone: null, stage: null, startedAt: null, endedAt: null, note: null, round: null });

  nodes.issue = { ...base('issue'), title: `#${run.issueNumber}`, sub: run.issueTitle, state: 'done', note: run.issueKind, startedAt: run.createdAt };

  // Run.round counts fix rounds: 0 is the initial build, 1..maxRounds are fix rounds. A follow-up review round (a change
  // the run assistant made at needs human) has reviewers but no builder: the reviewers and the gate show the highest one.
  const currentRound = Math.max(run.round, latestStage(run, 'builder')?.round ?? 0, ...REVIEWERS.map((r) => latestStage(run, r)?.round ?? 0));

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
        n.note = `${roundLabel(run, st.round)}: ${st.verdict ?? st.status}`;
        n.verdict = null;
        n.tone = null;
      } else if (st.status === 'pending' && st.round > 0) {
        const prev = stagesFor(run, role).filter((x) => x.round < st.round && x.verdict).pop();
        if (prev) n.note = `${roundLabel(run, prev.round)}: ${prev.verdict}`;
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

  nodes.merge = { ...base('merge'), title: run.baseBranch ? 'merge to release' : ROLE_TITLE.merge, state: run.status === 'merged' ? 'done' : TERMINAL_FAIL.includes(run.status) ? 'skipped' : 'pending', sub: run.prNumber ? `PR #${run.prNumber}` : null, tone: run.status === 'merged' ? 'pass' : null, verdict: run.status === 'merged' ? 'MERGED' : null, endedAt: run.status === 'merged' ? run.endedAt : null };
  const approver = latestStage(run, 'approver');
  const approvedByHuman = run.status === 'merged' && (approver?.verdict === 'ESCALATE' || approver?.status === 'skipped');
  nodes.human = {
    ...base('human'),
    state: run.status === 'needs_human' ? 'running' : approvedByHuman ? 'done' : TERMINAL_FAIL.includes(run.status) || run.status === 'merged' ? 'skipped' : 'pending',
    sub: run.status === 'needs_human' ? HUMAN_SUB[run.reviewState ?? 'current'] : approvedByHuman ? 'approved by owner' : run.status === 'merged' ? 'not needed' : '/factory approve',
    tone: run.status === 'needs_human' ? 'escalate' : approvedByHuman ? 'pass' : null,
    verdict: run.status === 'needs_human' ? 'WAITING' : approvedByHuman ? 'APPROVED' : null,
  };
  return nodes;
}

/** "build" for round 0, "fix round N" after. */
export const roundName = (round: number) => (round === 0 ? 'build' : `fix round ${round}`);

const HUMAN_SUB: Record<NonNullable<Run['reviewState']>, string> = {
  current: 'waiting for owner',
  reviewing: 'reviewing change',
  unreviewed: 'change not reviewed',
};

/**
 * Like [roundName], but a round without a builder stage is a follow-up review of a change made at needs human:
 * "follow-up K", counted from the last builder round.
 */
export function roundLabel(run: Run, round: number): string {
  if (round === 0) return 'build';
  if (run.stages.some((s) => s.role === 'builder' && s.round === round)) return `fix round ${round}`;
  const lastBuilder = Math.max(0, ...run.stages.filter((s) => s.role === 'builder' && s.round < round).map((s) => s.round));
  return `follow-up ${round - lastBuilder}`;
}

export function rounds(run: Run): { round: number; stages: Stage[] }[] {
  const by = new Map<number, Stage[]>();
  for (const s of run.stages) {
    if (s.role === 'spec-writer') continue;
    by.set(s.round, [...(by.get(s.round) ?? []), s]);
  }
  return [...by.entries()].sort((a, b) => a[0] - b[0]).map(([round, stages]) => ({ round, stages }));
}
