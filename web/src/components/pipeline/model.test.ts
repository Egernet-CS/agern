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
