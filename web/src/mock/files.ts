// Project files in the mock: per-project file stores, managers, resumable uploads (chunks are kept as Blobs and hashed
// with SubtleCrypto on completion), the admin overview, a run that got files and the sandbox definition's missing files,
// all in memory. Demo: web-shop has files, a manager (carl) and an interrupted upload; billing-service has none.
// Upload names containing "flaky" lose every chunk (retries, then "Upload interrupted"); "corrupt" fails the checksum.
import { ApiError } from '../api/api';
import type { AdminProjectFiles, Owner, ProjectFile, ProjectFiles, ProjectFilesLimits, ProjectFileUpload, RunProjectFiles, SandboxStatus, User } from '../api/types';
import { fileNameProblem } from '../lib/projectFiles';
import { bytes } from '../lib/format';
import { db, MockError } from './engine';

type MockFile = ProjectFile & { url: string };
type MockUpload = ProjectFileUpload & { parts: Blob[]; busy: boolean };
type Store = { files: MockFile[]; managers: string[]; uploads: MockUpload[] };

const MiB = 1024 * 1024;
const LIMITS: ProjectFilesLimits = { maxFileBytes: 2048 * MiB, maxProjectBytes: 5120 * MiB, maxTotalBytes: 51200 * MiB, maxFiles: 100 };
/** Files of projects the mock does not list, so the server-wide total is not just the demo's. */
const OTHER_BYTES = 3_400 * MiB;

const ownerOf = (u: User): Owner => ({ username: u.username, displayName: u.displayName });
const ownerNamed = (username: string): Owner | null => {
  const u = db.users.find((x) => x.username === username);
  return u ? ownerOf(u) : null;
};
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const later = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** A stable made-up SHA-256 for seeded files (the mock has no bytes for them). */
function fakeSha(seed: string): string {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) h = Math.imul(h ^ seed.charCodeAt(i), 16777619) >>> 0;
  let out = '';
  for (let i = 0; out.length < 64; i++) {
    h = Math.imul(h ^ (i + 0x9e37), 16777619) >>> 0;
    out += h.toString(16).padStart(8, '0');
  }
  return out.slice(0, 64);
}

const textUrl = (text: string) => `data:text/plain;charset=utf-8,${encodeURIComponent(text)}`;

const stores = new Map<string, Store>();
function store(repo: string): Store {
  let s = stores.get(repo);
  if (!s) stores.set(repo, (s = { files: [], managers: [], uploads: [] }));
  return s;
}

function seedFile(repo: string, name: string, size: number, version: number, by: string, minutesAgo: number, note: string | null, downloadable = false) {
  store(repo).files.push({
    id: crypto.randomUUID(),
    name,
    size,
    sha256: fakeSha(`${repo}/${name}/${version}`),
    version,
    note,
    downloadable,
    uploadedBy: ownerNamed(by),
    uploadedAt: ago(minutesAgo),
    url: textUrl(`Mock content of ${name} (v${version})\n`),
  });
}

seedFile('web-shop', 'vendor/acme-pay-sdk-4.2.tar.gz', 50_646_221, 3, 'alice', 60 * 24 * 5, 'From the Acme partner portal; the licence forbids redistribution.');
seedFile('web-shop', 'certs/test-ca.pem', 1_944, 1, 'carl', 60 * 24 * 12, 'Test CA of the staging payment gateway', true);
seedFile('web-shop', 'fonts/brand-sans.woff2', 214_380, 2, 'alice', 60 * 30, null);
store('web-shop').managers.push('carl');
store('web-shop').uploads.push({
  id: crypto.randomUUID(),
  name: 'vendor/ios-scanner-sdk.zip',
  size: 190_840_832,
  received: 64 * MiB,
  sha256: null,
  note: 'Scanner SDK 7.1 for the iOS companion build',
  createdBy: ownerNamed('alice'),
  createdAt: ago(60 * 3 + 5),
  touchedAt: ago(60 * 3),
  expiresAt: later(60 * 21),
  replaces: null,
  // The mock keeps no bytes for the part sent before (its hash on completion covers the rest only).
  parts: [],
  busy: false,
});
seedFile('data-pipeline', 'models/classifier-v7.onnx', 327_155_712, 7, 'alice', 60 * 24 * 2, 'Exported from the training cluster; 7th retrain.');
store('billing-service');

