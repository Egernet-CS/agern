// Unit tests of the pipeline model, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Run, RunUpdate, Stage, Verdict } from '../../api/types';
import { approverEscalation, buildNodes, isFollowUp, roundName } from './model.ts';

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

const update = (number: number, round: number) => ({ number, round, maxRounds: 2 }) as unknown as RunUpdate;
const rounds = (followUpFrom: number | null, updates: RunUpdate[]) =>
  ({ conversation: { followUpFrom }, updates }) as unknown as Run;

test('update rounds are named "update N"', () => {
  const r = rounds(null, [update(1, 2), update(2, 3)]);
  assert.equal(roundName(0, r), 'build');
  assert.equal(roundName(1, r), 'fix round 1');
  assert.equal(roundName(2, r), 'update 1');
  assert.equal(roundName(3, r), 'update 2');
  assert.equal(isFollowUp(2, r), false);
});

test('follow-up numbers skip the update rounds after the first follow-up', () => {
  // Build 0, follow-up 1 in round 1, update 1 in round 2, follow-up 2 in round 3.
  const r = rounds(1, [update(1, 2)]);
  assert.equal(roundName(1, r), 'follow-up 1');
  assert.equal(roundName(2, r), 'update 1');
  assert.equal(isFollowUp(2, r), false);
  assert.equal(roundName(3, r), 'follow-up 2');
  assert.equal(isFollowUp(3, r), true);
  // A follow-up after an update that came before any follow-up.
  const later = rounds(3, [update(1, 2)]);
  assert.equal(roundName(2, later), 'update 1');
  assert.equal(roundName(3, later), 'follow-up 1');
});

test('runs without updates are named as before', () => {
  const r = { conversation: { followUpFrom: 2 } } as unknown as Run;
  assert.equal(roundName(1, r), 'fix round 1');
  assert.equal(roundName(2, r), 'follow-up 1');
  assert.equal(roundName(0), 'build');
});

test('a sync run: spec-writer and approver skipped, nothing approved by a human, no merge into the default branch', () => {
  const st = (role: string, status: string, verdict: Verdict | null = null) =>
    ({ id: role, role, round: 0, status, verdict, summary: null, model: 'm', sessionId: null, startedAt: null, endedAt: null, findings: [] }) as unknown as Stage;
  const r = {
    id: 'r', repo: 'demo-app', issueNumber: 0, issueTitle: 'Bring release/x up to date with main', issueKind: 'task', status: 'merged',
    round: 0, maxRounds: 2, kind: 'sync', baseBranch: 'release/x', syncPushedSha: 'abcdef0123456789', prNumber: null, targetRepo: null,
    conversation: { sessionId: null, review: 'none', followUpFrom: null }, createdAt: '', endedAt: '',
    stages: [st('spec-writer', 'skipped'), st('builder', 'done'), ...['security', 'ux', 'ui-design', 'code'].map((x) => st(x, 'done', 'PASS')), st('approver', 'skipped')],
  } as unknown as Run;
  const n = buildNodes(r, new Map());
  assert.equal(n['spec-writer'].state, 'skipped');
  assert.equal(n.approver.state, 'skipped');
  assert.equal(n.human.state, 'skipped');
  assert.notEqual(n.human.verdict, 'APPROVED');
  assert.equal(n.issue.title, 'sync');
  assert.equal(n.merge.title, 'push release/x');
  assert.equal(n.merge.verdict, 'UP TO DATE');
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
