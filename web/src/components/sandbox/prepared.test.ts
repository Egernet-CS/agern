// Unit tests of the prepared image and startup texts, for Node's built-in runner (`npm test`).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { diskSize, seconds, startupNote } from './prepared.ts';

test('disk sizes in binary units', () => {
  assert.equal(diskSize(512 * 1024), '512 KB');
  assert.equal(diskSize(420 * 1024 * 1024), '420 MB');
  assert.equal(diskSize(1.25 * 1024 * 1024 * 1024), '1.3 GB');
});

test('seconds keep one decimal only for short times', () => {
  assert.equal(seconds(4200), '4.2 s');
  assert.equal(seconds(83_400), '83 s');
});

test('the startup note says how long the builder waited and how the sandbox was made', () => {
  assert.deepEqual(startupNote({ firstCommandMs: 12_000, createMs: 3400, startedFrom: 'reused', preparedKey: '0123456789abcdef' }), {
    text: 'first command after 12 s',
    title: "Time from the builder's start to its first sandbox command; creating the sandbox took 3.4 s (from prepared image 0123456789abcdef)",
  });
  assert.equal(
    startupNote({ firstCommandMs: 200_000, createMs: 150_000, startedFrom: 'prepared', preparedKey: 'abc' })!.title,
    "Time from the builder's start to its first sandbox command; creating the sandbox took 150 s (setup ran, prepared image abc stored)",
  );
  assert.match(startupNote({ firstCommandMs: 1, createMs: 1, startedFrom: 'setup', preparedKey: null })!.title, /\(setup ran\)$/);
  assert.match(startupNote({ firstCommandMs: 1, createMs: 1, startedFrom: 'plain', preparedKey: null })!.title, /\(no setup script\)$/);
});

test('older runs show nothing', () => {
  assert.equal(startupNote({}), null);
  assert.equal(startupNote({ firstCommandMs: null, createMs: 10, startedFrom: 'reused', preparedKey: 'k' }), null);
});
