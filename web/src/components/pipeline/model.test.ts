// Unit tests of the pipeline model, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Run, RunUpdate, Stage, Verdict } from '../../api/types';
import { approverEscalation, isFollowUp, roundName } from './model.ts';

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
