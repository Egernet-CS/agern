// Unit tests of the transcript model, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { FactoryEvent } from '../../api/types';
import { buildTranscript, toolArgs, toolSummary } from './model.ts';

const exec = 'mcp__factory__factory_sandbox_exec';
const wait = 'mcp__factory__factory_sandbox_wait';
const result = (v: unknown) => ({ text: JSON.stringify(v), isError: false, images: 0 });

test('factory_sandbox_wait arguments show the handle, or the latest command', () => {
  assert.equal(toolArgs(wait, { handle: 'cmd-1a2b3c4d', waitSeconds: 60 }), 'cmd-1a2b3c4d');
  assert.equal(toolArgs(wait, {}), 'latest command');
  assert.equal(toolArgs(wait, { handle: '  ' }), 'latest command');
});

test('a background start and a wait that ran out are summarised as running', () => {
  assert.equal(toolSummary(exec, result({ handle: 'cmd-1a2b3c4d', running: true, timeoutSeconds: 1800 })), 'Started in the background');
  assert.equal(toolSummary(wait, result({ handle: 'cmd-1a2b3c4d', running: true, elapsedMs: 61_400 })), 'Still running after 61 s');
});

test('finished commands keep the exit summary, for exec and wait alike', () => {
  const done = { handle: 'cmd-1a2b3c4d', running: false, exitCode: 0, output: 'ok', truncated: false, durationMs: 2500, timedOut: false };
  assert.equal(toolSummary(wait, result(done)), 'Exit 0 in 2.5 s · full log below');
  assert.equal(toolSummary(exec, result({ ...done, running: undefined, exitCode: 3 })), 'Exit 3 in 2.5 s · full log below');
  assert.equal(toolSummary(wait, result({ ...done, exitCode: -1, timedOut: true, durationMs: 5000 })), 'Timed out in 5.0 s · full log below');
});

test('errors and non-JSON answers are not summarised', () => {
  assert.equal(toolSummary(wait, { text: 'Unknown handle', isError: true, images: 0 }), null);
  assert.equal(toolSummary(wait, { text: 'not json', isError: false, images: 0 }), null);
});

const thinkingTokens = (n: number) => ({ type: 'system', subtype: 'thinking_tokens', estimated_tokens: n, estimated_tokens_delta: 100, session_id: 'c' });
const claude = (seq: number, payload: unknown) => ({ seq, sessionId: 's', ts: '2026-01-01T00:00:00Z', type: 'claude', payload }) as FactoryEvent;

test('stored thinking_tokens events of older runs are not shown', () => {
  const { items, summary } = buildTranscript([claude(1, thinkingTokens(100)), claude(2, thinkingTokens(200)), claude(3, { type: 'system', subtype: 'other_thing' })], []);
  assert.deepEqual(items.map((i) => (i.k === 'unknown' ? i.label : i.k)), ['system.other_thing']);
  assert.equal(summary.thinkingTokens, null);
});

test('the latest live thinking_tokens estimate is in the summary, not in the items', () => {
  const partials = [{ type: 'stream_event', event: { type: 'message_start', message: { id: 'm1' } } }, thinkingTokens(4050), thinkingTokens(5800)];
  const { items, summary } = buildTranscript([], partials);
  assert.equal(items.length, 0);
  assert.equal(summary.thinkingTokens, 5800);
});
