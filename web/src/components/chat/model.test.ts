// Unit tests of the transcript model, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { FactoryEvent } from '../../api/types';
import { buildTranscript, fitWidth, sandboxLogOf, toolArgs, toolSummary } from './model.ts';

const exec = 'mcp__factory__factory_sandbox_exec';
const wait = 'mcp__factory__factory_sandbox_wait';
const result = (v: unknown) => ({ text: JSON.stringify(v), isError: false, images: 0 });

test('a sandbox log keeps the review mark of the review sandbox, and only a true one', () => {
  const base = { kind: 'exec', command: 'echo hi', exitCode: 0, timedOut: false, durationMs: 5, output: 'hi\n', outputTruncated: false, platform: null };
  assert.equal(sandboxLogOf({ ...base, review: true })?.review, true);
  assert.equal(sandboxLogOf(base)?.review, undefined);
  assert.equal(sandboxLogOf({ ...base, review: 'yes' })?.review, undefined);
});

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

const toolUse = (seq: number, id: string, name = wait) =>
  claude(seq, { type: 'assistant', message: { id: `m${seq}`, content: [{ type: 'tool_use', id, name, input: { handle: 'h' } }] } });
const toolResult = (seq: number, id: string) =>
  claude(seq, { type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: id, content: '{}' }] } });
const toolProgress = (fields: Record<string, unknown>) => ({ type: 'tool_progress', tool_name: wait, heartbeat: true, uuid: 'u', session_id: 'c', ...fields });
const toolItems = (items: ReturnType<typeof buildTranscript>['items']) => items.filter((i) => i.k === 'tool');

test('stored tool_progress heartbeats of older runs are not shown', () => {
  const { items } = buildTranscript([toolUse(1, 't1'), claude(2, toolProgress({ tool_use_id: 't1', elapsed_time_seconds: 5 })), toolResult(3, 't1')], []);
  assert.deepEqual(items.map((i) => i.k), ['tool']);
  assert.equal(toolItems(items)[0].progress, undefined);
});

test('a live heartbeat shows on the running tool call it names', () => {
  const events = [toolUse(1, 't1'), toolUse(2, 't2')];
  const { items } = buildTranscript(events, [toolProgress({ tool_use_id: 't1', elapsed_time_seconds: 130 })]);
  assert.deepEqual(toolItems(items).map((t) => t.progress), [{ elapsedSeconds: 130 }, undefined]);
});

test('a heartbeat without a known tool_use_id goes to the latest open tool call', () => {
  const events = [toolUse(1, 't1'), toolResult(2, 't1'), toolUse(3, 't2'), toolUse(4, 't3'), toolResult(5, 't3')];
  const { items } = buildTranscript(events, [toolProgress({ elapsed_time_seconds: 7 })]);
  assert.deepEqual(toolItems(items).map((t) => t.progress), [undefined, { elapsedSeconds: 7 }, undefined]);
  // A hook's heartbeat names the tool call as its parent.
  const hook = buildTranscript([toolUse(1, 't1'), toolUse(2, 't2')], [toolProgress({ tool_use_id: 'hook-PreToolUse-t1-heartbeat-0', parent_tool_use_id: 't1', elapsed_time_seconds: 3 })]);
  assert.deepEqual(toolItems(hook.items).map((t) => t.progress), [{ elapsedSeconds: 3 }, undefined]);
  // Missing elapsed time: still shown as running.
  const bare = buildTranscript([toolUse(1, 't1')], [{ type: 'tool_progress' }]);
  assert.deepEqual(toolItems(bare.items)[0].progress, { elapsedSeconds: null });
});

test('the heartbeat disappears once the tool result arrives', () => {
  const { items } = buildTranscript([toolUse(1, 't1'), toolResult(2, 't1')], [toolProgress({ tool_use_id: 't1', elapsed_time_seconds: 60 })]);
  assert.equal(toolItems(items)[0].progress, undefined);
  assert.equal(items.length, 1);
});

test('fitWidth fits a thumbnail into 220×160 without upscaling, keeping the aspect ratio', () => {
  assert.equal(fitWidth(2000, 1000), 220); // landscape: limited by the width
  assert.equal(fitWidth(1000, 2000), 80); // portrait: limited by the height
  assert.equal(fitWidth(32, 16), 32); // small: not upscaled
  assert.equal(fitWidth(800, 600), 213);
  assert.equal(fitWidth(1, 100000), 1); // never 0
  assert.equal(fitWidth(2000, 1000, 160, 160), 160);
});

test('fitWidth is null when the size is unknown', () => {
  for (const [w, h] of [[null, 10], [10, null], [0, 10], [10, 0], [-5, 10], [10, -1], [Number.NaN, 10], [Infinity, 10], [undefined, undefined]] as const) {
    assert.equal(fitWidth(w, h), null, `${w}×${h}`);
  }
});