/** web-shop's sandbox needs the SDK and a licence file that nobody uploaded yet. */
export const SANDBOX_FILES: Record<string, string[]> = { 'web-shop': ['vendor/acme-pay-sdk-4.2.tar.gz', 'licenses/acme-pay.lic'] };

const used = (s: Store) => s.files.reduce((n, f) => n + f.size, 0);
const totalUsed = () => OTHER_BYTES + [...stores.values()].reduce((n, s) => n + used(s), 0);
const canManage = (repo: string, me: User) => me.isAdmin || store(repo).managers.includes(me.username);
function needManager(repo: string, me: User) {
  if (!canManage(repo, me)) throw new MockError(403, 'forbidden', 'Only admins and the project’s file managers can change its files');
}
const plain = ({ url: _u, ...f }: MockFile): ProjectFile => f;
const plainUpload = ({ parts: _p, busy: _b, ...u }: MockUpload): ProjectFileUpload => u;

export function projectFiles(repo: string, me: User): ProjectFiles {
  const s = store(repo);
  const manage = canManage(repo, me);
  return {
    repo,
    canManage: manage,
    files: [...s.files].sort((a, b) => a.name.localeCompare(b.name)).map(plain),
    managers: s.managers.map((m) => ownerNamed(m)).filter((o): o is Owner => !!o),
    usage: { files: s.files.length, projectBytes: used(s), totalBytes: totalUsed() },
    limits: LIMITS,
    uploads: manage ? s.uploads.map(plainUpload) : [],
  };
}

function upload(repo: string, id: string): MockUpload {
  const u = store(repo).uploads.find((x) => x.id === id);
  if (!u) throw new MockError(404, 'not_found', 'No such upload (it was cancelled, completed or has expired)');
  return u;
}

export function createUpload(repo: string, body: { name: string; size: number; sha256?: string; note?: string }, me: User): ProjectFileUpload {
  needManager(repo, me);
  const s = store(repo);
  const problem = fileNameProblem(body.name);
  if (problem) throw new MockError(400, 'invalid_file_name', problem);
  const sha = body.sha256?.toLowerCase() ?? null;
  const open = s.uploads.find((u) => u.name === body.name && u.size === body.size && u.sha256 === sha && u.createdBy?.username === me.username);
  if (open) return plainUpload(open);
  if (s.uploads.some((u) => u.name === body.name)) throw new MockError(409, 'name_conflict', `Another upload of ${body.name} is in progress`);
  if (body.size <= 0) throw new MockError(422, 'empty_file', 'The file is empty');
  if (body.size > LIMITS.maxFileBytes) throw new MockError(413, 'file_too_large', `A file may be at most ${bytes(LIMITS.maxFileBytes)}`);
  const replaced = s.files.find((f) => f.name === body.name);
  if (!replaced && s.files.length >= LIMITS.maxFiles) throw new MockError(409, 'too_many_files', `A project may have at most ${LIMITS.maxFiles} files`);
  if (used(s) - (replaced?.size ?? 0) + body.size > LIMITS.maxProjectBytes) throw new MockError(413, 'project_quota_exceeded', `The project’s files may total at most ${bytes(LIMITS.maxProjectBytes)}`);
  if (totalUsed() - (replaced?.size ?? 0) + body.size > LIMITS.maxTotalBytes) throw new MockError(413, 'total_quota_exceeded', `All files on this server may total at most ${bytes(LIMITS.maxTotalBytes)}`);
  const u: MockUpload = {
    id: crypto.randomUUID(),
    name: body.name,
    size: body.size,
    received: 0,
    sha256: sha,
    note: body.note?.trim() || null,
    createdBy: ownerOf(me),
    createdAt: new Date().toISOString(),
    touchedAt: new Date().toISOString(),
    expiresAt: later(60 * 24),
    replaces: replaced?.id ?? null,
    parts: [],
    busy: false,
  };
  s.uploads.push(u);
  return plainUpload(u);
}

