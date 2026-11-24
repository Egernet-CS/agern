// Unit tests of the tool summaries, for Node's built-in runner (`npm test`; Node 22.18+ runs TypeScript as is).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { toolArgs, toolSummary } from './model.ts';

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
