// Unit tests of the GitHub status texts and banner rules, for Node's built-in runner (`npm test`).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  bannerText,
  bannerVisible,
  componentStatusWords,
  menuLabel,
  nextDismissed,
  otherTokensSentence,
  rateLimitSentence,
  sortComponents,
  verdictExplanation,
  verdictGlyph,
  verdictHeading,
} from './githubStatus.ts';

const now = Date.parse('2026-11-25T16:00:00Z');
const inMin = (m: number) => new Date(now + m * 60_000).toISOString();

test('verdict heading, explanation and glyph per state', () => {
  assert.equal(verdictHeading('working'), 'GitHub is working');
  assert.equal(verdictHeading('degraded'), 'GitHub is degraded for Agern');
  assert.equal(verdictHeading('down'), 'GitHub is down for Agern');
  assert.equal(verdictExplanation('working'), 'Runs, chats and releases can use GitHub normally.');
  assert.match(verdictExplanation('degraded'), /^Some GitHub calls are failing\. Runs and releases wait and retry by themselves;/);
  assert.match(verdictExplanation('down'), /^Agern cannot reach GitHub right now\./);
  assert.equal(new Set(['working', 'degraded', 'down'].map((s) => verdictGlyph(s as 'working'))).size, 3);
  assert.equal(bannerText('down'), 'GitHub is down for Agern.');
});

test('menu label carries the state when GitHub is not working', () => {
  assert.equal(menuLabel(undefined), 'GitHub status');
  assert.equal(menuLabel('working'), 'GitHub status');
  assert.equal(menuLabel('degraded'), 'GitHub status — degraded');
  assert.equal(menuLabel('down'), 'GitHub status — down');
});

test('banner visibility and dismissal', () => {
  assert.equal(bannerVisible('working', '/', null), false);
  assert.equal(bannerVisible(undefined, '/', null), false);
  assert.equal(bannerVisible('degraded', '/', null), true);
  assert.equal(bannerVisible('down', '/p/app', null), true);
  assert.equal(bannerVisible('down', '/github-status', null), false);
  // Dismissed for degraded: hidden while degraded, shown again when it gets worse.
  assert.equal(bannerVisible('degraded', '/', 'degraded'), false);
  assert.equal(bannerVisible('down', '/', 'degraded'), true);
  assert.equal(bannerVisible('degraded', '/', 'down'), true);
  // A working poll clears the dismissal; other states keep it.
  assert.equal(nextDismissed('working', 'degraded'), null);
  assert.equal(nextDismissed('degraded', 'degraded'), 'degraded');
  assert.equal(nextDismissed('down', 'degraded'), 'degraded');
});

test('rate-limit sentences', () => {
  assert.equal(rateLimitSentence(null, now), 'unknown');
  assert.equal(rateLimitSentence({ limit: 5000, remaining: 4987, resetAt: inMin(42), observedAt: inMin(0) }, now), '4987 of 5000 left, resets in 42m');
  assert.equal(rateLimitSentence({ limit: 5000, remaining: 0, resetAt: inMin(-3), observedAt: inMin(-5) }, now), '0 of 5000 left, resets now');
  assert.equal(otherTokensSentence(0, null, now), 'No other tokens have been used recently.');
  assert.equal(
    otherTokensSentence(2, { limit: 5000, remaining: 3120, resetAt: inMin(120), observedAt: inMin(0) }, now),
    '2 other tokens; the lowest has 3120 of 5000 REST calls left (resets in 2h).',
  );
  assert.match(otherTokensSentence(1, { limit: 60, remaining: 1, resetAt: inMin(5), observedAt: inMin(0) }, now), /^1 other token;/);
});

test('component wording and order', () => {
  assert.equal(componentStatusWords('degraded_performance'), 'degraded performance');
  assert.equal(componentStatusWords('major_outage'), 'major outage');
  const sorted = sortComponents([
    { name: 'Pages', relevant: false },
    { name: 'Issues', relevant: true },
    { name: 'Copilot', relevant: false },
    { name: 'Actions', relevant: true },
  ]);
  assert.deepEqual(sorted.map((c) => c.name), ['Issues', 'Actions', 'Pages', 'Copilot']);
});