export function getUpload(repo: string, id: string, me: User): ProjectFileUpload {
  needManager(repo, me);
  return plainUpload(upload(repo, id));
}

const asApi = (e: unknown) => (e instanceof MockError ? new ApiError(e.status, e.code, e.message) : e);

/** Simulated progress in ten steps; "flaky" uploads lose the connection half way through every chunk. */
export async function putChunk(
  repo: string,
  id: string,
  offset: number,
  chunk: Blob,
  me: User,
  opts?: { onProgress?: (loaded: number, total: number) => void; signal?: AbortSignal },
): Promise<ProjectFileUpload> {
  let u: MockUpload;
  try {
    needManager(repo, me);
    u = upload(repo, id);
    if (u.busy) throw new MockError(409, 'upload_busy', 'Another request is writing to this upload');
    if (offset !== u.received) throw new MockError(409, 'upload_offset_mismatch', `The upload has ${u.received} bytes; send from there`);
    if (offset + chunk.size > u.size) throw new MockError(400, 'upload_too_long', 'More bytes than the upload’s size');
  } catch (e) {
    throw asApi(e);
  }
  u.busy = true;
  try {
    for (let i = 1; i <= 10; i++) {
      if (opts?.signal?.aborted) throw new ApiError(0, 'aborted', 'Upload cancelled');
      await wait(120);
      if (u.name.includes('flaky') && i === 5) throw new ApiError(0, 'network', 'The upload did not reach the factory server.');
      opts?.onProgress?.(Math.round((chunk.size * i) / 10), chunk.size);
    }
    u.parts.push(chunk);
    u.received += chunk.size;
    u.touchedAt = new Date().toISOString();
    return structuredClone(plainUpload(u));
  } finally {
    u.busy = false;
  }
}

async function sha256Of(blob: Blob): Promise<string> {
  try {
    const d = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
    return Array.from(new Uint8Array(d), (b) => b.toString(16).padStart(2, '0')).join('');
  } catch {
    // No SubtleCrypto outside a secure context (plain HTTP on a LAN): a made-up hash.
    return fakeSha(`${blob.size}-${Date.now()}`);
  }
}

export async function completeUpload(repo: string, id: string, me: User): Promise<ProjectFile> {
  try {
    needManager(repo, me);
    const u = upload(repo, id);
    if (u.received < u.size) throw new MockError(409, 'upload_incomplete', `The upload has ${u.received} of ${u.size} bytes`);
    await wait(400);
    const blob = new Blob(u.parts);
    const sha = await sha256Of(blob);
    const s = store(repo);
    if (u.name.includes('corrupt') || (u.sha256 && u.sha256 !== sha)) {
      s.uploads = s.uploads.filter((x) => x.id !== id);
      throw new MockError(422, 'checksum_mismatch', `The file’s SHA-256 is ${sha}, not the expected ${u.sha256 ?? fakeSha('expected')}; the upload was discarded`);
    }
    s.uploads = s.uploads.filter((x) => x.id !== id);
    const old = s.files.find((f) => f.name === u.name);
    const file: MockFile = {
      id: old?.id ?? crypto.randomUUID(),
      name: u.name,
      size: u.size,
      sha256: sha,
      version: (old?.version ?? 0) + 1,
      note: u.note ?? old?.note ?? null,
      downloadable: old?.downloadable ?? false,
      uploadedBy: ownerOf(me),
      uploadedAt: new Date().toISOString(),
      url: URL.createObjectURL(blob),
    };
    if (old) s.files = s.files.map((f) => (f.id === old.id ? file : f));
    else s.files.push(file);
    return plain(file);
  } catch (e) {
    throw asApi(e);
  }
}

