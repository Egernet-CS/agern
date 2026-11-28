// Unit tests of the run page's sandbox use summary, for Node's built-in runner (`npm test`).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { sandboxUse } from './commands.ts';

const cmd = (command: string) => ({ command, platform: null });

test('host mode without commands says the sandbox was not used', () => {
  assert.deepEqual(sandboxUse({ build: 'host', commands: [], commandCount: 0 }), { host: true, notUsed: true, summary: null, more: 0 });
});

test('host mode with commands counts them, singular and plural', () => {
  assert.equal(sandboxUse({ build: 'host', commands: [cmd('a')], commandCount: 1 }).summary, 'sandbox used for 1 command');
  const two = sandboxUse({ build: 'host', commands: [cmd('a'), cmd('b')], commandCount: 2 });
  assert.equal(two.summary, 'sandbox used for 2 commands');
  assert.equal(two.notUsed, false);
});

test('sandbox mode lists commands but never says "not used"', () => {
  assert.equal(sandboxUse({ build: 'sandbox', commands: [cmd('a')], commandCount: 1 }).summary, '1 sandbox command');
  assert.equal(sandboxUse({ build: 'sandbox', commands: [cmd('a'), cmd('b')], commandCount: 2 }).summary, '2 sandbox commands');
  assert.deepEqual(sandboxUse({ build: 'sandbox', commands: [], commandCount: 0 }), { host: false, notUsed: false, summary: null, more: 0 });
});

test('older runs without the fields read as today', () => {
  assert.deepEqual(sandboxUse({}), { host: false, notUsed: false, summary: null, more: 0 });
});

test('commands beyond the recorded ones are counted as more', () => {
  const listed = Array.from({ length: 50 }, (_, i) => cmd(`echo ${i}`));
  assert.equal(sandboxUse({ build: 'sandbox', commands: listed, commandCount: 73 }).more, 23);
});
