// Unit tests of the transcript model, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { FactoryEvent } from '../../api/types';
import { buildTranscript, deployLogOf, fitWidth, sandboxLogOf, toolArgs, toolSummary } from './model.ts';

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

const system = (seq: number, payload: unknown) => ({ seq, sessionId: 's', ts: '2026-01-01T00:00:00Z', type: 'system', payload }) as FactoryEvent;

test('a deploy command in a system event is parsed into its log', () => {
  const deploy = {
    kind: 'exec', target: 'kiosk', host: 'kiosk.example.net', port: 22, user: 'deploy', command: 'systemctl restart app',
    exitCode: 0, timedOut: false, durationMs: 1200, output: 'ok\n', outputTruncated: false, error: null,
  };
  const { items } = buildTranscript([system(1, { level: 'info', text: 'Deploy [kiosk] `systemctl restart app`: exit 0 in 1.2 s', deploy })], []);
  assert.equal(items.length, 1);
  const item = items[0];
  assert.ok(item.k === 'system');
  assert.deepEqual(item.deploy, { ...deploy, path: undefined, dest: undefined, bytes: undefined });
  assert.equal(item.sandbox, undefined);
});

test('deploy copies and failures keep their fields; a missing exit code is null', () => {
  const copy = deployLogOf({ kind: 'copy', target: 'kiosk', host: 'h', port: 2222, user: 'u', path: 'dist/app.tar.gz', dest: '/opt/app/', bytes: 1234, exitCode: null, timedOut: false, durationMs: 800, output: '', outputTruncated: false, error: null });
  assert.equal(copy?.kind, 'copy');
  assert.equal(copy?.path, 'dist/app.tar.gz');
  assert.equal(copy?.dest, '/opt/app/');
  assert.equal(copy?.bytes, 1234);
  assert.equal(copy?.exitCode, null);
  const failed = deployLogOf({ kind: 'exec', target: 'kiosk', host: 'h', port: 22, user: 'u', command: 'true', exitCode: null, timedOut: false, durationMs: 15000, output: '', outputTruncated: false, error: 'could not connect: timed out' });
  assert.equal(failed?.error, 'could not connect: timed out');
  assert.equal(failed?.exitCode, null);
});

test('malformed deploy payloads are ignored', () => {
  assert.equal(deployLogOf(undefined), undefined);
  assert.equal(deployLogOf('exec'), undefined);
  assert.equal(deployLogOf({ kind: 'exec', target: 'kiosk' }), undefined, 'an exec without command');
  assert.equal(deployLogOf({ kind: 'copy', target: 'kiosk', path: 'a' }), undefined, 'a copy without dest');
  assert.equal(deployLogOf({ kind: 'shell', target: 'kiosk', command: 'x' }), undefined, 'an unknown kind');
  assert.equal(deployLogOf({ kind: 'exec', target: 7, command: 'x' }), undefined, 'a target that is not a string');
  // Wrong field types fall back to safe values.
  const odd = deployLogOf({ kind: 'exec', target: 'kiosk', command: 'x', exitCode: 'zero', port: 'ssh', output: 5, timedOut: 'yes' });
  assert.equal(odd?.exitCode, null);
  assert.equal(odd?.port, 22);
  assert.equal(odd?.output, '');
  assert.equal(odd?.timedOut, false);
});

test('deploy tool calls are summarised by target, command or copy', () => {
  const dexec = 'mcp__factory__factory_deploy_exec';
  const dcopy = 'mcp__factory__factory_deploy_copy';
  assert.equal(toolArgs(dexec, { target: 'kiosk', command: 'systemctl restart app' }), 'kiosk: systemctl restart app');
  assert.equal(toolArgs(dcopy, { target: 'kiosk', path: 'dist/app.tar.gz', dest: '/opt/app/' }), 'kiosk: dist/app.tar.gz → /opt/app/');
  assert.equal(toolSummary(dexec, result({ target: 'kiosk', exitCode: 3, output: '', truncated: false, durationMs: 1500, timedOut: false })), 'Exit 3 in 1.5 s · full log below');
  assert.equal(toolSummary(dexec, result({ target: 'kiosk', exitCode: -1, output: '', truncated: false, durationMs: 5000, timedOut: true })), 'Timed out in 5.0 s · full log below');
  assert.equal(toolSummary(dcopy, result({ target: 'kiosk', dest: '/opt/app/app.tar.gz', bytes: 2048, durationMs: 800 })), 'Copied 2,048 bytes in 0.8 s');
  assert.equal(toolSummary(dexec, { text: 'Deploy targets are not approved', isError: true, images: 0 }), null);
});
