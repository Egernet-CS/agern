// Unit tests of the network-wait texts, for Node's built-in runner (`npm test`).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Release, ReleaseIssue, RunWait } from '../api/types';
import { issueWaitNote, networkBlockedIssue, releaseNetworkLine, runningWaitLine, shortClock } from './networkWait.ts';

const at = (h: number, m: number, day = 26) => new Date(2026, 10, day, h, m).toISOString();
const now = new Date(2026, 10, 26, 15, 0).getTime();

test('times on the same day show the clock only, other days the date too', () => {
  assert.equal(shortClock(at(14, 5), now), '14:05');
  assert.equal(shortClock(at(9, 30, 25), now), '25 Nov 09:30');
});

test('a release paused for the network names since, the note and the next try', () => {
  const r = {
    status: 'paused',
    pause: { reason: 'network', note: 'Could not start #12: ConnectException', resumeAt: at(15, 5), since: at(14, 50), attempt: 3 },
  } as Pick<Release, 'status' | 'pause'>;
  assert.equal(releaseNetworkLine(r, now), 'Waiting for the network since 14:50. Could not start #12: ConnectException. Next try at 15:05.');
  assert.equal(releaseNetworkLine({ ...r, pause: { ...r.pause!, reason: 'manual' } }, now), null);
  assert.equal(releaseNetworkLine({ ...r, status: 'building' }, now), null);
});

test('a running issue whose run waits', () => {
  const waiting: RunWait = { reason: 'network', since: at(14, 0), nextAttemptAt: at(15, 10), attempt: 2, detail: 'x' };
  const i = { number: 7, title: 'Dark mode', state: 'running', waiting } as ReleaseIssue;
  assert.equal(runningWaitLine(i, now), '#7 Dark mode is waiting for the network since 14:00; next try at 15:10.');
  assert.equal(issueWaitNote(i, now), 'Waiting for the network since 14:00; next try at 15:10.');
  assert.equal(runningWaitLine({ ...i, waiting: null }, now), null);
  assert.equal(issueWaitNote({ ...i, state: 'merged' }, now), null);
});

test('the issue a network pause blocks is the first waiting one', () => {
  const issues = [{ number: 1, state: 'merged' }, { number: 2, state: 'waiting' }, { number: 3, state: 'waiting' }] as ReleaseIssue[];
  const r = { status: 'paused', pause: { reason: 'network', note: null, resumeAt: null }, issues } as Pick<Release, 'status' | 'pause' | 'issues'>;
  assert.equal(networkBlockedIssue(r)?.number, 2);
  assert.equal(networkBlockedIssue({ ...r, pause: { reason: 'manual', note: null, resumeAt: null } }), null);
});
