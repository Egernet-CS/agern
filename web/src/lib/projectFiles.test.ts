// Unit tests of the project file rules, for Node's built-in runner (`npm test`).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { ProjectFiles } from '../api/types';
import { CHUNK_BYTES, chunkPlan, fileNameProblem, limitProblem, nextChunk, normalizeSha256, retryDelayMs } from './projectFiles.ts';

test('valid names: plain, nested, with the allowed punctuation', () => {
  for (const n of ['sdk.zip', 'vendor/acme-sdk-4.2.tar.gz', '_x', 'a/b/c/d/e/f/g/h', 'lib+extra@2', 'A'.repeat(100), `${'a'.repeat(99)}/${'b'.repeat(100)}`]) {
    assert.equal(fileNameProblem(n), null, n);
  }
});

test('invalid names name the rule', () => {
  const bad = ['', '../x', '/abs', 'a//b', 'a/./b', '.hidden', '-x', 'a b', 'a\\b', 'x/', 'a'.repeat(101), 'a'.repeat(201), 'a/b/c/d/e/f/g/h/i', 'ä'];
  for (const n of bad) {
    const p = fileNameProblem(n);
    assert.ok(p, `${JSON.stringify(n)} should be invalid`);
    assert.match(p, /1–200 characters in at most 8 parts/);
  }
  assert.match(fileNameProblem('a'.repeat(201))!, /longer than 200/);
  assert.match(fileNameProblem('a/b/c/d/e/f/g/h/i')!, /more than 8 parts/);
  assert.match(fileNameProblem('a//b')!, /empty part/);
  assert.match(fileNameProblem('../x')!, /“\.\.”/);
});

test('a name of exactly 200 characters is allowed, 201 is not', () => {
  const n200 = `${'a'.repeat(100)}/${'b'.repeat(99)}`;
  assert.equal(n200.length, 200);
  assert.equal(fileNameProblem(n200), null);
  assert.ok(fileNameProblem(`${n200}c`));
});

test('chunks of 8 MiB from the received offset', () => {
  assert.equal(CHUNK_BYTES, 8 * 1024 * 1024);
  assert.deepEqual(nextChunk(100, 0), [0, 100]);
  assert.deepEqual(nextChunk(100, 100), null);
  assert.deepEqual(nextChunk(0, 0), null);
  const size = 20 * 1024 * 1024;
  assert.deepEqual(chunkPlan(size, 0), [
    [0, CHUNK_BYTES],
    [CHUNK_BYTES, 2 * CHUNK_BYTES],
    [2 * CHUNK_BYTES, size],
  ]);
  // Resuming in the middle of a chunk continues from exactly there.
  assert.deepEqual(chunkPlan(size, 5), [
    [5, 5 + CHUNK_BYTES],
    [5 + CHUNK_BYTES, 5 + 2 * CHUNK_BYTES],
    [5 + 2 * CHUNK_BYTES, size],
  ]);
  assert.deepEqual(chunkPlan(size, size), []);
  assert.deepEqual(chunkPlan(10, 0, 4), [[0, 4], [4, 8], [8, 10]]);
});

test('back-off doubles and stops growing', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6].map(retryDelayMs), [1000, 2000, 4000, 8000, 16000, 16000]);
});

test('expected SHA-256 is 64 hex digits', () => {
  const h = 'AB'.repeat(32);
  assert.equal(normalizeSha256(` ${h} `), h.toLowerCase());
  assert.equal(normalizeSha256('abc'), null);
  assert.equal(normalizeSha256('g'.repeat(64)), null);
});

test('limits are checked before the first request; replacing frees the old bytes', () => {
  const pf: Pick<ProjectFiles, 'files' | 'usage' | 'limits'> = {
    files: [{ id: '1', name: 'a.bin', size: 600, sha256: 'x', version: 1, note: null, downloadable: false, uploadedBy: null, uploadedAt: '' }],
    usage: { files: 1, projectBytes: 600, totalBytes: 900 },
    limits: { maxFileBytes: 500, maxProjectBytes: 1000, maxTotalBytes: 1200, maxFiles: 1 },
  };
  assert.match(limitProblem(pf, 'b.bin', 0)!, /empty/);
  assert.match(limitProblem(pf, 'a.bin', 501)!, /at most 500 B/);
  assert.match(limitProblem(pf, 'b.bin', 10)!, /already has 1 file/);
  assert.equal(limitProblem(pf, 'a.bin', 500), null);
  const roomy = { ...pf, limits: { ...pf.limits, maxFiles: 5, maxFileBytes: 1000 } };
  assert.match(limitProblem(roomy, 'b.bin', 401)!, /in this project/);
  assert.equal(limitProblem(roomy, 'b.bin', 300), null);
  assert.match(limitProblem({ ...roomy, usage: { ...roomy.usage, totalBytes: 1100 } }, 'b.bin', 300)!, /on this server/);
});
