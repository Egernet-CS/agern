// Mock implementation of the Api interface. Enabled with VITE_MOCK=1 (npm run dev:mock).
// Scenarios via ?mock=default|fresh|noclaude|observer|loggedout|nosandbox|usage-none|usage-high|usage-limit (remembered per tab;
// nosandbox: FACTORY_SANDBOX=off; usage-*: alice's Claude usage without data, at 86 % or at a reached limit).
// GitHub status: ?mock=github-degraded (several 5xx on issues and releases while GitHub's own page says all is well) and
// ?mock=github-down (only network errors, githubstatus.com unreachable); any other scenario: GitHub is working.
import { ApiError, type Api, type Channel } from '../api/api';
import * as deploy from './deploy';
import * as projectFiles from './files';
import { githubStatus } from './githubStatus';
import * as images from './images';
import * as sandboxVm from './sandboxVm';

/**
 * A light-theme sketch of a shop page with an open cart drawer (demo content, 320×200), used for every mock
 * screenshot a reviewer attaches: recognisable in the run page and in the manual's figures.
 */
const SCREENSHOT_SVG =
  'data:image/svg+xml;charset=utf-8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" viewBox="0 0 320 200" font-family="sans-serif">' +
      '<rect width="320" height="200" fill="#f6f4ef"/>' +
      '<rect width="320" height="24" fill="#ffffff"/><rect x="10" y="8" width="44" height="8" rx="2" fill="#2d6a4f"/>' +
      '<rect x="230" y="8" width="24" height="8" rx="2" fill="#c9c4b8"/><rect x="262" y="8" width="24" height="8" rx="2" fill="#c9c4b8"/>' +
      [0, 1, 2].map((i) => `<rect x="${10 + i * 62}" y="36" width="54" height="54" rx="4" fill="#e3ddd0"/><rect x="${10 + i * 62}" y="96" width="40" height="6" rx="2" fill="#8a8476"/><rect x="${10 + i * 62}" y="106" width="24" height="6" rx="2" fill="#2d6a4f"/>`).join('') +
      '<rect x="0" y="24" width="200" height="176" fill="#000000" fill-opacity="0.18"/>' +
      '<rect x="200" y="24" width="120" height="176" fill="#ffffff"/><text x="210" y="42" font-size="10" font-weight="700" fill="#1f2421">Your cart</text>' +
      [0, 1, 2].map((i) => `<rect x="210" y="${52 + i * 28}" width="20" height="20" rx="3" fill="#e3ddd0"/><rect x="236" y="${55 + i * 28}" width="50" height="5" rx="2" fill="#8a8476"/><rect x="236" y="${64 + i * 28}" width="26" height="5" rx="2" fill="#c9c4b8"/><text x="310" y="${64 + i * 28}" font-size="7" text-anchor="end" fill="#1f2421">24.99</text>`).join('') +
      '<line x1="210" y1="140" x2="310" y2="140" stroke="#e3ddd0"/><text x="210" y="154" font-size="8" fill="#1f2421">Total</text><text x="310" y="154" font-size="8" font-weight="700" text-anchor="end" fill="#1f2421">74.97 kr</text>' +
      '<rect x="210" y="164" width="100" height="22" rx="4" fill="#2d6a4f"/><text x="260" y="179" font-size="8" font-weight="700" text-anchor="middle" fill="#ffffff">Checkout</text>' +
      '</svg>',
  );
import { isRunActive, isSessionActive, type AgentSession, type Attachment, type ClientConfig, type Issue, type LoginStep, type McpToken, type Preferences, type Release, type Run, type StageRole, type User } from '../api/types';
import {
  announceIssue,
  assignIssues,
  cancelReleaseSchedule,
  createRelease,
  scheduleRelease,
  deleteRelease,
  effective,
  getRelease,
  releaseOfIssue,
  releasesOf,
  reorderRelease,
  updateRelease,
  startRelease,
  pauseRelease,
  resumeRelease,
  cancelRelease,
  retryReleaseIssue,
  retryOptionsFor,
  retryRun,
  approveRelease,
  checkRelease,
  syncRelease,
  setReleaseMode,
  approveRun,
  mergeAnywayRun,
  approveSandbox,
  cancelRun,
  reviewRun,
  talkToRun,
  revokeSandboxApproval,
  setProjectIcon,
  setProjectSettings,
  setProjectIconFromRepository,
  deleteProjectIcon,
  pendingSandboxes,
  sandboxOverview,
  sandboxStatus,
  stopSandbox,
  chatInput,
  clearUsageWindows,
  closeChat,
  createChat,
  db,
  interrupt,
  messageAgent,
  MockError,
  ORG,
  issueMembers,
  startRun,
  stopSession,
  workspaceOf,
  usageOf,
} from './engine';

images.seedRunImage();
sandboxVm.seedSandboxVmPreview();
projectFiles.seedRunProjectFiles();

