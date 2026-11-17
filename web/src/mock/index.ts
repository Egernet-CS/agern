// Mock implementation of the Api interface. Enabled with VITE_MOCK=1 (npm run dev:mock).
// Scenarios via ?mock=default|fresh|noclaude|observer|loggedout (remembered per tab).
import { ApiError, type Api, type Channel } from '../api/api';
import { isRunActive, isSessionActive, type AgentSession, type Issue, type LoginStep, type Run, type User } from '../api/types';
import {
  approveRun,
  cancelRun,
  chatInput,
  createChat,
  db,
  interrupt,
  MockError,
  ORG,
  startRun,
  stopSession,
} from './engine';

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
  return { ...p, activeAgents: sessions.length, activeRuns: runs.length, openIssues: (db.issues[repo] ?? []).filter((i) => i.state === 'open').length };
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
const byNewest = <T extends { createdAt: string }>(a: T, b: T) => b.createdAt.localeCompare(a.createdAt);

export const mockApi: Api = {
  health: () => ok(() => ({ status: 'ok' as const, version: '0.1.0-mock', name: 'agern' })),
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
      return me();
    }),
  deleteClaudeCredential: () => ok(() => Object.assign(me(), { hasClaudeCredential: false, claudeCredentialKind: null })),
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
  deleteGithubToken: () => ok(() => Object.assign(me(), { hasGithubToken: false, githubLogin: null, githubTokenKind: null, githubTokenScopes: null })),

  projects: () => ok(() => (visible() ? db.projects.map((p) => project(p.name)) : [])),
  project: (repo) => ok(() => project(repo)),
  issues: (repo, state = 'open') =>
    ok(() => {
      project(repo);
      return (db.issues[repo] ?? []).filter((i) => state === 'all' || i.state === state).map(({ body: _body, ...i }) => i as Issue);
    }),
  issue: (repo, n) =>
    ok(() => {
      project(repo);
      const i = db.issues[repo]?.find((x) => x.number === n);
      if (!i) throw new MockError(404, 'not_found', 'Issue not found');
      return i;
    }),
  createIssue: (repo, body) =>
    ok(() => {
      project(repo);
      const list = (db.issues[repo] ??= []);
      const number = Math.max(0, ...list.map((i) => i.number)) + 1;
      const label = body.kind === 'bug' ? { name: 'bug', color: 'd73a4a' } : body.kind === 'feature' ? { name: 'enhancement', color: 'a2eeef' } : { name: 'chore', color: 'c5def5' };
      const now = new Date().toISOString();
      const i: Issue = { number, title: body.title, state: 'open', kind: body.kind, labels: [label], author: me().githubLogin ?? me().username, assignees: [], comments: 0, createdAt: now, updatedAt: now, htmlUrl: `https://github.com/${ORG}/${repo}/issues/${number}`, body: body.body, activeRunId: null };
      list.unshift(i);
      return i;
    }),

  projectRuns: (repo) => ok(() => (project(repo), [...db.runs.values()].filter((r) => r.repo === repo).sort(byNewest))),
  startRun: (repo, n) =>
    ok(() => {
      project(repo);
      needClaude();
      return startRun(repo, n, me());
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
  cancelRun: (id) =>
    ok(() => {
      const r = run(id);
      if (r.owner.username !== me().username && !me().isAdmin) throw new MockError(403, 'forbidden', 'Only the owner or an admin can cancel.');
      cancelRun(r);
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
  sendInput: (id, text) =>
    ok(() => {
      const s = session(id);
      if (s.owner.username !== me().username) throw new MockError(403, 'forbidden', 'Only the owner can send input.');
      if (s.kind !== 'chat') throw new MockError(409, 'conflict', 'Only chat sessions accept input');
      if (!text.trim().startsWith('/factory')) needClaude();
      chatInput(s, text, me());
    }),
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
    const t = setTimeout(() => {
      if (closed) return;
      handlers.onOpen?.();
      for (const e of db.events.get(id) ?? []) if (e.seq > after) handlers.onFrame(clone(e));
      let set = db.sessionSubs.get(id);
      if (!set) db.sessionSubs.set(id, (set = new Set()));
      set.add(sub);
    }, 60);
    return {
      close() {
        closed = true;
        clearTimeout(t);
        db.sessionSubs.get(id)?.delete(sub);
      },
    };
  },
  openFeed(handlers): Channel {
    let closed = false;
    const sub = (f: Parameters<typeof handlers.onFrame>[0]) => !closed && db.loggedIn && db.me.hasGithubToken && handlers.onFrame(f);
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

