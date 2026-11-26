// Unit tests of the pipeline model, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Run, Stage, Verdict } from '../../api/types';
import { approverEscalation, buildNodes } from './model.ts';

const approver = (round: number, verdict: Verdict | null, summary: string | null) =>
  ({ role: 'approver', round, verdict, summary, status: 'done' }) as unknown as Stage;
const run = (status: Run['status'], stages: Stage[]) => ({ status, stages }) as unknown as Run;

test('an escalated run that needs a human gives the approver its reason', () => {
  assert.equal(approverEscalation(run('needs_human', [approver(0, 'ESCALATE', '  A new dependency.  ')])), 'A new dependency.');
  assert.equal(approverEscalation(run('needs_human', [approver(0, 'ESCALATE', null)])), 'The approver gave no reason.');
});

test('the latest approver verdict counts', () => {
  assert.equal(approverEscalation(run('needs_human', [approver(0, 'ESCALATE', 'old'), approver(1, 'APPROVE', 'fine')])), null);
  assert.equal(approverEscalation(run('needs_human', [approver(0, 'APPROVE', 'fine'), approver(1, 'ESCALATE', 'new')])), 'new');
});

test('no escalation: an approved run whose merge failed, or a run that no longer needs a human', () => {
  assert.equal(approverEscalation(run('needs_human', [approver(0, 'APPROVE', 'fine')])), null);
  assert.equal(approverEscalation(run('needs_human', [])), null);
  assert.equal(approverEscalation(run('merged', [approver(0, 'ESCALATE', 'risky')])), null);
});

// Merge policy (ARCHITECTURE §6 "Merge policy")
const policyRun = (status: Run['status'], extra: Partial<Run>, verdict: Verdict = 'APPROVE') =>
  ({
    id: 'r', repo: 'demo', issueNumber: 1, issueTitle: 't', issueKind: 'feature', status, round: 0, maxRounds: 2, prNumber: 7,
    stages: [approver(0, verdict, 'ok')], conversation: { sessionId: null, review: 'none', followUpFrom: null }, endedAt: null,
    ...extra,
  }) as unknown as Run;

test('a ready run waits on GitHub and needs nobody in Agern', () => {
  const n = buildNodes(policyRun('ready', { mergePolicy: 'github' }), new Map());
  assert.equal(n.merge.title, 'merge on GitHub');
  assert.equal(n.merge.state, 'pending');
  assert.equal(n.merge.sub, 'PR #7 · waiting on GitHub');
  assert.equal(n.human.state, 'skipped');
  assert.notEqual(n.gate.state, 'failed');
});

test('a run merged on GitHub says so', () => {
  const n = buildNodes(policyRun('merged', { mergePolicy: 'github', mergedOnGitHub: true, mergedBy: 'carol' }), new Map());
  assert.equal(n.merge.state, 'done');
  assert.equal(n.merge.sub, 'merged on GitHub');
  assert.equal(n.human.state, 'skipped');
});

test('under owner_approves a merged run was approved by its owner, also after an approver APPROVE', () => {
  const n = buildNodes(policyRun('merged', { mergePolicy: 'owner_approves' }), new Map());
  assert.equal(n.human.state, 'done');
  assert.equal(n.human.sub, 'approved by owner');
  const auto = buildNodes(policyRun('merged', { mergePolicy: 'automatic' }), new Map());
  assert.equal(auto.human.state, 'skipped');
});

// The merge node and the checks before a merge (docs/ARCHITECTURE.md §6 "Checks before a merge").
const full = (status: Run['status'], extra: Partial<Run> = {}) =>
  ({
    status, stages: [approver(0, 'APPROVE', 'fine')], round: 0, maxRounds: 2, issueNumber: 1, issueTitle: 't', issueKind: 'task',
    createdAt: '2026-01-01T00:00:00Z', endedAt: null, prNumber: 7, baseBranch: null, targetRepo: null,
    conversation: { sessionId: null, review: 'none', followUpFrom: null }, failureKind: null, checks: null, ...extra,
  }) as unknown as Run;
const mergeNode = (r: Run) => buildNodes(r, new Map()).merge;
const checks = (overriddenBy: string | null = null) =>
  ({ state: 'failed', sha: 'abc', detail: 'Failed: build', items: [], waitingSince: null, deadline: null, overriddenBy, overriddenAt: null }) as Run['checks'];

test('the merge node runs while the run waits for checks', () => {
  const n = mergeNode(full('waiting_for_checks'));
  assert.equal(n.state, 'running');
  assert.equal(n.sub, 'waiting for checks');
});

test('the merge node fails when checks blocked the merge, not for other needs-human reasons', () => {
  const n = mergeNode(full('needs_human', { failureKind: 'checks', checks: checks() }));
  assert.equal(n.state, 'failed');
  assert.equal(n.tone, 'fail');
  assert.equal(n.sub, 'checks not passed');
  assert.equal(mergeNode(full('needs_human')).state, 'pending');
});

test('a merge despite checks is noted on the merge node', () => {
  const n = mergeNode(full('merged', { checks: checks('alice') }));
  assert.equal(n.state, 'done');
  assert.equal(n.verdict, 'MERGED');
  assert.equal(n.note, 'despite checks');
  assert.equal(mergeNode(full('merged', { checks: { ...checks()!, state: 'passed' } })).note, null);
});