/** Mock rule: any 6 digits except 000000, or a recovery code like abcd-1234 (login only). */
function secondFactor(u: User, code: string, allowRecovery: boolean) {
  // Same codes and statuses as the backend (docs/ARCHITECTURE.md §8): 422 invalid_code, 423 locked + lockedUntil.
  const until = db.lockedUntil.get(u.username) ?? 0;
  if (until > Date.now()) {
    throw new MockError(423, 'locked', `Too many wrong codes. Two-factor sign-in is locked until ${new Date(until).toISOString()}.`, new Date(until).toISOString());
  }
  const c = code.trim();
  const valid = (/^\d{6}$/.test(c) && c !== '000000') || (allowRecovery && /^[a-z0-9]{4}-?[a-z0-9]{4}$/i.test(c));
  if (!valid) {
    const n = (db.failures.get(u.username) ?? 0) + 1;
    db.failures.set(u.username, n);
    if (n >= 5) {
      const lockEnd = Date.now() + 15 * 60_000;
      db.lockedUntil.set(u.username, lockEnd);
      db.failures.delete(u.username);
      u.twoFactorLockedUntil = new Date(lockEnd).toISOString();
      throw new MockError(423, 'locked', 'Too many wrong codes. Two-factor sign-in is locked for 15 minutes.', u.twoFactorLockedUntil);
    }
    throw new MockError(422, 'invalid_code', 'Invalid code');
  }
  db.failures.delete(u.username);
}
function finish(u: User) {
  db.pending = null;
  db.loggedIn = true;
  db.me = u;
}
function recoveryCodes() {
  const a = 'abcdefghjkmnpqrstuvwxyz23456789';
  const part = () => Array.from({ length: 4 }, () => a[Math.floor(Math.random() * a.length)]).join('');
  return Array.from({ length: 10 }, () => `${part()}-${part()}`);
}

// ---- personal MCP tokens (in memory; the "secret" is fake and only ever returned by create)

type MockMcpToken = McpToken & { revoked: boolean };
const mcpTokens: MockMcpToken[] = [
  {
    id: 'mcp-seed-bob', name: 'workstation', repo: 'web-shop', owner: { username: 'bob', displayName: 'Bob Kjær' },
    createdAt: new Date(Date.now() - 6 * 86_400_000).toISOString(), lastUsedAt: new Date(Date.now() - 3_600_000).toISOString(),
    expiresAt: new Date(Date.now() + 84 * 86_400_000).toISOString(), expired: false, revoked: false,
  },
];
/** Like the server's McpIdentity.serverName, for the mock's public URL (the page's own origin). */
function mockServerName(repo: string) {
  const base = `${location.host}-${repo}`.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  const prefixed = base.startsWith('agern') ? base : `agern-${base}`;
  return prefixed.slice(0, 64).replace(/-+$/, '');
}
function mcpTokenDto(t: MockMcpToken): McpToken {
  return {
    id: t.id, name: t.name, repo: t.repo, owner: t.owner, createdAt: t.createdAt, lastUsedAt: t.lastUsedAt, expiresAt: t.expiresAt,
    expired: t.expiresAt !== null && Date.parse(t.expiresAt) <= Date.now(),
  };
}
const ownMcpTokens = () => mcpTokens.filter((t) => !t.revoked && t.owner.username === me().username).map(mcpTokenDto);
const allMcpTokens = () => mcpTokens.filter((t) => !t.revoked).map(mcpTokenDto);

const clone = <T>(v: T): T => structuredClone(v);
const wait = (ms = 120 + Math.random() * 180) => new Promise((r) => setTimeout(r, ms));

async function ok<T>(fn: () => T): Promise<T> {
  await wait();
  try {
    return clone(fn());
  } catch (e) {
    if (e instanceof MockError) throw new ApiError(e.status, e.code, e.message, e.lockedUntil);
    throw e;
  }
}

function me(): User {
  if (!db.loggedIn) throw new MockError(401, 'unauthorized', 'Not logged in');
  return db.me;
}
function visible() {
  return me().hasGithubToken;
}
function needGithub() {
  if (!visible()) throw new MockError(403, 'forbidden', 'Register a GitHub token in Settings first');
}
function needClaude() {
  if (!me().hasClaudeCredential) throw new MockError(422, 'claude_credential_required', 'Register a Claude credential in Settings first');
}
function needAdmin() {
  if (!me().isAdmin) throw new MockError(403, 'forbidden', 'Admins only.');
}
function project(repo: string) {
  needGithub();
  const p = db.projects.find((x) => x.name === repo);
  if (!p) throw new MockError(404, 'not_found', `No repository ${repo} in ${ORG}.`);
  const sessions = [...db.sessions.values()].filter((s) => s.repo === repo && isSessionActive(s));
  const runs = [...db.runs.values()].filter((r) => r.repo === repo && isRunActive(r));
  return { ...p, activeAgents: sessions.length, activeRuns: runs.length, openIssues: (db.issues[repo] ?? []).filter((i) => i.state === 'open').length, workspace: null };
}
/** A release the caller can see (404 before any 403, like the server). */
function withRelease<T>(id: string, fn: (r: Release) => T): T {
  const r = getRelease(id);
  project(r.repo);
  return fn(r);
}
function session(id: string): AgentSession {
  const s = db.sessions.get(id);
  if (!s) throw new MockError(404, 'not_found', 'Session not found');
  return s;
}
function run(id: string): Run {
  const r = db.runs.get(id);
  if (!r) throw new MockError(404, 'not_found', 'Run not found');
  return r;
}
/** "Retry now" / "Give up" of a network wait: the mock never waits, so after the server's checks it answers 409. */
function notWaitingForNetwork(r: Run): never {
  if (r.owner.username !== me().username && !me().isAdmin) throw new MockError(403, 'forbidden', 'Only the owner or an admin can do this for this run');
  throw new MockError(409, 'run_not_waiting_for_network', 'The run is not waiting for the network');
}
// ---------------------------------------------------------------- attachments (same rules as the backend)