export function cancelUpload(repo: string, id: string, me: User) {
  needManager(repo, me);
  upload(repo, id);
  const s = store(repo);
  s.uploads = s.uploads.filter((x) => x.id !== id);
}

function file(repo: string, id: string): MockFile {
  const f = store(repo).files.find((x) => x.id === id);
  if (!f) throw new MockError(404, 'not_found', 'No such file');
  return f;
}

export function updateFile(repo: string, id: string, body: { note?: string; downloadable?: boolean }, me: User): ProjectFile {
  needManager(repo, me);
  const f = file(repo, id);
  if (body.note !== undefined) f.note = body.note.trim() || null;
  if (body.downloadable !== undefined) f.downloadable = body.downloadable;
  return plain(f);
}

export function deleteFile(repo: string, id: string, me: User) {
  needManager(repo, me);
  const f = file(repo, id);
  const s = store(repo);
  s.files = s.files.filter((x) => x.id !== f.id);
}

/** Like the server: only a downloadable file has a URL that answers (the mock's is a blob or data URL). */
export function fileUrl(repo: string, id: string): string {
  const f = store(repo).files.find((x) => x.id === id);
  return f?.downloadable ? f.url : textUrl('Download is switched off for this file (403 download_disabled).');
}

export function setManagers(repo: string, usernames: string[], me: User): ProjectFiles {
  if (!me.isAdmin) throw new MockError(403, 'forbidden', 'Admins only.');
  const unknown = usernames.filter((u) => !db.users.some((x) => x.username === u));
  if (unknown.length) throw new MockError(400, 'unknown_users', `Unknown user${unknown.length === 1 ? '' : 's'}: ${unknown.join(', ')}`);
  store(repo).managers = [...new Set(usernames)];
  return projectFiles(repo, me);
}

export function adminOverview(me: User): AdminProjectFiles {
  if (!me.isAdmin) throw new MockError(403, 'forbidden', 'Admins only.');
  return {
    totalBytes: totalUsed(),
    limits: LIMITS,
    repos: [...stores.entries()]
      .filter(([, s]) => s.files.length > 0 || s.uploads.length > 0)
      .map(([repo, s]) => ({ repo, files: s.files.length, bytes: used(s) }))
      .sort((a, b) => b.bytes - a.bytes),
  };
}

export function adminDelete(repo: string, me: User) {
  if (!me.isAdmin) throw new MockError(403, 'forbidden', 'Admins only.');
  const s = stores.get(repo);
  if (!s) throw new MockError(404, 'not_found', `${repo} has no files`);
  s.files = [];
  s.uploads = [];
}

/** The sandbox definition's `files` (seeded per project) and which of them are not uploaded. */
export function withSandboxFiles(st: SandboxStatus): SandboxStatus {
  if (!st.definition) return st;
  const names = SANDBOX_FILES[st.repo] ?? [];
  const have = new Set(store(st.repo).files.map((f) => f.name));
  return { ...st, definition: { ...st.definition, files: names }, missingFiles: names.filter((n) => !have.has(n)) };
}

/**
 * A web-shop run (a merged one when the seed has one, else its oldest) got the project's files: the SDK (required by the
 * sandbox, changed since the previous run) and the CA.
 */
export function seedRunProjectFiles() {
  const shop = [...db.runs.values()].filter((x) => x.repo === 'web-shop' && x.kind !== 'sync').sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const r = shop.find((x) => x.status === 'merged') ?? shop[0];
  if (!r) return;
  const s = store('web-shop');
  const pf: RunProjectFiles = {
    repo: 'web-shop',
    files: s.files
      .filter((f) => f.name !== 'fonts/brand-sans.woff2')
      .map((f) => ({
        name: f.name,
        size: f.size,
        sha256: f.sha256,
        version: f.version,
        required: (SANDBOX_FILES['web-shop'] ?? []).includes(f.name),
        previousSha256: f.name.startsWith('vendor/') ? fakeSha(`${f.name}/previous`) : null,
      })),
  };
  r.projectFiles = pf;
}
