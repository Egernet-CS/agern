// Pure rules of the project files (names, chunks, limits), shared by the Files page and the mock. Must match the server.
import type { ProjectFile, ProjectFiles } from '../api/types';
import { bytes } from './format.ts';

/** Uploads go in chunks of 8 MiB. */
export const CHUNK_BYTES = 8 * 1024 * 1024;
/** A failed chunk is tried again this many times (each after reading `received` from the server). */
export const CHUNK_RETRIES = 5;

export const MAX_NAME_LENGTH = 200;
export const MAX_NAME_SEGMENTS = 8;
const SEGMENT = /^[A-Za-z0-9_][A-Za-z0-9._+@-]{0,99}$/;

export const NAME_RULE =
  'Use 1–200 characters in at most 8 parts separated by “/”; each part starts with a letter, digit or “_” and has at most 100 letters, digits or “._+@-”.';

/** Why [name] is not a valid project file name, or null when it is. */
export function fileNameProblem(name: string): string | null {
  if (name.length === 0) return `The name is empty. ${NAME_RULE}`;
  if (name.length > MAX_NAME_LENGTH) return `The name is longer than ${MAX_NAME_LENGTH} characters. ${NAME_RULE}`;
  const segments = name.split('/');
  if (segments.length > MAX_NAME_SEGMENTS) return `The name has more than ${MAX_NAME_SEGMENTS} parts. ${NAME_RULE}`;
  for (const s of segments) {
    if (s === '') return `The name has an empty part (a leading, trailing or double “/”). ${NAME_RULE}`;
    if (!SEGMENT.test(s)) return `“${s}” is not allowed as a part of the name. ${NAME_RULE}`;
  }
  return null;
}

/** The byte range [start, end) of the next chunk to send, or null when everything was received. */
export function nextChunk(size: number, received: number, chunk = CHUNK_BYTES): [number, number] | null {
  const start = Math.max(0, received);
  if (start >= size) return null;
  return [start, Math.min(size, start + chunk)];
}

/** Every chunk still to send, in order. */
export function chunkPlan(size: number, received: number, chunk = CHUNK_BYTES): [number, number][] {
  const out: [number, number][] = [];
  for (let r = nextChunk(size, received, chunk); r; r = nextChunk(size, r[1], chunk)) out.push(r);
  return out;
}

/** Back-off before retry [attempt] (1-based): 1 s, 2 s, 4 s, 8 s, 16 s. */
export const retryDelayMs = (attempt: number) => 1000 * 2 ** Math.max(0, Math.min(attempt - 1, 4));

/** An expected SHA-256 as typed (hex, any case, surrounding spaces ignored); null when it is not one. */
export function normalizeSha256(text: string): string | null {
  const t = text.trim().toLowerCase();
  return /^[0-9a-f]{64}$/.test(t) ? t : null;
}

export const shortSha = (sha: string) => sha.slice(0, 12);

/** "1 file", "3 files". */
export const filesCount = (n: number) => `${n} file${n === 1 ? '' : 's'}`;

/**
 * Why a file of [size] bytes cannot be uploaded as [name] under the project's limits (the server checks again), or null.
 * Uploading under an existing name replaces that file: its bytes no longer count and the number of files stays.
 */
export function limitProblem(pf: Pick<ProjectFiles, 'files' | 'usage' | 'limits'>, name: string, size: number): string | null {
  const { limits, usage } = pf;
  const replaced: ProjectFile | undefined = pf.files.find((f) => f.name === name);
  const freed = replaced?.size ?? 0;
  if (size === 0) return 'The file is empty.';
  if (size > limits.maxFileBytes) return `The file is ${bytes(size)}; a file may be at most ${bytes(limits.maxFileBytes)}.`;
  if (!replaced && pf.files.length >= limits.maxFiles) return `This project already has ${filesCount(pf.files.length)}, the most allowed.`;
  if (usage.projectBytes - freed + size > limits.maxProjectBytes)
    return `Not enough room in this project: ${bytes(usage.projectBytes)} of ${bytes(limits.maxProjectBytes)} used, the file is ${bytes(size)}.`;
  if (usage.totalBytes - freed + size > limits.maxTotalBytes)
    return `Not enough room on this server: ${bytes(usage.totalBytes)} of ${bytes(limits.maxTotalBytes)} used, the file is ${bytes(size)}.`;
  return null;
}