const LIMITS: ClientConfig['attachments'] = { maxFileBytes: 20 << 20, maxMessageBytes: 50 << 20, maxCount: 10, imageTypes: ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] };
type MockAttachment = { meta: Attachment; sessionId: string; owner: string; sent: boolean; url: string };
const attachments = new Map<string, MockAttachment>();

function sanitizeName(raw: string): string {
  // eslint-disable-next-line no-control-regex
  let n = (raw.split(/[\\/]/).pop() ?? '').replace(/[\u0000-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069]/g, '').replace(/[:*?"<>|]/g, '_');
  n = n.replace(/\s+/g, ' ').trim().replace(/^[.\s]+/, '');
  return (n.length > 120 ? n.slice(0, 120) : n) || 'attachment';
}

async function imageSize(file: File): Promise<{ width: number; height: number } | null> {
  try {
    const bmp = await createImageBitmap(file);
    const r = { width: bmp.width, height: bmp.height };
    bmp.close();
    return r;
  } catch {
    return null;
  }
}

const ROLES: StageRole[] = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver'];
const MODEL_RE = /^[A-Za-z0-9._[\]-]{1,100}$/;

function preferences(): Preferences {
  const u = me().username;
  const o = db.prefs.get(u) ?? { chatModel: null, pipelineModels: {}, maxFixRounds: null };
  const e = effective(u);
  const max = db.settings.maxFixRounds;
  return {
    overrides: o,
    effective: {
      chatModel: { value: e.chatModel, source: o.chatModel ? 'user' : 'default' },
      pipelineModels: Object.fromEntries(ROLES.map((r) => [r, { value: e.model(r), source: o.pipelineModels[r] ? 'user' : 'default' }])) as Preferences['effective']['pipelineModels'],
      maxFixRounds: { value: e.maxFixRounds, source: o.maxFixRounds != null ? 'user' : 'default', max, clamped: o.maxFixRounds != null && o.maxFixRounds > max },
    },
    suggestions: ['opus', 'sonnet', 'haiku', 'fable'],
  };
}

const byNewest = <T extends { createdAt: string }>(a: T, b: T) => b.createdAt.localeCompare(a.createdAt);

export const mockApi: Api = {
  health: () => ok(() => ({ status: 'ok' as const, version: '0.1.0-mock', name: 'agern' })),
  githubStatus: (opts) => ok(() => githubStatus(me(), !!opts?.brief)),
  providers: () => ok(() => ({ providers: ['os', 'ldap', 'dev'], org: ORG, twoFactor: true })),
  login: (username, password) =>
    ok((): LoginStep => {
      const u = db.users.find((x) => x.username === username);
      // Any non-empty password works in the mock.
      if (!u || !password) throw new MockError(401, 'invalid_credentials', 'Wrong username or password.');
      db.loggedIn = false;
      if (u.twoFactorEnabled) {
        db.pending = { user: u, step: 'totp' };
        return { next: 'totp' };
      }
      db.pending = { user: u, step: 'enroll' };
      const secret = 'JBSWY3DPEHPK3PXPKZQGK3TFOJ2W4ZDF';
      return { next: 'enroll', secret, otpauthUri: `otpauth://totp/Agern:${u.username}?secret=${secret}&issuer=Agern&algorithm=SHA1&digits=6&period=30` };
    }),
  totp: (code) =>
    ok((): LoginStep => {
      const p = db.pending;
      if (!p) throw new MockError(401, 'unauthorized', 'Not logged in');
      if (p.step !== 'totp') throw new MockError(409, 'wrong_login_step', `This login step is not expected now (session is in '${p.step}')`);
      secondFactor(p.user, code, true);
      finish(p.user);
      return { next: 'done', user: p.user };
    }),
  totpEnroll: (code) =>
    ok((): LoginStep => {
      const p = db.pending;
      if (!p) throw new MockError(401, 'unauthorized', 'Not logged in');
      if (p.step !== 'enroll') throw new MockError(409, 'wrong_login_step', `This login step is not expected now (session is in '${p.step}')`);
      secondFactor(p.user, code, false);
      p.user.twoFactorEnabled = true;
      finish(p.user);
      return { next: 'done', user: p.user, recoveryCodes: recoveryCodes() };
    }),
  regenerateRecoveryCodes: (code) =>
    ok(() => {
      secondFactor(me(), code, false);
      return { recoveryCodes: recoveryCodes() };
    }),
  resetTwoFactor: (id) =>
    ok(() => {
      needAdmin();
      const u = db.users.find((x) => x.id === id);
      if (!u) throw new MockError(404, 'not_found', 'User not found');
      u.twoFactorEnabled = false;
      u.twoFactorLockedUntil = null;
      db.failures.delete(u.username);
      db.lockedUntil.delete(u.username);
      return u;
    }),
  mcpTokens: () => ok(ownMcpTokens),
  createMcpToken: ({ name, repo, expiresInDays }) =>
    ok(() => {
      const u = me();
      const n = name.trim();
      if (!n || n.length > 60 || [...n].some((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)) throw new MockError(400, 'bad_request', 'name must be 1 to 60 characters without control characters');
      if (expiresInDays !== null && (expiresInDays < 1 || expiresInDays > 366)) throw new MockError(400, 'bad_request', 'expiresInDays must be between 1 and 366, or null');
      project(repo);
      const active = mcpTokens.filter((t) => !t.revoked && t.owner.username === u.username);
      if (active.length >= 50) throw new MockError(409, 'too_many_mcp_tokens', 'You have 50 tokens; revoke one first');
      if (active.some((t) => t.repo === repo && t.name.toLowerCase() === n.toLowerCase())) {
        throw new MockError(409, 'mcp_token_name_taken', `You already have a token named "${n}" for ${repo}`);
      }
      const now = Date.now();
      const t: MockMcpToken = {
        id: `mcp-${now.toString(36)}`, name: n, repo, owner: { username: u.username, displayName: u.displayName },
        createdAt: new Date(now).toISOString(), lastUsedAt: null,
        expiresAt: expiresInDays === null ? null : new Date(now + expiresInDays * 86_400_000).toISOString(), expired: false, revoked: false,
      };
      mcpTokens.push(t);
      const secret = `agern_pat_mock${Array.from({ length: 39 }, () => 'abcdefghijklmnopqrstuvwxyz0123456789-_'[Math.floor(Math.random() * 38)]).join('')}`;
      return { token: mcpTokenDto(t), secret, mcpUrl: `${location.origin}/mcp`, serverName: mockServerName(repo) };
    }),
  revokeMcpToken: (id) =>
    ok(() => {
      const t = mcpTokens.find((x) => x.id === id && !x.revoked && x.owner.username === me().username);
      if (!t) throw new MockError(404, 'not_found', 'Token not found');
      t.revoked = true;
      return ownMcpTokens();
    }),
  adminMcpTokens: () => ok(() => (needAdmin(), allMcpTokens())),
  adminRevokeMcpToken: (id) =>
    ok(() => {
      needAdmin();
      const t = mcpTokens.find((x) => x.id === id && !x.revoked);
      if (!t) throw new MockError(404, 'not_found', 'Token not found');
      t.revoked = true;
      return allMcpTokens();
    }),
  logout: () =>
    ok(() => {
      db.loggedIn = false;
      db.pending = null;
    }),
  me: () => ok(me),
  putClaudeCredential: (kind, value) =>
    ok(() => {
      if (value.trim().length < 8) throw new MockError(422, 'unprocessable', 'The credential looks malformed');
      Object.assign(me(), { hasClaudeCredential: true, claudeCredentialKind: kind });
      clearUsageWindows(me());
      return me();
    }),
  deleteClaudeCredential: () =>
    ok(() => {
      Object.assign(me(), { hasClaudeCredential: false, claudeCredentialKind: null });
      clearUsageWindows(me());
      return me();
    }),
  putGithubToken: (token) =>
    ok(() => {
      if (token.startsWith('bad') || token.length < 8) throw new MockError(422, 'github_token_invalid', 'GitHub rejected the token (401)');
      // Like the backend: the kind comes from the prefix, classic tokens report their scopes.
      const kind = token.startsWith('github_pat_') ? 'fine_grained' : 'classic';
      return Object.assign(me(), {
        hasGithubToken: true,
        githubLogin: me().githubLogin ?? `${me().username}-gh`,
        githubTokenKind: kind,
        githubTokenScopes: kind === 'classic' ? ['read:org', 'repo'] : null,
      });
    }),
  putImageProvider: (provider, model, apiKey) => ok(() => images.setImageProvider(me(), provider, model, apiKey)),
  deleteImageProvider: () => ok(() => images.removeImageProvider(me())),
  startCodexLogin: () => ok(() => images.startCodexLogin()),
  codexLogin: () => ok(() => images.pollCodexLogin(me())),
  cancelCodexLogin: () => ok(() => images.cancelCodexLogin()),
  deleteGithubToken: () => ok(() => Object.assign(me(), { hasGithubToken: false, githubLogin: null, githubTokenKind: null, githubTokenScopes: null })),

  preferences: () => ok(preferences),
  usage: () => ok(() => usageOf(me())),
  putPreferences: (p) =>
    ok(() => {
      const chat = p.chatModel?.trim() || null;
      const models: Record<string, string> = {};
      for (const [k, v] of Object.entries(p.pipelineModels ?? {})) if (v?.trim()) models[k] = v.trim();
      if (Object.keys(models).some((k) => !ROLES.includes(k as StageRole))) throw new MockError(400, 'bad_request', 'Unknown role');
      if ([chat, ...Object.values(models)].some((m) => m && !MODEL_RE.test(m))) throw new MockError(400, 'bad_request', 'Invalid model');
      const max = db.settings.maxFixRounds;
      if (p.maxFixRounds != null && (p.maxFixRounds < 0 || p.maxFixRounds > max)) {
        throw new MockError(422, 'max_fix_rounds_out_of_range', `maxFixRounds must be between 0 and ${max} (the factory's maximum)`);
      }
      db.prefs.set(me().username, { chatModel: chat, pipelineModels: models, maxFixRounds: p.maxFixRounds ?? null });
      return preferences();
    }),
  projects: () => ok(() => (visible() ? db.projects.map((p) => project(p.name)) : [])),
  project: (repo) => ok(() => ({ ...project(repo), workspace: workspaceOf(repo, me()) })),
  setProjectIcon: (repo, file) =>
    ok(() => {
      needAdmin();
      project(repo);
      setProjectIcon(repo, file);
      return project(repo);
    }),
  setProjectIconFromRepository: (repo, path) =>
    ok(() => {
      needAdmin();
      project(repo);
      setProjectIconFromRepository(repo, path);
      return project(repo);
    }),
  setProjectSettings: (repo, settings) =>
    ok(() => {
      needAdmin();
      project(repo);
      setProjectSettings(repo, settings);
      return project(repo);
    }),
  deleteProjectIcon: (repo) =>
    ok(() => {
      needAdmin();
      project(repo);
      deleteProjectIcon(repo);
      return project(repo);
    }),
  issues: (repo, state = 'open') =>
    ok(() => {
      project(repo);
      return (db.issues[repo] ?? [])
        .filter((i) => state === 'all' || i.state === state)
        .map(({ body: _body, ...i }) => ({ ...i, release: releaseOfIssue(repo, i.number), imageCount: images.imageCount(repo, i.number), workspaceMembers: issueMembers(repo, i as Issue) }) as Issue);
    }),
  issue: (repo, n) =>
    ok(() => {
      project(repo);
      const i = db.issues[repo]?.find((x) => x.number === n);
      if (!i) throw new MockError(404, 'not_found', 'Issue not found');
      return { ...i, release: releaseOfIssue(repo, i.number), imageCount: images.imageCount(repo, i.number), workspaceMembers: issueMembers(repo, i) };
    }),
  createIssue: (repo, body) =>
    ok(() => {
      project(repo);
      const list = (db.issues[repo] ??= []);
      const number = Math.max(0, ...list.map((i) => i.number)) + 1;
      const label = body.kind === 'bug' ? { name: 'bug', color: 'd73a4a' } : body.kind === 'feature' ? { name: 'enhancement', color: 'a2eeef' } : { name: 'chore', color: 'c5def5' };
      const now = new Date().toISOString();
      const i: Issue = { number, title: body.title, state: 'open', kind: body.kind, labels: [label], author: me().githubLogin ?? me().username, assignees: [], comments: 0, createdAt: now, updatedAt: now, htmlUrl: `https://github.com/${ORG}/${repo}/issues/${number}`, body: body.body, activeRunId: null, release: null, imageCount: 0 };
      list.unshift(i);
      announceIssue(repo, i);
      return i;
    }),

  deployStatus: (repo) => ok(() => deploy.deployStatus(repo, project(repo).defaultBranch)),
  approveDeploy: (repo, sha256) => ok(() => (project(repo), deploy.approveDeploy(repo, sha256, me()))),
  revokeDeployApproval: (repo) => ok(() => (project(repo), deploy.revokeDeployApproval(repo, me()))),
  generateDeployKey: (repo) => ok(() => (project(repo), deploy.generateDeployKey(repo, me()))),
  rotateDeployKey: (repo) => ok(() => (project(repo), deploy.rotateDeployKey(repo, me()))),
  checkDeployTarget: (repo, target) => ok(() => (project(repo), deploy.checkDeployTarget(repo, target, me()))),
  getSandboxVm: (repo) => ok(() => sandboxVm.getSandboxVm(repo, project(repo).defaultBranch)),
  approveSandboxVm: (repo, sha256) => ok(() => (project(repo), sandboxVm.approveSandboxVm(repo, sha256, me()))),
  revokeSandboxVm: (repo) => ok(() => (project(repo), sandboxVm.revokeSandboxVm(repo, me()))),
  checkSandboxVm: (repo) => ok(() => (project(repo), sandboxVm.checkSandboxVm(repo, me()))),
  releaseSandboxVmLease: (repo) => ok(() => (project(repo), sandboxVm.releaseSandboxVmLease(repo, me()))),
  startSandboxVmPreview: (runId) => ok(() => (needGithub(), sandboxVm.startSandboxVmPreview(run(runId), me()))),
  releaseSandboxVmPreview: (runId) => ok(() => (needGithub(), sandboxVm.releaseSandboxVmPreview(run(runId), me()))),
  sandboxStatus: (repo) => ok(() => projectFiles.withSandboxFiles(sandboxStatus(repo, project(repo).defaultBranch))),
  approveSandbox: (repo, sha256) => ok(() => (project(repo), projectFiles.withSandboxFiles(approveSandbox(repo, sha256, me())))),
  revokeSandboxApproval: (repo) => ok(() => (project(repo), projectFiles.withSandboxFiles(revokeSandboxApproval(repo, me())))),
  sandboxes: () => ok(() => (needAdmin(), sandboxOverview())),
  pendingSandboxes: () => ok(() => (needAdmin(), pendingSandboxes())),
  stopSandbox: (runId) => ok(() => (needAdmin(), stopSandbox(runId, me()))),

  releases: (repo) => ok(() => (project(repo), releasesOf(repo))),
  release: (id) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      return r;
    }),
  createRelease: (repo, body) => ok(() => (project(repo), createRelease(repo, body, me()))),
  updateRelease: (id, body) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      return updateRelease(r, body, me());
    }),
  deleteRelease: (id) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      deleteRelease(r, me());
    }),
  assignIssues: (repo, numbers, releaseId) => ok(() => (project(repo), assignIssues(repo, numbers, releaseId, me()))),
  scheduleRelease: (id, startAt) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      return scheduleRelease(r, startAt, me());
    }),
  cancelReleaseSchedule: (id) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      return cancelReleaseSchedule(r, me());
    }),
  reorderRelease: (id, numbers) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      return reorderRelease(r, numbers, me());
    }),
  startRelease: (id) => ok(() => withRelease(id, (r) => startRelease(r, me()))),
  pauseRelease: (id) => ok(() => withRelease(id, (r) => pauseRelease(r, me()))),
  resumeRelease: (id) => ok(() => withRelease(id, (r) => resumeRelease(r, me()))),
  // The mock never waits for the network: the checks of the server, then 409.
  giveUpReleaseNetwork: (id) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      if (r.owner && r.owner.username !== me().username && !me().isAdmin) {
        throw new MockError(403, 'forbidden', `Only ${r.owner.username}, who started this release, or an admin can do this`);
      }
      throw new MockError(409, 'release_not_waiting_for_network', 'The release is not waiting for the network');
    }),
  cancelRelease: (id) => ok(() => withRelease(id, (r) => cancelRelease(r, me()))),
  retryReleaseIssue: (id, n, mode) => ok(() => withRelease(id, (r) => retryReleaseIssue(r, n, me(), mode))),
  releaseIssueRetryOptions: (id, n) =>
    ok(() => {
      const r = getRelease(id);
      project(r.repo);
      const i = r.issues.find((x) => x.number === n);
      return retryOptionsFor(i?.runId ? db.runs.get(i.runId) : undefined);
    }),
  approveRelease: (id) => ok(() => withRelease(id, (r) => approveRelease(r, me()))),
  checkRelease: (id) => ok(() => withRelease(id, (r) => checkRelease(r, me()))),
  syncRelease: (id) => ok(() => withRelease(id, (r) => syncRelease(r, me()))),
  setReleaseMode: (id, mode) => ok(() => withRelease(id, (r) => setReleaseMode(r, mode, me()))),

  projectFiles: (repo) => ok(() => (project(repo), projectFiles.projectFiles(repo, me()))),
  createProjectFileUpload: (repo, body) => ok(() => (project(repo), projectFiles.createUpload(repo, body, me()))),
  projectFileUpload: (repo, id) => ok(() => (project(repo), projectFiles.getUpload(repo, id, me()))),
  async putProjectFileChunk(repo, id, offset, chunk, opts) {
    await ok(() => project(repo));
    return projectFiles.putChunk(repo, id, offset, chunk, me(), opts);
  },
  async completeProjectFileUpload(repo, id) {
    await ok(() => project(repo));
    return projectFiles.completeUpload(repo, id, me());
  },
  cancelProjectFileUpload: (repo, id) => ok(() => (project(repo), projectFiles.cancelUpload(repo, id, me()))),
  updateProjectFile: (repo, id, body) => ok(() => (project(repo), projectFiles.updateFile(repo, id, body, me()))),
  deleteProjectFile: (repo, id) => ok(() => (project(repo), projectFiles.deleteFile(repo, id, me()))),
  projectFileUrl: (repo, id) => projectFiles.fileUrl(repo, id),
  setProjectFileManagers: (repo, usernames) => ok(() => (project(repo), projectFiles.setManagers(repo, usernames, me()))),
  adminProjectFiles: () => ok(() => projectFiles.adminOverview(me())),
  adminDeleteProjectFiles: (repo) => ok(() => projectFiles.adminDelete(repo, me())),

  projectRuns: (repo) => ok(() => (project(repo), [...db.runs.values()].filter((r) => r.repo === repo).sort(byNewest))),
  createMemberLabels: (repo) => ok(() => (project(repo), { created: [], missing: [] })),
  startRun: (repo, n) =>
    ok(() => {
      project(repo);
      needClaude();
      const r = startRun(repo, n, me());
      images.claimImages(r);
      return r;
    }),
  runs: (filter = {}) =>
    ok(() =>
      visible()
        ? [...db.runs.values()]
            .filter((r) => (!filter.active || isRunActive(r)) && (!filter.status?.length || filter.status.includes(r.status)))
            .sort(byNewest)
        : [],
    ),
  run: (id) => ok(() => (needGithub(), run(id))),
  approveRun: (id) =>
    ok(() => {
      const r = run(id);
      approveRun(r, me());
      return r;
    }),
  mergeAnywayRun: (id, headSha) =>
    ok(() => {
      const r = run(id);
      mergeAnywayRun(r, me(), headSha);
      return r;
    }),
  runRetryOptions: (id) => ok(() => retryOptionsFor(run(id))),
  retryRun: (id, mode) =>
    ok(() => {
      needClaude();
      return retryRun(run(id), mode, me());
    }),
  cancelRun: (id) =>
    ok(() => {
      const r = run(id);
      if (r.owner.username !== me().username && !me().isAdmin) throw new MockError(403, 'forbidden', 'Only the owner or an admin can cancel.');
      cancelRun(r);
      return r;
    }),

  retryRunNow: (id) => ok(() => notWaitingForNetwork(run(id))),
  giveUpRun: (id) => ok(() => notWaitingForNetwork(run(id))),

  talkToRun: (id, text) => ok(() => (needGithub(), talkToRun(run(id), text, me()))),
  reviewRun: (id) =>
    ok(() => {
      const r = run(id);
      reviewRun(r, me());
      return r;
    }),

  projectSessions: (repo, kind) =>
    ok(() => (project(repo), [...db.sessions.values()].filter((s) => s.repo === repo && (!kind || s.kind === kind)).sort(byNewest))),
  createChat: (repo) =>
    ok(() => {
      project(repo);
      needClaude();
      return createChat(repo, me());
    }),
  agents: (activeOnly = false) =>
    ok(() => (visible() ? [...db.sessions.values()].filter((s) => !activeOnly || isSessionActive(s)).sort(byNewest) : [])),
  session: (id) => ok(() => session(id)),
  events: (id, after = 0, limit = 1000) => ok(() => (db.events.get(session(id).id) ?? []).filter((e) => e.seq > after).slice(0, limit)),
  sendInput: (id, text, attachmentIds = []) =>
    ok(() => {
      const s = session(id);
      if (s.owner.username !== me().username) throw new MockError(403, 'forbidden', 'Only the owner can send input.');
      if (s.kind !== 'chat') {
        // Like the backend: the same endpoint carries messages to a running spec-writer / builder (text only).
        if (attachmentIds.length) throw new MockError(422, 'attachments_not_allowed', 'Messages to pipeline agents are text only');
        messageAgent(s, text, me());
        return;
      }
      if (!text.trim() && attachmentIds.length === 0) throw new MockError(400, 'bad_request', 'text must not be empty');
      if (text.trim().startsWith('/factory') && attachmentIds.length) {
        throw new MockError(422, 'attachments_not_allowed', '/factory commands do not take attachments. Remove them, or send them in a normal message.');
      }
      // Like the backend: /compact goes to the CLI as is, and only when it can run now.
      const compact = /^\/compact(\s|$)/.test(text.trim());
      if (compact) {
        if (attachmentIds.length) throw new MockError(422, 'attachments_not_allowed', '/compact does not take attachments. Remove them, or send them in a normal message.');
        if (s.status === 'running' || s.status === 'starting') throw new MockError(409, 'agent_busy', 'The agent is working. Compact when it has finished, or interrupt it first.');
        if (!(db.events.get(s.id) ?? []).some((e) => e.type === 'claude')) throw new MockError(409, 'nothing_to_compact', 'There is nothing to compact yet.');
      }
      if (!text.trim().startsWith('/factory')) needClaude();
      const list = attachmentIds.map((a) => attachments.get(a));
      if (new Set(attachmentIds).size !== attachmentIds.length || list.some((a) => !a || a.sent || a.sessionId !== id || a.owner !== me().username)) {
        throw new MockError(409, 'attachment_unavailable', 'An attachment is unknown, belongs to another session, or was already sent');
      }
      list.forEach((a) => (a!.sent = true));
      chatInput(s, text, me(), list.map((a) => a!.meta));
      if (!text.trim().startsWith('/factory') && !compact) images.chatImages(s, text, me());
    }),
  sendToAgent: (id, text) => ok(() => messageAgent(session(id), text, me())),
  async uploadAttachment(sessionId, file, opts) {
    const s = session(sessionId);
    if (s.owner.username !== me().username) throw new ApiError(403, 'forbidden', 'Only the owner of this session can attach files');
    const unsent = [...attachments.values()].filter((a) => a.sessionId === sessionId && !a.sent);
    if (unsent.length >= LIMITS.maxCount) throw new ApiError(409, 'too_many_attachments', `At most ${LIMITS.maxCount} attachments per message`);
    if (file.size > LIMITS.maxFileBytes) throw new ApiError(413, 'attachment_too_large', 'The file is larger than 20.0 MB');
    if (file.size + unsent.reduce((n, a) => n + a.meta.size, 0) > LIMITS.maxMessageBytes) throw new ApiError(413, 'attachments_too_large', 'Attachments of one message may total at most 50.0 MB');
    if (file.size === 0) throw new ApiError(400, 'empty_attachment', 'The file is empty');
    for (let i = 1; i <= 5; i++) {
      if (opts?.signal?.aborted) throw new ApiError(0, 'aborted', 'Upload cancelled');
      await wait(90);
      opts?.onProgress?.(Math.round((file.size * i) / 5), file.size);
    }
    const image = LIMITS.imageTypes.includes(file.type);
    const dims = image ? await imageSize(file) : null;
    const big = !!dims && (dims.width > 2000 || dims.height > 2000);
    const meta: Attachment = {
      id: crypto.randomUUID(),
      name: sanitizeName(file.name),
      mediaType: image ? file.type : file.type.startsWith('text/') ? 'text/plain' : 'application/octet-stream',
      size: file.size,
      kind: image ? 'image' : 'file',
      width: dims?.width ?? null,
      height: dims?.height ?? null,
      inline: image ? (big ? 'downscaled' : 'original') : 'none',
      note: big ? 'Shown to the model as a downscaled copy; the original is available as a file.' : null,
    };
    attachments.set(meta.id, { meta, sessionId, owner: me().username, sent: false, url: URL.createObjectURL(file) });
    return clone(meta);
  },
  deleteAttachment: (sessionId, id) =>
    ok(() => {
      const a = attachments.get(id);
      if (!a || a.sessionId !== sessionId) throw new MockError(404, 'not_found', 'Attachment not found');
      if (a.owner !== me().username) throw new MockError(403, 'forbidden', 'Only the owner of this session can remove attachments');
      if (a.sent) throw new MockError(409, 'attachment_sent', 'The attachment was already sent');
      URL.revokeObjectURL(a.url);
      attachments.delete(id);
    }),
  // One demo sketch stands in for every screenshot (the mock has no image bytes).
  screenshotUrl: () => SCREENSHOT_SVG,
  sessionImages: (id) => ok(() => images.sessionImages(session(id).id)),
  issueImages: (repo, n) =>
    ok(() => {
      project(repo);
      return images.issueImages(repo, n);
    }),
  selectImage: (id, issueNumber, name) => ok(() => images.selectImage(id, issueNumber, name, me())),
  unselectImage: (id) => ok(() => images.unselectImage(id, me())),
  discardImage: (id) => ok(() => images.discardImage(id, me())),
  imageUrl: () => images.IMAGE_PNG,
  attachmentUrl: (_sessionId, id) => attachments.get(id)?.url ?? '/art/favicon.svg',
  config: () => ok(() => ({ attachments: LIMITS, images: images.IMAGE_CONFIG })),
  interrupt: (id) =>
    ok(() => {
      const s = session(id);
      if (s.owner.username !== me().username) throw new MockError(403, 'forbidden', 'Only the owner can interrupt.');
      interrupt(s);
    }),
  stopSession: (id) =>
    ok(() => {
      const s = session(id);
      stopSession(s);
      return s;
    }),
  closeChat: (id) =>
    ok(() => {
      const s = session(id);
      if (s.owner.username !== me().username && !me().isAdmin) throw new MockError(403, 'forbidden', 'Only the owner or an admin can close this chat');
      if (s.kind !== 'chat') throw new MockError(409, 'not_a_chat', 'Only chat sessions can be closed; cancel the run to stop a pipeline agent');
      images.closeChatImages(id);
      for (const [aid, a] of attachments) {
        if (a.sessionId !== id) continue;
        URL.revokeObjectURL(a.url);
        attachments.delete(aid);
      }
      closeChat(s);
    }),

  settings: () => ok(() => (needAdmin(), db.settings)),
  putSettings: (s) =>
    ok(() => {
      needAdmin();
      db.settings = clone(s);
      return db.settings;
    }),
  users: () => ok(() => (needAdmin(), db.users)),
  softwareRequests: () => ok(() => (needAdmin(), [...db.softwareRequests].sort(byNewest))),
  resolveSoftwareRequest: (id, status, note) =>
    ok(() => {
      needAdmin();
      const r = db.softwareRequests.find((x) => x.id === id);
      if (!r) throw new MockError(404, 'not_found', 'Request not found');
      Object.assign(r, { status, note: note ?? null, resolvedAt: new Date().toISOString() });
      return r;
    }),

  openSessionSocket(id, after, handlers): Channel {
    let closed = false;
    const sub = (e: Parameters<typeof handlers.onFrame>[0]) => !closed && handlers.onFrame(e);
    const onClose = (info: { code: number; reason: string }) => {
      if (closed) return;
      closed = true;
      handlers.onClose?.(info);
    };
    const t = setTimeout(() => {
      if (closed) return;
      // Like the server: an unknown (or closed) session is refused.
      if (!db.sessions.has(id)) return onClose({ code: 1008, reason: 'not found' });
      handlers.onOpen?.();
      for (const e of db.events.get(id) ?? []) if (e.seq > after) handlers.onFrame(clone(e));
      let set = db.sessionSubs.get(id);
      if (!set) db.sessionSubs.set(id, (set = new Set()));
      set.add(sub);
      let closers = db.sessionClosers.get(id);
      if (!closers) db.sessionClosers.set(id, (closers = new Set()));
      closers.add(onClose);
    }, 60);
    return {
      close() {
        closed = true;
        clearTimeout(t);
        db.sessionSubs.get(id)?.delete(sub);
        db.sessionClosers.get(id)?.delete(onClose);
      },
    };
  },
  openFeed(handlers): Channel {
    let closed = false;
    // Like the backend: `sandboxes` frames go to admins only; `usage` frames (only ever sent for the signed-in user) skip the repository filter.
    const sub = (f: Parameters<typeof handlers.onFrame>[0]) =>
      !closed && db.loggedIn && (f.type === 'usage' || (db.me.hasGithubToken && (f.type !== 'sandboxes' || db.me.isAdmin))) && handlers.onFrame(f);
    const t = setTimeout(() => {
      handlers.onOpen?.();
      db.feedSubs.add(sub);
    }, 60);
    return {
      close() {
        closed = true;
        clearTimeout(t);
        db.feedSubs.delete(sub);
      },
    };
  },
};

