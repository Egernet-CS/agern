// Unit tests of the time and cost texts, for Node's built-in runner (`npm test`).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Cost, TotalsTime } from '../api/types';
import { amountText, barFraction, credentialNote, credentialShort, issueLabel, maxUsd, runningNote, shortAmount, tick } from './totals.ts';

const c = (over: Partial<Cost> = {}): Cost => ({
  usd: 4.31, apiKeyUsd: 0, subscriptionUsd: 4.31, sessions: 7, turns: 80, runningSessions: 0, unknownSessions: 0, ...over,
});

test('the amount is a lower bound with sessions without a cost, singular and plural', () => {
  assert.equal(amountText(c()), '$4.31');
  assert.equal(amountText(c({ unknownSessions: 1 })), '≥ $4.31 (1 session without a cost)');
  assert.equal(amountText(c({ unknownSessions: 3 })), '≥ $4.31 (3 sessions without a cost)');
  assert.equal(shortAmount(c({ unknownSessions: 3 })), '≥ $4.31');
  assert.equal(amountText(c({ usd: 0, subscriptionUsd: 0 })), '$0.00');
  assert.equal(amountText(c({ usd: 0.004 })), '$0.0040');
});

test('the credential note: subscription, API key, or both with their parts', () => {
  assert.equal(credentialNote(c()), 'API-equivalent cost reported by Claude Code (subscription), not what was billed.');
  // No cost yet counts as subscription, the weaker claim.
  assert.equal(credentialNote(c({ usd: 0, subscriptionUsd: 0 })), 'API-equivalent cost reported by Claude Code (subscription), not what was billed.');
  assert.equal(credentialNote(c({ apiKeyUsd: 4.31, subscriptionUsd: 0 })), 'API cost (API key).');
  assert.equal(
    credentialNote(c({ usd: 5.5, apiKeyUsd: 1.25, subscriptionUsd: 4.25 })),
    '$4.25 API-equivalent (subscription, not what was billed) + $1.25 API cost (API key).',
  );
  assert.equal(credentialShort(c()), 'API-equivalent');
  assert.equal(credentialShort(c({ apiKeyUsd: 4.31, subscriptionUsd: 0 })), 'API cost');
  assert.equal(credentialShort(c({ usd: 5.5, apiKeyUsd: 1.25, subscriptionUsd: 4.25 })), '$4.25 API-equivalent + $1.25 API cost');
});

test('running agents are announced, singular and plural', () => {
  assert.equal(runningNote(c()), null);
  assert.equal(runningNote(c({ runningSessions: 1 })), '1 agent running; their cost is added when they finish.');
  assert.equal(runningNote(c({ runningSessions: 2 })), '2 agents running; their cost is added when they finish.');
});

test('ticking: running and working, running and waiting, not running', () => {
  const t: TotalsTime = { elapsedMs: 60_000, workingMs: 40_000, waitingMs: 20_000, running: true, working: true };
  assert.deepEqual(tick(t, 1_000, 6_000), { ...t, elapsedMs: 65_000, workingMs: 45_000 });
  const waiting = { ...t, working: false };
  assert.deepEqual(tick(waiting, 1_000, 6_000), { ...waiting, elapsedMs: 65_000, waitingMs: 25_000 });
  const done = { ...t, running: false, working: false };
  assert.deepEqual(tick(done, 1_000, 6_000), done);
  // A clock behind the response does not count backwards.
  assert.deepEqual(tick(t, 6_000, 1_000), t);
});

test('the bar is relative to the most expensive issue, and empty when nothing cost anything', () => {
  assert.equal(barFraction(2, 4), 0.5);
  assert.equal(barFraction(4, 4), 1);
  assert.equal(barFraction(0, 0), 0);
  assert.equal(barFraction(1, 0), 0);
  assert.equal(maxUsd([{ cost: c({ usd: 1 }) }, { cost: c({ usd: 3 }) }]), 3);
  assert.equal(maxUsd([]), 0);
});

test('an issue row has a complete accessible label', () => {
  const t: TotalsTime = { elapsedMs: 72 * 60_000, workingMs: 40 * 60_000, waitingMs: 32 * 60_000, running: false, working: false };
  assert.equal(issueLabel(12, t, c(), 2), 'Issue #12: 1h 12m elapsed, 40m 00s agents working, $4.31 in 2 runs');
  assert.equal(issueLabel(12, t, c(), 1), 'Issue #12: 1h 12m elapsed, 40m 00s agents working, $4.31 in 1 run');
});
