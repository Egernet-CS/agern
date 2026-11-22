// In-memory simulation of the Agern backend. Only loaded when VITE_MOCK=1.
import { STEERABLE_ROLES } from '../api/types';
import type {
  AgentMessage,
  AgentSession,
  Attachment,
  ClaudeUsage,
  FactoryEvent,
  FeedFrame,
  Issue,
  IssueKind,
  PreferenceOverrides,
  Project,
  Release,
  ReleaseChecks,
  ReleaseMode,
  RetryMode,
  RetryOptions,
  Run,
  PendingSandbox,
  RunSandbox,
  RunningSandbox,
  SandboxApproval,
  SandboxDefinition,
  SandboxOverview,
  SandboxStatus,
  SessionKind,
  SessionStatus,
  Settings,
  SoftwareRequest,
  Stage,
  StageRole,
  User,
  UsageWindow,
  Verdict,
} from '../api/types';
import { approverScript, builderScript, chatReplies, reviewerScript, sandboxBuilderScript, seededChat, specScript, type Step } from './scripts';

export const ORG = 'Egernet-CS';
const iso = (ms: number) => new Date(ms).toISOString();
const ago = (min: number) => iso(Date.now() - min * 60_000);
let idCounter = 0;
export const uid = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `id-${Date.now()}-${idCounter++}`;

// ---------------------------------------------------------------- users & scenario

const scenario = (() => {
  const q = new URLSearchParams(location.search).get('mock');
  try {
    if (q) sessionStorage.setItem('agern-mock', q);
    return q ?? sessionStorage.getItem('agern-mock') ?? 'default';
  } catch {
    return q ?? 'default';
  }
})();

const alice: User = {
  id: uid(),
  username: 'alice',
  displayName: 'Alice Holm',
  isAdmin: true,
  githubLogin: 'alice-holm',
  hasGithubToken: scenario !== 'fresh',
  hasClaudeCredential: scenario !== 'fresh' && scenario !== 'noclaude',
  claudeCredentialKind: scenario === 'fresh' || scenario === 'noclaude' ? null : 'oauth_token',
  twoFactorEnabled: true,
  twoFactorLockedUntil: null,
  githubTokenKind: scenario !== 'fresh' ? 'classic' : null,
  githubTokenScopes: scenario !== 'fresh' ? ['read:org', 'repo'] : null,
};
const bob: User = {
  id: uid(),
  username: 'bob',
  displayName: 'Bob Kjær',
  isAdmin: false,
  githubLogin: 'bobkjaer',
  hasGithubToken: true,
  hasClaudeCredential: true,
  claudeCredentialKind: 'api_key',
  twoFactorEnabled: true,
  twoFactorLockedUntil: null,
  githubTokenKind: 'fine_grained',
  githubTokenScopes: null,
};
const carl: User = {
  id: uid(),
  username: 'carl',
  displayName: 'Carl Juhl',
  isAdmin: false,
  githubLogin: null,
  hasGithubToken: false,
  hasClaudeCredential: false,
  claudeCredentialKind: null,
  twoFactorEnabled: false,
  twoFactorLockedUntil: null,
  githubTokenKind: null,
  githubTokenScopes: null,
};
const dora: User = {
  id: uid(),
  username: 'dora',
  displayName: 'Dora Lind',
  isAdmin: false,
  githubLogin: 'doralind',
  hasGithubToken: true,
  hasClaudeCredential: true,
  claudeCredentialKind: 'oauth_token',
  twoFactorEnabled: true,
  twoFactorLockedUntil: new Date(Date.now() + 14 * 60_000).toISOString(),
  githubTokenKind: 'classic',
  githubTokenScopes: ['public_repo'],
};

export const db = {
  loggedIn: scenario !== 'loggedout',
  me: scenario === 'observer' ? bob : alice,
  users: [alice, bob, carl, dora],
  /** Password accepted, second factor outstanding. */
  pending: null as null | { user: User; step: 'totp' | 'enroll' },
  failures: new Map<string, number>([['dora', 5]]),
  lockedUntil: new Map<string, number>([['dora', Date.parse(dora.twoFactorLockedUntil!)]]),
  projects: [] as Project[],
  issues: {} as Record<string, Issue[]>,
  sessions: new Map<string, AgentSession>(),
  events: new Map<string, FactoryEvent[]>(),
  sessionSubs: new Map<string, Set<(e: FactoryEvent) => void>>(),
  /** Close handlers of open session sockets (the server closes them when the chat is closed). */
  sessionClosers: new Map<string, Set<(info: { code: number; reason: string }) => void>>(),
  /** Ids of closed (deleted) chats: late timers for them do nothing. */
  closedSessions: new Set<string>(),
  feedSubs: new Set<(f: FeedFrame) => void>(),
  runs: new Map<string, Run>(),
  /** Release plans (docs/ARCHITECTURE.md §6 "Releases"). */
  releases: new Map<string, Release>(),
  /** `.agern/sandbox.yml` on each project's default branch (precomputed hash and validation; the mock parses no YAML). */
  sandboxFiles: new Map<string, MockSandboxFile>(),
  /** Admin approvals by content hash, one per project. */
  sandboxApprovals: new Map<string, SandboxApproval>(),
  /** FACTORY_SANDBOX is not off (scenario `nosandbox`: switched off). */
  sandboxEnabled: scenario !== 'nosandbox',
  /** Sandboxes that exist right now, by run id (created when an approved run's builder starts, removed after review). */
  runningSandboxes: new Map<string, { createdAt: string; builderSessionId: string; containers: { platform: string; image: string; createdAt: string }[] }>(),
  players: new Map<string, Player>(),
  settings: {
    agentsMayInstallSoftware: false,
    chatModel: 'opus',
    pipelineModels: { 'spec-writer': 'opus', builder: 'opus', security: 'opus', ux: 'sonnet', 'ui-design': 'sonnet', code: 'sonnet', approver: 'opus' },
    maxFixRounds: 2,
  } as Settings,
  softwareRequests: [] as SoftwareRequest[],
  /** Personal choices per username (like the backend's user_preferences). */
  prefs: new Map<string, PreferenceOverrides>(),
  /** Claude usage per username (like the backend's claude_usage_windows and claude_spend_daily). */
  usage: new Map<string, MockUsage>(),
};

// ---------------------------------------------------------------- Claude usage (docs/ARCHITECTURE.md §5)

type MockUsage = { windows: UsageWindow[]; spend: Map<string, number> };

const utcDay = (ms: number) => iso(ms).slice(0, 10);

function seedUsage() {
  const now = Date.now();
  const window = (type: string, utilization: number, status: string, resetsInMin: number, observedMinAgo: number): UsageWindow => ({
    type, utilization, status, resetsAt: iso(now + resetsInMin * 60_000), observedAt: iso(now - observedMinAgo * 60_000),
  });
  // Scenarios: ?mock=usage-none (no windows yet), usage-high (5-hour window at 86 %), usage-limit (5-hour limit reached).
  const fiveHour =
    scenario === 'usage-high' ? window('five_hour', 0.86, 'allowed_warning', 125, 4)
    : scenario === 'usage-limit' ? window('five_hour', 1.0, 'rejected', 80, 2)
    : window('five_hour', 0.42, 'allowed', 125, 4);
  const earlier = new Date(now);
  earlier.setUTCDate(1);
  const spend = (today: number, earlierInMonth: number) => {
    const m = new Map([[utcDay(now), today]]);
    if (utcDay(earlier.getTime()) !== utcDay(now)) m.set(utcDay(earlier.getTime()), earlierInMonth);
    return m;
  };
  db.usage.set('alice', { windows: scenario === 'usage-none' ? [] : [fiveHour, window('seven_day', 0.18, 'allowed', 3 * 24 * 60 + 200, 4)], spend: spend(1.84, 21.3) });
  db.usage.set('bob', { windows: [], spend: spend(0.62, 11.72) });
}
seedUsage();

const usageState = (u: User) => {
  let st = db.usage.get(u.username);
  if (!st) db.usage.set(u.username, (st = { windows: [], spend: new Map() }));
  return st;
};

/** `GET /api/me/usage` for [u]. */
export function usageOf(u: User): ClaudeUsage {
  const st = usageState(u);
  const kind = u.hasClaudeCredential ? u.claudeCredentialKind : null;
  const day = utcDay(Date.now());
  const month = day.slice(0, 7);
  let monthUsd = 0;
  for (const [d, v] of st.spend) if (d.startsWith(month)) monthUsd += v;
  return {
    credentialKind: kind,
    windows: kind === 'oauth_token' ? [...st.windows].sort((a, b) => a.type.localeCompare(b.type)) : [],
    spend: { todayUsd: st.spend.get(day) ?? 0, monthUsd, day, month },
  };
}

/** A result of one of [owner]'s agents cost [usd]: adds the spend and, on an OAuth token, nudges the 5-hour window. */
function recordUsage(owner: { username: string }, usd: number) {
  const u = db.users.find((x) => x.username === owner.username);
  if (!u || !u.hasClaudeCredential) return;
  const st = usageState(u);
  const now = Date.now();
  if (usd > 0) st.spend.set(utcDay(now), (st.spend.get(utcDay(now)) ?? 0) + usd);
  if (u.claudeCredentialKind === 'oauth_token') {
    const w = st.windows.find((x) => x.type === 'five_hour');
    if (w) {
      if (w.status !== 'rejected') w.utilization = Math.round(((w.utilization ?? 0) + 0.01) * 100) / 100;
      w.observedAt = iso(now);
    } else {
      st.windows.push({ type: 'five_hour', utilization: 0.03, status: 'allowed', resetsAt: iso(now + 5 * 3600_000), observedAt: iso(now) });
    }
  }
  // The frame is private to the user: in the mock the only listener is the signed-in user.
  if (u.username === db.me.username) feed({ type: 'usage' });
}

/** The credential was replaced or removed: its limit windows go, the spend stays. */
export function clearUsageWindows(u: User) {
  usageState(u).windows = [];
  if (u.username === db.me.username) feed({ type: 'usage' });
}

/** Same rule as the backend: the user's choice, else the admin default; fix rounds capped by the admin value. */
export function effective(username: string) {
  const o = db.prefs.get(username) ?? { chatModel: null, pipelineModels: {}, maxFixRounds: null };
  const s = db.settings;
  return {
    chatModel: o.chatModel ?? s.chatModel,
    model: (role: StageRole) => o.pipelineModels[role] ?? s.pipelineModels[role],
    maxFixRounds: Math.min(o.maxFixRounds ?? s.maxFixRounds, s.maxFixRounds),
  };
}

const ownerOf = (u: User) => ({ username: u.username, displayName: u.displayName });

// ---------------------------------------------------------------- broadcasting

function feed(frame: FeedFrame) {
  for (const s of db.feedSubs) s(structuredClone(frame));
}
export function touchSession(s: AgentSession, ts = iso(Date.now())) {
  if (db.closedSessions.has(s.id)) return;
  s.updatedAt = ts;
  feed({ type: 'session', session: s });
}
export function touchRun(r: Run, ts = iso(Date.now())) {
  r.updatedAt = ts;
  feed({ type: 'run', run: r });
}
/** Announces an issue created through Agern, in list shape (no body) like the server's `issue` frame. */
export function announceIssue(repo: string, issue: Issue) {
  const listed = { ...issue };
  delete listed.body;
  feed({ type: 'issue', repo, issue: listed });
}

function emit(sessionId: string, type: FactoryEvent['type'], payload: unknown, ts: string, live = false) {
  if (db.closedSessions.has(sessionId)) return;
  const list = db.events.get(sessionId) ?? [];
  db.events.set(sessionId, list);
  const ev: FactoryEvent = { seq: live ? -1 : list.length + 1, sessionId, ts, type, payload };
  if (!live) list.push(ev);
  for (const sub of db.sessionSubs.get(sessionId) ?? []) sub(structuredClone(ev));
}

function setStatus(s: AgentSession, status: SessionStatus, ts: string) {
  if (s.status === status) return;
  s.status = status;
  // Like the backend: a process exists from start until it ends; an idle chat keeps its process.
  if (status === 'starting' || status === 'running') s.live = true;
  else if (status !== 'idle') s.live = false;
  if (['done', 'failed', 'stopped'].includes(status)) s.endedAt = ts;
  emit(s.id, 'status', { status }, ts);
  touchSession(s, ts);
}

// ---------------------------------------------------------------- step player

const MODEL_IDS: Record<string, string> = { opus: 'claude-opus-4-5', sonnet: 'claude-sonnet-4-5', haiku: 'claude-haiku-4-5' };

/** Schedules callbacks on a cumulative timeline, either in real time or instantly with past timestamps. */
class Player {
  private t = 0;
  private timers: ReturnType<typeof setTimeout>[] = [];
  cancelled = false;
  constructor(
    readonly mode: 'live' | 'instant',
    private readonly startMs: number,
    private readonly scale = 25,
  ) {}
  after(ms: number, fn: (ts: string) => void) {
    this.t += ms;
    if (this.mode === 'instant') fn(iso(this.startMs + this.t * this.scale));
    else {
      const at = this.t;
      this.timers.push(setTimeout(() => !this.cancelled && fn(iso(Date.now())), at));
    }
  }
  get endMs() {
    return this.startMs + this.t * (this.mode === 'instant' ? this.scale : 1);
  }
  cancel() {
    this.cancelled = true;
    this.timers.forEach(clearTimeout);
  }
}

function shortArgs(name: string, input: Record<string, unknown>): string {
  const v = input.command ?? input.file_path ?? input.pattern ?? input.url ?? input.number ?? Object.values(input)[0];
  return `${name.replace(/^mcp__factory__/, '')}: ${String(v ?? '').slice(0, 60)}`;
}

function play(
  s: AgentSession,
  steps: Step[],
  p: Player,
  opts: { init?: boolean; userText?: string; onDone?: (endMs: number) => void },
) {
  const sid = s.id;
  const claudeSid = `c-${sid.slice(0, 8)}`;
  const model = MODEL_IDS[s.model] ?? s.model;
  let turns = 0;
  const startT = p.endMs;
  if (opts.userText !== undefined) {
    const text = opts.userText;
    p.after(0, (ts) => emit(sid, 'user', { text, username: s.owner.username }, ts));
  }
  p.after(opts.userText ? 150 : 0, (ts) => setStatus(s, 'running', ts));
  if (opts.init) {
    p.after(250, (ts) =>
      emit(sid, 'claude', {
        type: 'system',
        subtype: 'init',
        session_id: claudeSid,
        model,
        cwd: `/srv/agern/workspaces/${s.owner.username}/${s.repo}/${s.runId ? `runs/${s.runId.slice(0, 8)}` : 'main'}`,
        permissionMode: 'bypassPermissions',
        tools: ['Bash', 'Read', 'Edit', 'Write', 'Glob', 'Grep', 'WebFetch', 'TodoWrite', 'mcp__factory__factory_get_issue'],
        mcp_servers: [{ name: 'factory', status: 'connected' }],
      }, ts),
    );
  }
  const streamBlock = (kind: 'text' | 'thinking', text: string) => {
    const msgId = `msg_${uid().slice(0, 12)}`;
    if (p.mode === 'live') {
      p.after(kind === 'thinking' ? 500 : 700, (ts) =>
        emit(sid, 'claude', { type: 'stream_event', event: { type: 'message_start', message: { id: msgId, model } } }, ts, true),
      );
      p.after(20, (ts) =>
        emit(sid, 'claude', { type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: kind === 'text' ? { type: 'text', text: '' } : { type: 'thinking', thinking: '' } } }, ts, true),
      );
      const chunks = text.match(/[\s\S]{1,14}/g) ?? [];
      for (const c of chunks) {
        p.after(28, (ts) =>
          emit(sid, 'claude', { type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: kind === 'text' ? { type: 'text_delta', text: c } : { type: 'thinking_delta', thinking: c } } }, ts, true),
        );
      }
    }
    p.after(p.mode === 'live' ? 60 : 900, (ts) => {
      emit(sid, 'claude', {
        type: 'assistant',
        session_id: claudeSid,
        message: { id: msgId, role: 'assistant', model, content: [kind === 'text' ? { type: 'text', text } : { type: 'thinking', thinking: text }] },
      }, ts);
      if (kind === 'text') s.lastActivity = text.split('\n')[0].replace(/[*`#]/g, '').slice(0, 80);
      touchSession(s, ts);
    });
  };
  const system = (st: Extract<Step, { kind: 'system' }>, gap: number) =>
    p.after(gap, (ts) => emit(sid, 'system', { level: st.level, text: st.text, ...(st.sandbox ? { sandbox: st.sandbox } : {}) }, ts));
  for (const step of steps) {
    if (step.kind === 'pause') p.after(step.ms, () => {});
    else if (step.kind === 'system') system(step, step.ms ?? 300);
    else if (step.kind === 'think') streamBlock('thinking', step.text);
    else if (step.kind === 'text') {
      turns++;
      streamBlock('text', step.text);
    } else {
      turns++;
      const toolId = `toolu_${uid().slice(0, 12)}`;
      p.after(600, (ts) => {
        emit(sid, 'claude', {
          type: 'assistant',
          session_id: claudeSid,
          message: { id: `msg_${uid().slice(0, 12)}`, role: 'assistant', model, content: [{ type: 'tool_use', id: toolId, name: step.name, input: step.input }] },
        }, ts);
        s.lastActivity = shortArgs(step.name, step.input);
        touchSession(s, ts);
      });
      const logs = step.logs ?? [];
      logs.forEach((l) => system(l, Math.round((step.ms ?? 900) / (logs.length + 1))));
      p.after(logs.length ? Math.round((step.ms ?? 900) / (logs.length + 1)) : step.ms ?? 900, (ts) =>
        emit(sid, 'claude', {
          type: 'user',
          session_id: claudeSid,
          message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolId, content: step.result, is_error: !!step.isError }] },
        }, ts),
      );
    }
  }
  p.after(200, (ts) => {
    const cost = Math.round((0.02 + turns * (s.model === 'opus' ? 0.031 : 0.009)) * 10000) / 10000;
    s.costUsd = Math.round(((s.costUsd ?? 0) + cost) * 10000) / 10000;
    // Only live turns count: the seeded history is already part of the seeded usage.
    if (p.mode === 'live') recordUsage(s.owner, cost);
    s.numTurns = (s.numTurns ?? 0) + turns;
    const last = [...steps].reverse().find((x) => x.kind === 'text');
    emit(sid, 'claude', {
      type: 'result',
      subtype: 'success',
      is_error: false,
      session_id: claudeSid,
      duration_ms: Math.round(Date.parse(ts) - startT),
      num_turns: s.numTurns,
      total_cost_usd: s.costUsd,
      result: last && last.kind === 'text' ? last.text : '',
    }, ts);
    touchSession(s, ts);
    opts.onDone?.(Date.parse(ts));
  });
}

// ---------------------------------------------------------------- sessions

function newSession(
  repo: string,
  kind: SessionKind,
  owner: User,
  extra: Partial<AgentSession> = {},
  createdMs = Date.now(),
): AgentSession {
  const s: AgentSession = {
    id: uid(),
    repo,
    kind,
    model: kind === 'chat' ? effective(owner.username).chatModel : effective(owner.username).model(kind as StageRole),
    status: 'starting',
    owner: ownerOf(owner),
    title: null,
    runId: null,
    parentSessionId: null,
    issueNumber: null,
    createdAt: iso(createdMs),
    updatedAt: iso(createdMs),
    endedAt: null,
    costUsd: null,
    numTurns: null,
    lastActivity: null,
    live: false,
    ...extra,
  };
  db.sessions.set(s.id, s);
  db.events.set(s.id, []);
  return s;
}

export function createChat(repo: string, owner: User, createdMs = Date.now()): AgentSession {
  const s = newSession(repo, 'chat', owner, { title: 'Chat' }, createdMs);
  s.status = 'idle';
  emit(s.id, 'system', { level: 'info', text: `Chat started in ${ORG}/${repo} on main.` }, iso(createdMs));
  touchSession(s, iso(createdMs));
  return s;
}

function systemReply(s: AgentSession, text: string, level: 'info' | 'warn' | 'error' = 'info') {
  emit(s.id, 'system', { level, text }, iso(Date.now()));
}

export function chatInput(s: AgentSession, text: string, by: User, attachments: Attachment[] = []) {
  const ts = iso(Date.now());
  if (text.trim().startsWith('/factory')) {
    emit(s.id, 'user', { text, username: by.username }, ts);
    const [, cmd, arg] = text.trim().split(/\s+/);
    setTimeout(() => {
      if (db.closedSessions.has(s.id)) return;
      try {
        if (!cmd || cmd === 'help') {
          systemReply(s, ['/factory run <issue>      start a pipeline run for an issue', '/factory status           list runs in this project', '/factory approve [runId]  approve a run waiting for a human', '/factory cancel <runId>   cancel a run', '/factory releases         list the releases of this project and their progress', '/factory release create <name> [description]', '/factory release add <name> <issue>…', '/factory release remove <issue>…', '/factory release approve <name>  merge a release that is ready to release (owner only)', '/factory help             this help'].join('\n'));
        } else if (cmd === 'releases') {
          const list = releasesOf(s.repo);
          systemReply(s, list.length ? list.map(releaseSummary).join('\n\n') : `No releases in ${s.repo} yet.`);
        } else if (cmd === 'release') {
          const [, , sub, ...rest] = text.trim().split(/\s+/);
          const nums = (tokens: string[]) => tokens.flatMap((t) => t.split(',')).filter(Boolean).map((t) => Number(t.replace('#', '')));
          const find = (name: string) => releasesOf(s.repo).find((r) => r.name.toLowerCase() === name.toLowerCase() || r.id === name);
          if (sub === 'create' && rest[0]) {
            const r = createRelease(s.repo, { name: rest[0], description: rest.slice(1).join(' ') }, by);
            systemReply(s, `Created release "${r.name}".`);
          } else if (sub === 'add' && rest.length > 1) {
            const r = find(rest[0]);
            if (!r) throw new Error(`No release "${rest[0]}" in ${s.repo}. Releases: ${releasesOf(s.repo).map((x) => x.name).join(', ') || 'none'}.`);
            const n = nums(rest.slice(1));
            assignIssues(s.repo, n, r.id, by);
            systemReply(s, `Release "${r.name}": added ${n.map((x) => `#${x}`).join(', ')} (now ${r.issues.length} issue${r.issues.length === 1 ? '' : 's'}).`);
          } else if (sub === 'remove' && rest.length) {
            const n = nums(rest);
            assignIssues(s.repo, n, null, by);
            systemReply(s, `Removed ${n.map((x) => `#${x}`).join(', ')} from their release.`);
          } else if (sub === 'approve' && rest[0]) {
            const r = find(rest[0]);
            if (!r) throw new Error(`No release "${rest[0]}" in ${s.repo}. Releases: ${releasesOf(s.repo).map((x) => x.name).join(', ') || 'none'}.`);
            approveRelease(r, by);
            const k = r.issues.filter((i) => i.state === 'merged').length;
            systemReply(s, `Release "${r.name}" is released: pull request ${r.prUrl} merged into ${r.targetBranch ?? 'main'}, ${k} issue${k === 1 ? '' : 's'} closed.`);
          } else systemReply(s, 'Usage: /factory release create <name> [description] | add <name> <issue>… | remove <issue>… | approve <name>', 'warn');
        } else if (cmd === 'status') {
          const runs = [...db.runs.values()].filter((r) => r.repo === s.repo).slice(0, 6);
          systemReply(s, runs.length ? runs.map((r) => `${r.id.slice(0, 8)}  #${r.issueNumber}  ${r.status.padEnd(11)} ${r.issueTitle}`).join('\n') : 'No runs in this project.');
        } else if (cmd === 'run') {
          const r = startRun(s.repo, Number(arg?.replace('#', '')), by, s.id);
          systemReply(s, `Started run ${r.id.slice(0, 8)} for #${r.issueNumber} "${r.issueTitle}". Follow it at /p/${s.repo}/runs/${r.id}`);
        } else if (cmd === 'approve') {
          const r = arg ? findRun(arg) : [...db.runs.values()].find((x) => x.repo === s.repo && x.status === 'needs_human');
          if (!r) throw new Error('No run is waiting for approval.');
          approveRun(r, by);
          systemReply(s, `Approved run ${r.id.slice(0, 8)} — merging #${r.prNumber}.`);
        } else if (cmd === 'cancel') {
          const r = arg ? findRun(arg) : undefined;
          if (!r) throw new Error('Usage: /factory cancel <runId>');
          cancelRun(r);
          systemReply(s, `Cancelled run ${r.id.slice(0, 8)}.`);
        } else systemReply(s, `Unknown command "${cmd}". Try /factory help.`, 'warn');
      } catch (e) {
        systemReply(s, (e as Error).message, 'error');
      }
    }, 250);
    return;
  }
  if (attachments.length) {
    // Like the backend: the user event carries the attachments; the reply says what the agent received.
    emit(s.id, 'user', { text, username: by.username, attachments }, ts);
    const imgs = attachments.filter((a) => a.kind === 'image' && a.inline !== 'none').length;
    const steps: Step[] = [
      { kind: 'think', text: 'The user attached files; they are saved outside the repository.' },
      { kind: 'text', text: `I received ${attachments.length} attachment${attachments.length === 1 ? '' : 's'}${imgs ? ` (${imgs} shown to me as image${imgs === 1 ? '' : 's'})` : ''}: ${attachments.map((a) => `\`${a.name}\``).join(', ')}. ${text.trim() ? 'What would you like me to do with them?' : 'Tell me what to do with them.'}` },
    ];
    const p = new Player('live', Date.now());
    db.players.set(s.id, p);
    play(s, steps, p, { init: (db.events.get(s.id) ?? []).every((e) => e.type !== 'claude'), onDone: () => (db.players.delete(s.id), setStatus(s, 'idle', iso(Date.now()))) });
    return;
  }
  if (db.players.has(s.id)) {
    // Like the backend: input during a turn is accepted and delivered when the turn ends.
    const q = queued.get(s.id) ?? [];
    q.push(text);
    queued.set(s.id, q);
    return;
  }
  const reply = chatReplies.find((r) => r.match.test(text))!;
  const p = new Player('live', Date.now());
  db.players.set(s.id, p);
  const first = (db.events.get(s.id) ?? []).every((e) => e.type !== 'claude');
  play(s, reply.steps, p, {
    userText: text,
    init: first,
    onDone: () => {
      db.players.delete(s.id);
      setStatus(s, 'idle', iso(Date.now()));
      const next = queued.get(s.id)?.shift();
      if (next !== undefined) chatInput(s, next, by);
    },
  });
}

const queued = new Map<string, string[]>();

export function interrupt(s: AgentSession) {
  const p = db.players.get(s.id);
  if (!p) return;
  p.cancel();
  db.players.delete(s.id);
  const ts = iso(Date.now());
  emit(s.id, 'claude', { type: 'result', subtype: 'error_during_execution', is_error: true, num_turns: s.numTurns ?? 0, total_cost_usd: s.costUsd ?? 0, duration_ms: 0, result: '' }, ts);
  emit(s.id, 'system', { level: 'warn', text: 'Interrupted by user' }, ts);
  setStatus(s, 'idle', ts);
}

/** Like the backend: the owner's message to a running spec-writer / builder is queued, then picked up at its next step. */
export function messageAgent(s: AgentSession, text: string, by: User): AgentMessage {
  if (s.owner.username !== by.username) throw new MockError(403, 'forbidden', 'Only the owner of this run can send messages to its agents');
  if (!STEERABLE_ROLES.includes(s.kind as StageRole)) {
    throw new MockError(409, 'agent_does_not_accept_messages', `The ${s.kind} agent does not accept messages: reviewers and the approver answer in a fixed verdict format. Only a running spec-writer or builder can be messaged.`);
  }
  if (!text.trim()) throw new MockError(400, 'bad_request', 'text must not be empty');
  if (s.status !== 'running' || !db.players.has(s.id)) {
    throw new MockError(409, 'agent_not_running', `The ${s.kind} agent is no longer running (it is ${s.status}), so the message cannot be delivered. To change finished work, start a new run instead.`);
  }
  const messageId = uid();
  emit(s.id, 'user', { text, username: by.username, messageId, origin: 'direct' }, iso(Date.now()));
  setTimeout(() => {
    const picked = db.players.has(s.id);
    emit(s.id, 'system', picked
      ? { level: 'info', text: `Message from ${by.username} delivered to the agent.`, messageId }
      : { level: 'warn', text: `Message from ${by.username} was not delivered: the agent stopped before picking it up.`, messageId }, iso(Date.now()));
  }, 1500);
  return { status: 'queued', messageId, sessionId: s.id, runId: s.runId, role: s.kind as StageRole, note: 'Queued — delivered at the agent’s next step (after its current tool call, or as a further turn if it is finishing).' };
}

export function stopSession(s: AgentSession) {
  db.players.get(s.id)?.cancel();
  db.players.delete(s.id);
  setStatus(s, 'stopped', iso(Date.now()));
}

/**
 * Like the backend's `DELETE /api/sessions/{id}`: stops the chat's agent and deletes the chat with its events; runs it
 * started lose the link. Its sockets are closed with 4404 and the feed announces `session_closed`.
 */
export function closeChat(s: AgentSession) {
  db.players.get(s.id)?.cancel();
  db.players.delete(s.id);
  queued.delete(s.id);
  db.closedSessions.add(s.id);
  db.sessions.delete(s.id);
  db.events.delete(s.id);
  for (const r of db.runs.values()) if (r.startedBySessionId === s.id) r.startedBySessionId = null;
  const closers = [...(db.sessionClosers.get(s.id) ?? [])];
  db.sessionSubs.delete(s.id);
  db.sessionClosers.delete(s.id);
  for (const close of closers) close({ code: 4404, reason: 'session closed' });
  feed({ type: 'session_closed', repo: s.repo, sessionId: s.id });
}

// ---------------------------------------------------------------- runs

type Plan = {
  changes: Partial<Record<StageRole, string[]>>; // round-0 reviewers that ask for changes, with findings
  approver: 'APPROVE' | 'ESCALATE';
  liveFrom: (role: StageRole, round: number) => boolean;
};

const reviewers: StageRole[] = ['security', 'ux', 'ui-design', 'code'];
const reviewerMs: Record<string, number> = { security: 1.3, ux: 0.8, 'ui-design': 0.6, code: 1.1 };
const passSummary: Record<string, string> = {
  security: 'Cart ids are checked against the session user; queries are parameterised.',
  ux: 'Anonymous carts merge on login without surprising the user.',
  'ui-design': 'Drawer states are consistent with the design system.',
  code: 'Clear naming, good test coverage, transactions are scoped correctly.',
};

function findRun(idOrPrefix: string) {
  return db.runs.get(idOrPrefix) ?? [...db.runs.values()].find((r) => r.id.startsWith(idOrPrefix));
}

function setRunStatus(r: Run, status: Run['status'], ts: string) {
  r.status = status;
  if (['merged', 'failed', 'cancelled'].includes(status)) {
    r.endedAt = ts;
    const issue = db.issues[r.repo]?.find((i) => i.number === r.issueNumber);
    if (issue) {
      issue.activeRunId = null;
      // A release run merges into the release branch; its issue stays open until the release is merged.
      if (status === 'merged' && !r.releaseId) issue.state = 'closed';
    }
  }
  touchRun(r, ts);
  if (r.releaseId && ['merged', 'needs_human', 'failed', 'cancelled'].includes(status)) releaseRunEnded(r);
}

/** Like the backend: stages exist as `pending` before they run (round 0 at creation, fix rounds when they start). */
function pendingStage(r: Run, role: StageRole, round: number): Stage {
  const st: Stage = { id: uid(), role, round, model: effective(r.owner.username).model(role), status: 'pending', verdict: null, summary: null, findings: [], sessionId: null, startedAt: null, endedAt: null };
  r.stages.push(st);
  return st;
}

function runStage(r: Run, role: StageRole, round: number, plan: Plan, startMs: number, owner: User, done: (endMs: number, stage: Stage) => void) {
  const live = plan.liveFrom(role, round);
  const p = new Player(live ? 'live' : 'instant', live ? Date.now() : startMs);
  const prevBuilder = [...r.stages].reverse().find((x) => x.role === 'builder' && x.sessionId);
  // A fix round is a new session that resumes the previous builder's conversation (--resume).
  const s = newSession(r.repo, role, owner, {
    runId: r.id,
    issueNumber: r.issueNumber,
    title: `${role} · #${r.issueNumber}${round > 0 ? ` · round ${round}` : ''}`,
    parentSessionId: role === 'builder' && round > 0 ? prevBuilder?.sessionId ?? null : null,
  }, p.endMs);
  const stage = r.stages.find((x) => x.role === role && x.round === round && x.status === 'pending') ?? pendingStage(r, role, round);
  Object.assign(stage, { status: 'running', model: s.model, sessionId: s.id, startedAt: iso(p.endMs) });
  touchRun(r, iso(p.endMs));
  if (live) db.players.set(s.id, p);

  let steps: Step[];
  let verdict: Verdict | null = null;
  let summary = '';
  let findings: Stage['findings'] = [];
  if (role === 'spec-writer') {
    steps = specScript(r.issueNumber, r.issueTitle);
    summary = 'Spec posted as an issue comment.';
  } else if (role === 'builder') {
    const prev = r.stages.filter((x) => x.round === round - 1 && x.verdict === 'CHANGES').flatMap((x) => x.findings.map((f) => f.detail));
    const sb = r.sandbox;
    const def = sb?.state === 'approved' ? db.sandboxFiles.get(r.repo)?.definition : null;
    steps = def ? sandboxBuilderScript(round, def.image, def.setup, def.services.map((x) => x.name), def.platforms) : builderScript(round, prev);
    // Like the backend: the builder's transcript starts with the run's sandbox decision.
    if (sb && sb.state !== 'disabled') steps = [{ kind: 'system', level: sb.state === 'approved' ? 'info' : 'warn', text: `Sandbox: ${sb.reason}`, ms: 50 }, ...steps];
    if (def && !db.runningSandboxes.has(r.id)) {
      const at = iso(Math.min(Date.now(), p.endMs + 4000));
      // Like the backend: one container per image used — the default one, then each platform the builder tests on.
      const containers = [{ platform: 'default', image: def.image, createdAt: at }, ...def.platforms.map((x) => ({ platform: x.name, image: x.image, createdAt: at }))];
      db.runningSandboxes.set(r.id, { createdAt: at, builderSessionId: s.id, containers });
      if (sb && def.platforms.length) sb.platformsUsed = ['default', ...def.platforms.map((x) => x.name)];
      feed({ type: 'sandboxes', runId: r.id, change: 'created' });
    }
    summary = round > 0 ? 'Addressed review findings; tests green.' : 'Implemented the spec; 9 tests added, all green.';
  } else if (role === 'approver') {
    verdict = plan.approver;
    steps = approverScript(plan.approver, `${r.issueTitle} (#${r.issueNumber})`);
    summary = plan.approver === 'APPROVE'
      ? 'All reviewers pass; merging.\n\n- **Security:** cart ownership is checked in `CartRoutes.kt`.\n- **Code:** quantity handling now lives in `mergeQuantities()`.\n- **Tests:** `./gradlew build` is green.'
      : 'New payment-provider dependency not covered by the spec. A human should confirm.';
  } else {
    const ch = round === 0 ? plan.changes[role] : undefined;
    verdict = ch ? 'CHANGES' : 'PASS';
    summary = ch ? `${ch.length} issue${ch.length > 1 ? 's' : ''} to fix before this can merge.` : passSummary[role];
    findings = ch
      ? ch.map((d, i) => ({ severity: i === 0 ? (role === 'security' ? 'high' : 'medium') : 'low', file: role === 'security' ? 'src/main/kotlin/shop/cart/CartRoutes.kt' : 'src/main/kotlin/shop/cart/CartService.kt', detail: d }))
      : role === 'ux'
        ? [{ severity: 'info', file: null, detail: 'Consider a toast when carts are merged on login.' }]
        : [];
    steps = reviewerScript(role, verdict, summary);
    if (live) steps = [{ kind: 'pause', ms: reviewerMs[role] * 1500 }, ...steps];
  }
  play(s, steps, p, {
    init: true,
    onDone: (endMs) => {
      db.players.delete(s.id);
      const ts = iso(endMs);
      setStatus(s, 'done', ts);
      stage.status = 'done';
      stage.verdict = verdict;
      stage.summary = summary;
      stage.findings = findings;
      stage.endedAt = ts;
      touchRun(r, ts);
      done(endMs, stage);
    },
  });
}

function pipeline(r: Run, plan: Plan, owner: User, startMs: number) {
  const fail = () => r.status === 'cancelled';
  const spec = () => {
    setRunStatus(r, 'spec', iso(startMs));
    runStage(r, 'spec-writer', 0, plan, startMs, owner, (t) => !fail() && build(0, t));
  };
  const build = (round: number, t: number) => {
    r.round = round;
    if (round > 0) for (const role of ['builder', ...reviewers] as StageRole[]) pendingStage(r, role, round);
    setRunStatus(r, 'building', iso(t));
    runStage(r, 'builder', round, plan, t, owner, (t2) => {
      if (fail()) return;
      if (!r.prNumber) {
        r.prNumber = 80 + (r.issueNumber % 17);
        r.prUrl = `https://github.com/${ORG}/${r.repo}/pull/${r.prNumber}`;
      }
      review(round, t2);
    });
  };
  const review = (round: number, t: number) => {
    setRunStatus(r, 'reviewing', iso(t));
    let pending = reviewers.length;
    let end = t;
    for (const role of reviewers) {
      runStage(r, role, round, plan, t, owner, (t2) => {
        end = Math.max(end, t2);
        if (--pending > 0 || fail()) return;
        const changes = r.stages.some((x) => x.round === round && x.verdict === 'CHANGES');
        if (changes && round < r.maxRounds) build(round + 1, end + 400);
        else if (changes) {
          for (const st of r.stages) if (st.role === 'approver' && st.status === 'pending') st.status = 'skipped';
          r.error = `Reviewers still request changes after ${round} fix round(s).`;
          dropSandbox(r.id);
          setRunStatus(r, 'needs_human', iso(end));
        } else approve(round, end + 400);
      });
    }
  };
  const approve = (round: number, t: number) => {
    dropSandbox(r.id);
    for (const st of r.stages) if (st.role === 'approver' && st.status === 'pending') st.round = round;
    setRunStatus(r, 'approving', iso(t));
    runStage(r, 'approver', round, plan, t, owner, (t2, stage) => {
      if (fail()) return;
      if (stage.verdict === 'ESCALATE') r.error = `The approver escalated: ${stage.summary}`;
      setRunStatus(r, stage.verdict === 'APPROVE' ? 'merged' : 'needs_human', iso(t2 + 1500));
    });
  };
  spec();
}

// ---------------------------------------------------------------- sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes")

export type MockSandboxFile = { content: string; sha256: string; definition: SandboxDefinition | null; errors: string[] };

export function sandboxStatus(repo: string, branch = 'main'): SandboxStatus {
  const f = db.sandboxFiles.get(repo);
  const approval = db.sandboxApprovals.get(repo) ?? null;
  const approvers = db.users.filter((u) => u.isAdmin).map(ownerOf);
  const base = { repo, enabled: db.sandboxEnabled, path: '.agern/sandbox.yml', branch, approval, approvers };
  if (!f) return { ...base, state: 'none', sha256: null, content: null, errors: [], definition: null };
  const state = f.definition === null ? 'invalid' : approval?.sha256 === f.sha256 ? 'approved' : 'pending';
  return { ...base, state, sha256: f.sha256, content: f.content, errors: f.errors, definition: f.definition };
}

export function approveSandbox(repo: string, sha256: string, by: User): SandboxStatus {
  if (!by.isAdmin) throw new MockError(403, 'forbidden', 'Only an admin can approve a sandbox definition');
  const f = db.sandboxFiles.get(repo);
  if (!f) throw new MockError(404, 'not_found', `${repo} has no .agern/sandbox.yml on main`);
  if (f.sha256 !== sha256.trim().toLowerCase()) {
    throw new MockError(409, 'sandbox_definition_changed', `.agern/sandbox.yml on main has changed (it is now ${f.sha256}); review it again`);
  }
  if (!f.definition) throw new MockError(422, 'sandbox_definition_invalid', `The definition is invalid: ${f.errors.join('; ')}`);
  const approval = { sha256: f.sha256, content: f.content, approvedBy: ownerOf(by), approvedAt: iso(Date.now()) };
  db.sandboxApprovals.set(repo, approval);
  feed({ type: 'sandbox_approval', repo, approval });
  return sandboxStatus(repo);
}

export function revokeSandboxApproval(repo: string, by: User): SandboxStatus {
  if (!by.isAdmin) throw new MockError(403, 'forbidden', 'Only an admin can revoke a sandbox approval');
  if (db.sandboxApprovals.delete(repo)) feed({ type: 'sandbox_approval', repo, approval: null });
  return sandboxStatus(repo);
}

/** Like the backend: the run's sandbox containers are gone; admins hear about it on the feed. */
function dropSandbox(runId: string, change: 'removed' | 'stopped' = 'removed') {
  if (db.runningSandboxes.delete(runId)) feed({ type: 'sandboxes', runId, change });
}

export function sandboxOverview(): SandboxOverview {
  const sandboxes: RunningSandbox[] = [];
  for (const [runId, sb] of db.runningSandboxes) {
    const r = db.runs.get(runId);
    const def = r && db.sandboxFiles.get(r.repo)?.definition;
    if (!r || !def) continue;
    sandboxes.push({
      runId, repo: r.repo, visible: true, issueNumber: r.issueNumber, issueTitle: r.issueTitle, runStatus: r.status, owner: r.owner,
      image: def.image, services: def.services.map((x) => x.name), sha256: r.sandbox?.sha256 ?? '', createdAt: sb.createdAt,
      cpus: def.limits.cpus, memory: def.limits.memory,
      containers: sb.containers, platforms: def.platforms.map((x) => x.name), slots: sb.containers.length,
      memoryTotal: `${parseInt(def.limits.memory, 10) * (sb.containers.length + def.services.length)}${def.limits.memory.replace(/^[0-9]+/, '')}`,
    });
  }
  sandboxes.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return {
    enabled: db.sandboxEnabled,
    runtime: db.sandboxEnabled ? 'podman' : 'off',
    inUse: sandboxes.reduce((n, x) => n + x.slots, 0),
    limits: { maxCpus: 2, maxMemory: '4g', maxConcurrent: 4, execTimeoutMinutes: 30, registries: ['docker.io', 'ghcr.io', 'quay.io', 'mcr.microsoft.com'], maxPlatforms: 4 },
    sandboxes,
  };
}

export function pendingSandboxes(): PendingSandbox[] {
  const out: PendingSandbox[] = [];
  for (const [repo, f] of db.sandboxFiles) {
    const approved = db.sandboxApprovals.get(repo)?.sha256 ?? null;
    if (f.definition && approved !== f.sha256) out.push({ repo, sha256: f.sha256, image: f.definition.image, approvedSha256: approved });
  }
  return out.sort((a, b) => a.repo.localeCompare(b.repo));
}

/** Like the backend: removes the run's sandbox now; the run continues (its builder's next command would create a new one). */
export function stopSandbox(runId: string, by: User): SandboxOverview {
  const sb = db.runningSandboxes.get(runId);
  if (!sb) throw new MockError(404, 'not_found', `Run ${runId} has no sandbox right now`);
  dropSandbox(runId, 'stopped');
  emit(sb.builderSessionId, 'system', {
    level: 'warn',
    text: `An admin (${by.username}) stopped this run's sandbox: its containers and network were removed (cache volumes are kept). The run continues; the next sandbox command creates a new sandbox and runs the setup script again.`,
  }, iso(Date.now()));
  return sandboxOverview();
}

/** What a run starting now decides, like the backend's RunSandbox. */
function runSandboxFor(repo: string): RunSandbox {
  const st = sandboxStatus(repo);
  const short = st.sha256?.slice(0, 12);
  if (!st.enabled) return { state: 'disabled', reason: 'Sandboxes are switched off on this server (FACTORY_SANDBOX=off).', sha256: null, image: null, platforms: [], platformsUsed: [] };
  switch (st.state) {
    case 'none':
      return { state: 'none', reason: 'The repository has no .agern/sandbox.yml on its default branch (origin/main).', sha256: null, image: null, platforms: [], platformsUsed: [] };
    case 'invalid':
      return { state: 'invalid', reason: `.agern/sandbox.yml on origin/main is invalid: ${st.errors.join('; ')}`, sha256: st.sha256, image: null, platforms: [], platformsUsed: [] };
    case 'pending':
      return {
        state: 'unapproved',
        reason: `.agern/sandbox.yml (${short}) is not approved: ${st.approval ? `it changed since an admin approved version ${st.approval.sha256.slice(0, 12)}` : 'no version of it has been approved by an admin'}. An admin can approve it in the project's sandbox settings.`,
        sha256: st.sha256,
        image: st.definition?.image ?? null,
        platforms: [],
        platformsUsed: [],
      };
    case 'approved':
      return {
        state: 'approved',
        reason: `Sandbox definition ${short} approved by ${st.approval!.approvedBy.username}; image ${st.definition!.image}${st.definition!.platforms.length ? `; platforms ${st.definition!.platforms.map((x) => `${x.name} (${x.image})`).join(', ')}` : ''}. It is created on the builder's first sandbox command.`,
        sha256: st.sha256,
        image: st.definition!.image,
        platforms: st.definition!.platforms.map((x) => x.name),
        platformsUsed: [],
      };
  }
}

function makeRun(repo: string, issue: Issue, owner: User, createdMs: number, startedBy: string | null, release: Release | null): Run {
  const slug = issue.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 30);
  const id = uid();
  // Like the server: every run has its own branch, suffixed with the first 8 characters of its id.
  const suffix = id.replace(/[^a-z0-9]/gi, '').slice(0, 8).toLowerCase();
  const r: Run = {
    id,
    repo,
    issueNumber: issue.number,
    issueTitle: issue.title,
    issueKind: issue.kind,
    status: 'queued',
    round: 0,
    maxRounds: effective(owner.username).maxFixRounds,
    branch: release
      ? `factory/release-${release.name}/issue-${issue.number}-${slug}-${suffix}`
      : `factory/issue-${issue.number}-${slug}-${suffix}`,
    prNumber: null,
    prUrl: null,
    owner: ownerOf(owner),
    startedBySessionId: startedBy,
    error: null,
    createdAt: iso(createdMs),
    updatedAt: iso(createdMs),
    endedAt: null,
    stages: [],
    sandbox: runSandboxFor(repo),
    releaseId: release?.id ?? null,
    baseBranch: release ? `release/${release.name}` : null,
  };
  for (const role of ['spec-writer', 'builder', ...reviewers, 'approver'] as StageRole[]) pendingStage(r, role, 0);
  db.runs.set(r.id, r);
  issue.activeRunId = r.id;
  return r;
}

export class MockError extends Error {
  constructor(public status: number, public code: string, message: string, public lockedUntil: string | null = null) {
    super(message);
  }
}

export function startRun(repo: string, issueNumber: number, owner: User, startedBy: string | null = null, plan?: Partial<Plan>, createdMs = Date.now(), release: Release | null = null): Run {
  const issue = db.issues[repo]?.find((i) => i.number === issueNumber);
  if (!issue) throw new MockError(404, 'not_found', `Issue #${issueNumber} not found in ${repo}.`);
  if (issue.activeRunId) throw new MockError(409, 'active_run_exists', `Issue #${issueNumber} already has an active run`);
  const r = makeRun(repo, issue, owner, createdMs, startedBy, release);
  touchRun(r, iso(createdMs));
  const full: Plan = {
    changes: { code: ['Merge logic duplicates quantity handling from `CheckoutService`; extract a **shared helper**.'], security: ['Cart routes accept any cart id; ownership is not checked (**IDOR**).', 'Log line prints the full cart including email.'] },
    approver: 'APPROVE',
    liveFrom: () => true,
    ...plan,
  };
  if (createdMs < Date.now() - 1000) pipeline(r, full, owner, createdMs);
  else setTimeout(() => pipeline(r, full, owner, Date.now()), 900);
  return r;
}

export function approveRun(r: Run, by: User) {
  if (r.owner.username !== by.username) throw new MockError(403, 'forbidden', 'Only the owner can approve this run.');
  if (r.status !== 'needs_human') throw new MockError(409, 'conflict', 'Run is not waiting for a human.');
  r.error = null;
  setRunStatus(r, 'merged', iso(Date.now()));
}

export function cancelRun(r: Run) {
  for (const st of r.stages) {
    if (st.status === 'running' && st.sessionId) {
      const s = db.sessions.get(st.sessionId);
      if (s) stopSession(s);
      st.status = 'failed';
      st.endedAt = iso(Date.now());
    } else if (st.status === 'pending') {
      st.status = 'skipped';
      st.endedAt = iso(Date.now());
    }
  }
  dropSandbox(r.id);
  setRunStatus(r, 'cancelled', iso(Date.now()));
}

// ---------------------------------------------------------------- releases (same rules and error codes as the server)

const RELEASE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$/;
const RELEASE_NAME_RULE =
  "A release name is 1 to 50 letters, digits, '.', '_' or '-', starts with a letter or digit, and must not contain '..' or end with '.' or '.lock'.";

/** Strictly increasing `updatedAt`, like the server's touch. */
function touchRelease(r: Release) {
  const now = Date.now();
  r.updatedAt = iso(Math.max(now, Date.parse(r.updatedAt) + 1));
  feed({ type: 'release', repo: r.repo, release: r });
}

function validRelease(repo: string, rawName: string, rawDescription: string, self?: string) {
  const name = rawName.trim();
  if (!RELEASE_NAME.test(name) || name.includes('..') || name.endsWith('.') || name.endsWith('.lock')) throw new MockError(400, 'bad_request', RELEASE_NAME_RULE);
  const description = rawDescription.trim();
  if (description.length > 2000) throw new MockError(400, 'bad_request', 'The description is too long (at most 2000 characters)');
  const taken = [...db.releases.values()].some((r) => r.repo === repo && r.id !== self && r.name.toLowerCase() === name.toLowerCase());
  if (taken) throw new MockError(409, 'release_name_taken', `A release named "${name}" already exists in this project`);
  return { name, description };
}

export const releasesOf = (repo: string) =>
  [...db.releases.values()].filter((r) => r.repo === repo).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id));

export function releaseOfIssue(repo: string, n: number): { id: string; name: string } | null {
  const r = releasesOf(repo).find((x) => x.issues.some((i) => i.number === n));
  return r ? { id: r.id, name: r.name } : null;
}

export function getRelease(id: string): Release {
  const r = db.releases.get(id);
  if (!r) throw new MockError(404, 'not_found', 'Release not found');
  return r;
}

function validMode(raw: string | undefined): ReleaseMode {
  const m = (raw ?? '').trim().toLowerCase() || 'manual';
  if (m !== 'manual' && m !== 'automatic') throw new MockError(400, 'bad_request', 'The release mode must be "manual" or "automatic"');
  return m;
}

export function createRelease(
  repo: string,
  body: { name: string; description: string; mode?: ReleaseMode },
  by: User,
  createdMs = Date.now(),
): Release {
  const { name, description } = validRelease(repo, body.name, body.description ?? '');
  const mode = validMode(body.mode);
  const r: Release = {
    id: uid(),
    repo,
    name,
    description,
    status: 'planning',
    createdBy: ownerOf(by),
    createdAt: iso(createdMs),
    updatedAt: iso(createdMs),
    issues: [],
    owner: null,
    branch: null,
    startedAt: null,
    endedAt: null,
    pause: null,
    scheduledAt: null,
    waitingFor: null,
    startFailure: null,
    prNumber: null,
    prUrl: null,
    targetBranch: null,
    mergeState: 'unknown',
    mergeMethod: null,
    notice: null,
    readyAt: null,
    releasedAt: null,
    releasedBy: null,
    mergeCommit: null,
    mode,
    releasedAutomatically: false,
    checks: null,
  };
  db.releases.set(r.id, r);
  feed({ type: 'release', repo, release: r });
  return r;
}

const RELEASE_STATUS_TEXT: Record<Release['status'], string> = {
  planning: 'in preparation',
  scheduled: 'scheduled',
  building: 'building',
  paused: 'paused',
  ready: 'ready to release',
  released: 'released',
  cancelled: 'cancelled',
};

/** One release for `/factory releases`: status, progress, and each issue's state when it is not waiting. */
function releaseSummary(r: Release): string {
  const n = r.issues.length;
  const merged = r.issues.filter((i) => i.state === 'merged').length;
  const planned = r.status === 'planning' || r.status === 'scheduled';
  const progress = planned ? `${n} issue${n === 1 ? '' : 's'}` : `${merged} of ${n} merged`;
  const lines = r.issues.map((i) => `    #${i.number} ${i.title}${i.state === 'waiting' ? '' : ` — ${i.state}`}${(i.state === 'held' || i.state === 'skipped') && i.note ? ` (${i.note})` : ''}`);
  return [`• ${r.name} — ${planned ? releaseState(r) : RELEASE_STATUS_TEXT[r.status]}, ${progress}`, ...lines].join('\n');
}

const isActiveRelease = (r: Release) => r.status === 'building' || r.status === 'paused';
/** Started and still open: building, paused or ready (owner rules apply). */
const isStartedOpen = (r: Release) => isActiveRelease(r) || r.status === 'ready';

function notEditable(r: Release): never {
  throw new MockError(409, 'release_not_editable', `Release "${r.name}" is released and kept as history.`);
}

/**
 * Who may change a started release (a `planning` release: anyone who can see the project). `owner`: only the user who
 * started it; `ownerOrAdmin`: admins too. Same 403 message as the server.
 */
function guardRelease(r: Release, by: User, who: 'owner' | 'ownerOrAdmin') {
  if (r.status === 'planning' || r.status === 'scheduled' || !r.owner) return;
  if (r.owner.username === by.username || (who === 'ownerOrAdmin' && by.isAdmin)) return;
  throw new MockError(403, 'forbidden', `Only ${r.owner.username}, who started this release, ${who === 'ownerOrAdmin' ? 'or an admin ' : ''}can do this`);
}

/**
 * Manual or automatic (as the server: planned → anyone; scheduled or started → automatic owner only, manual owner or
 * admin; released or cancelled → 409). Switched to automatic, a ready release that is green goes out at once.
 */
export function setReleaseMode(r: Release, rawMode: string, by: User) {
  const mode = validMode(rawMode);
  if (r.status === 'released') notEditable(r);
  if (r.status === 'cancelled') throw new MockError(409, 'release_closed', 'The release is cancelled');
  if (r.status !== 'planning') guardRelease(r, by, mode === 'automatic' ? 'owner' : 'ownerOrAdmin');
  if (r.mode === mode) return r;
  r.mode = mode;
  touchRelease(r);
  evaluateRelease(r);
  return r;
}

export function updateRelease(r: Release, body: { name: string; description: string }, by: User) {
  if (r.status === 'released') notEditable(r);
  guardRelease(r, by, 'ownerOrAdmin');
  const v = validRelease(r.repo, body.name, body.description ?? '', r.id);
  if (r.status !== 'planning' && r.status !== 'scheduled' && v.name !== r.name) throw new MockError(409, 'release_name_locked', 'The name is fixed once the release is started');
  Object.assign(r, v);
  touchRelease(r);
  return r;
}

export function deleteRelease(r: Release, by: User) {
  if (r.status === 'scheduled') throw new MockError(409, 'release_scheduled', 'Cancel the scheduled start before deleting this release');
  if (r.status === 'released') notEditable(r);
  if (isStartedOpen(r)) throw new MockError(409, 'release_active', 'Cancel the release first');
  guardRelease(r, by, 'ownerOrAdmin');
  db.releases.delete(r.id);
  feed({ type: 'release_deleted', repo: r.repo, releaseId: r.id });
}

/** Assigns (moving from other releases) or, with `releaseId` null, unassigns. All-or-nothing. */
export function assignIssues(repo: string, numbers: number[], releaseId: string | null, by: User) {
  if (!numbers.length || numbers.length > 100 || numbers.some((n) => !Number.isInteger(n) || n <= 0)) throw new MockError(400, 'bad_request', 'Give 1 to 100 issue numbers');
  const nums = [...new Set(numbers)];
  const changed = new Set<Release>();
  const sourceOf = (n: number) => releasesOf(repo).find((r) => r.issues.some((i) => i.number === n));
  let target: Release | null = null;
  if (releaseId !== null) {
    target = db.releases.get(releaseId) ?? null;
    if (!target || target.repo !== repo) throw new MockError(404, 'not_found', 'Release not found');
    if (target.status === 'cancelled') throw new MockError(409, 'release_closed', `Release "${target.name}" is cancelled; it takes no more issues`);
    if (target.status === 'released') notEditable(target);
    guardRelease(target, by, 'owner');
  }
  // Check everything before changing anything.
  const moving = nums.flatMap((n) => {
    const i = db.issues[repo]?.find((x) => x.number === n);
    if (target) {
      if (!i) throw new MockError(404, 'not_found', `Issue #${n} not found`);
      if (i.state !== 'open') throw new MockError(409, 'issue_closed', `Issue #${n} is closed; only open issues can be planned in a release`);
    }
    const source = sourceOf(n);
    if (!source || source === target) return [];
    if (source.status === 'released') notEditable(source);
    guardRelease(source, by, 'ownerOrAdmin');
    const state = source.issues.find((x) => x.number === n)!.state;
    if (isStartedOpen(source) && state !== 'waiting' && state !== 'held' && state !== 'skipped')
      throw new MockError(409, 'release_issue_started', `Issue #${n} is ${state} in release "${source.name}"; only waiting, held or skipped issues can be taken out`);
    return [{ n, source }];
  });
  // A ready release builds again for new issues: it needs the project's building slot.
  const adds = target ? nums.filter((n) => !target!.issues.some((x) => x.number === n)) : [];
  if (target && target.status === 'ready' && adds.length) {
    const other = releasesOf(repo).find((x) => x.id !== target!.id && isActiveRelease(x));
    if (other) throw slotTaken();
  }
  for (const { n, source } of moving) {
    source.issues = source.issues.filter((i) => i.number !== n);
    changed.add(source);
  }
  if (target) {
    for (const n of adds) {
      const i = db.issues[repo]!.find((x) => x.number === n)!;
      target.issues.push({ number: n, title: i.title, kind: i.kind, state: 'waiting', runId: null, note: null, prNumber: null, prUrl: null });
      changed.add(target);
    }
    if (adds.length && target.status === 'ready') Object.assign(target, { status: 'building', readyAt: null });
  }
  changed.forEach(touchRelease);
  // New issues are built; a source may have nothing left to build, a ready one may be complete now.
  changed.forEach((r) => advanceRelease(r));
  return releasesOf(repo);
}

// Scheduled start (docs/ARCHITECTURE.md §6 "Scheduled start"): a release whose time comes starts through the same
// start as "start now", as its owner; a refusal returns it to planning with the reason as its start failure.

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/i;
const scheduleTimers = new Map<string, ReturnType<typeof setTimeout>>();

/** `/factory releases` state text, as the server's `ReleaseService.describe`. */
export function releaseState(r: Release): string {
  if (r.status === 'scheduled') {
    const at = r.scheduledAt ? new Date(r.scheduledAt).toISOString().replace('.000Z', 'Z') : '?';
    return `scheduled for ${at} by ${r.owner?.username ?? 'unknown'}${r.waitingFor ? `, waiting for release "${r.waitingFor.name}"` : ''}`;
  }
  if (r.status === 'planning') return `in preparation${r.startFailure ? ` (scheduled start failed: ${r.startFailure.reason})` : ''}`;
  return r.status;
}

function armSchedule(r: Release) {
  clearTimeout(scheduleTimers.get(r.id));
  scheduleTimers.delete(r.id);
  if (r.status !== 'scheduled' || !r.scheduledAt) return;
  // setTimeout holds at most ~24.8 days; longer waits re-arm when they fire.
  const wait = Math.min(Math.max(0, Date.parse(r.scheduledAt) - Date.now()), 2 ** 31 - 1);
  scheduleTimers.set(r.id, setTimeout(() => releaseDue(r.id), wait));
}

function releaseDue(id: string) {
  scheduleTimers.delete(id);
  const r = db.releases.get(id);
  if (!r || r.status !== 'scheduled' || !r.scheduledAt) return;
  if (Date.parse(r.scheduledAt) > Date.now()) return armSchedule(r);
  const busy = releasesOf(r.repo).find((x) => x.id !== r.id && (x.status === 'building' || x.status === 'paused'));
  if (busy) {
    if (r.waitingFor?.id !== busy.id) {
      r.waitingFor = { id: busy.id, name: busy.name };
      touchRelease(r);
    }
    scheduleTimers.set(r.id, setTimeout(() => releaseDue(r.id), 15_000));
    return;
  }
  if (r.waitingFor) r.waitingFor = null;
  const owner = r.owner ?? r.createdBy;
  const user = userNamed(owner.username);
  let reason: string | null = null;
  if (!user) reason = "The owner's account no longer exists.";
  else if (!user.hasClaudeCredential) reason = `${user.username} has no Claude credential registered. Register one in Settings and schedule the release again.`;
  else if (!user.hasGithubToken) reason = `${user.username} has no GitHub token registered. Register one in Settings and schedule the release again.`;
  else {
    try {
      startReleaseAs(r, user, 'scheduled');
      return;
    } catch (e) {
      reason = e instanceof MockError ? e.message : 'Internal error while starting the release; see the server log.';
    }
  }
  Object.assign(r, { status: 'planning', owner: null, scheduledAt: null, waitingFor: null, startFailure: { at: iso(Date.now()), owner, reason } });
  touchRelease(r);
}

function requireScheduleOwner(r: Release, by: User) {
  if (!by.isAdmin && r.owner?.username !== by.username)
    throw new MockError(403, 'forbidden', `Only ${r.owner?.username ?? 'its owner'} or an admin can change this schedule`);
}

/** Sets (caller becomes the owner) or changes (owner or admin) the scheduled start. */
export function scheduleRelease(r: Release, startAt: string, by: User): Release {
  const ms = Date.parse(startAt);
  if (!ISO_INSTANT.test(startAt.trim()) || Number.isNaN(ms)) throw new MockError(400, 'bad_request', 'startAt must be an ISO-8601 time');
  if (ms <= Date.now()) throw new MockError(400, 'bad_request', 'The start time must be in the future');
  if (ms > Date.now() + 366 * 86400_000) throw new MockError(400, 'bad_request', 'The start time is too far ahead (at most a year)');
  if (r.status === 'planning') {
    if (!by.hasClaudeCredential) throw new MockError(422, 'claude_credential_required', 'Register a Claude credential in Settings first');
    if (!by.hasGithubToken) throw new MockError(403, 'forbidden', 'Register a GitHub token in Settings first');
    Object.assign(r, { status: 'scheduled', owner: ownerOf(by), scheduledAt: iso(ms), waitingFor: null, startFailure: null });
  } else if (r.status === 'scheduled') {
    requireScheduleOwner(r, by);
    Object.assign(r, { scheduledAt: iso(ms), waitingFor: null });
  } else {
    throw new MockError(409, 'release_not_schedulable', `Release is ${r.status}; only a release in preparation can be scheduled`);
  }
  armSchedule(r);
  touchRelease(r);
  return r;
}

/** Back to a shared plan: owner, time and waiting cleared; an earlier start failure is kept. */
export function cancelReleaseSchedule(r: Release, by: User): Release {
  if (r.status !== 'scheduled') throw new MockError(409, 'release_not_scheduled', 'This release has no scheduled start');
  requireScheduleOwner(r, by);
  Object.assign(r, { status: 'planning', owner: null, scheduledAt: null, waitingFor: null });
  armSchedule(r);
  touchRelease(r);
  return r;
}

export function reorderRelease(r: Release, numbers: number[], by: User) {
  if (r.status === 'cancelled') throw new MockError(409, 'release_closed', `Release "${r.name}" is cancelled`);
  if (r.status === 'released') notEditable(r);
  guardRelease(r, by, 'owner');
  const current = r.issues.map((i) => i.number);
  const same = numbers.length === current.length && new Set(numbers).size === numbers.length && numbers.every((n) => current.includes(n));
  if (!same) throw new MockError(409, 'release_issues_changed', "The release's issues changed in the meantime; reload and try again.");
  if (isStartedOpen(r)) {
    const moved = r.issues.find((i, idx) => i.state !== 'waiting' && numbers[idx] !== i.number);
    if (moved) throw new MockError(409, 'release_issue_started', `Issue #${moved.number} is ${moved.state}; only waiting issues can be moved`);
  }
  r.issues = numbers.map((n) => r.issues.find((i) => i.number === n)!);
  touchRelease(r);
  return r;
}

// ---------------------------------------------------------------- building releases (same rules as the server)

/** Branches that exist on the (pretend) GitHub, as `<repo>:<branch>`; a cancelled release's branch stays. */
const releaseBranches = new Set<string>();

const userNamed = (username: string) => db.users.find((u) => u.username === username);

/** Takes an issue out of the build when it cannot be built: closed on GitHub, or busy in a run outside the release. */
function skipIfUnbuildable(r: Release, it: Release['issues'][number]): boolean {
  const issue = db.issues[r.repo]?.find((i) => i.number === it.number);
  let note: string | null = null;
  if (!issue || issue.state !== 'open') note = 'Closed on GitHub';
  else if (issue.activeRunId && db.runs.get(issue.activeRunId)?.releaseId !== r.id) note = 'Has an active run outside the release';
  if (!note) return false;
  it.state = 'skipped';
  it.note = note;
  return true;
}

/**
 * Starts the next waiting issue of a building release when none is running; with nothing left to build the release
 * becomes `ready`. A ready release is then evaluated (pull request, mergeability), as on the server.
 */
function advanceRelease(r: Release) {
  if (r.status === 'ready') return evaluateRelease(r);
  if (r.status !== 'building' || r.issues.some((i) => i.state === 'running')) return;
  const owner = r.owner && userNamed(r.owner.username);
  if (!owner) return;
  let changed = false;
  let started = false;
  for (const it of r.issues) {
    if (it.state !== 'waiting') continue;
    if (skipIfUnbuildable(r, it)) {
      changed = true;
      continue;
    }
    const run = startRun(r.repo, it.number, owner, null, { changes: {} }, Date.now(), r);
    it.state = 'running';
    it.runId = run.id;
    it.note = null;
    changed = true;
    started = true;
    break;
  }
  if (!started) {
    Object.assign(r, { status: 'ready', readyAt: iso(Date.now()), mergeState: 'unknown' });
    return evaluateRelease(r);
  }
  if (changed) touchRelease(r);
}

/** Release branches (`<repo>:<branch>`) whose pull request does not merge cleanly into the default branch. */
export const conflictingReleases = new Set<string>();
/** Repositories that only allow squash merges (no rebase, no merge commit). */
export const squashOnlyRepos = new Set<string>();
/** The checks on each release branch's pull request (`<repo>:<branch>`); none listed: no checks at all. */
export const releaseChecks = new Map<string, Omit<ReleaseChecks, 'sha'>>();
let nextReleasePr = 120;

/** A ready release: open its pull request when complete, then check mergeability and the merge method. */
function evaluateRelease(r: Release) {
  if (r.status !== 'ready') return;
  const complete = r.issues.some((i) => i.state === 'merged') && !r.issues.some((i) => i.state === 'held');
  r.targetBranch = 'main';
  if (!r.prNumber && complete) {
    r.prNumber = nextReleasePr++;
    r.prUrl = `https://github.com/${ORG}/${r.repo}/pull/${r.prNumber}`;
  }
  if (r.prNumber) {
    r.mergeState = conflictingReleases.has(`${r.repo}:${r.branch}`) ? 'conflict' : 'clean';
    r.mergeMethod = squashOnlyRepos.has(r.repo) ? 'unavailable' : 'rebase';
    const checks = releaseChecks.get(`${r.repo}:${r.branch}`) ?? { state: 'none', detail: 'No checks' };
    r.checks = { ...checks, sha: `${r.name.length}f3a9c1e7b2d4${r.prNumber}`.slice(0, 40) };
  } else r.mergeState = 'unknown';
  r.notice = null;
  touchRelease(r);
  // Automatic release: the same merge as an approval by the owner, once complete, clean and green; blockers wait.
  if (r.mode !== 'automatic' || !complete || !r.prNumber || r.mergeState !== 'clean' || r.mergeMethod === 'unavailable') return;
  if (r.checks?.state === 'failed') {
    r.notice = `Checks failed on pull request #${r.prNumber} (${r.checks.detail}), so it is not released automatically. Fix the release branch or re-run the checks; the owner can also approve it by hand.`;
    touchRelease(r);
    return;
  }
  if (r.checks?.state !== 'none' && r.checks?.state !== 'passed') return;
  const owner = r.owner && userNamed(r.owner.username);
  if (owner) finishRelease(r, owner, true);
}

const slotTaken = () =>
  new MockError(409, 'release_already_building', 'Another release of this project is building; it must finish before this one can build again');

const conflictOf = (r: Release) =>
  new MockError(409, 'release_not_ready', `Release is ${r.status === 'planning' ? 'in preparation' : RELEASE_STATUS_TEXT[r.status]}, not ready to release`);

/** "Check again" (owner only). */
export function checkRelease(r: Release, by: User) {
  if (r.status === 'planning' || r.status === 'scheduled') throw conflictOf(r);
  guardRelease(r, by, 'owner');
  if (r.status !== 'ready') throw conflictOf(r);
  evaluateRelease(r);
  return r;
}

/** Owner-only approval: merges the release pull request (rebase), closes the issues, deletes the branch. */
export function approveRelease(r: Release, by: User) {
  if (r.status === 'planning' || r.status === 'scheduled') throw conflictOf(r);
  guardRelease(r, by, 'owner');
  if (r.status !== 'ready') throw conflictOf(r);
  const held = r.issues.filter((i) => i.state === 'held');
  if (held.length)
    throw new MockError(409, 'release_incomplete', `${held.length} issue(s) are held: ${held.map((i) => `#${i.number}`).join(', ')}. Approve or retry their runs, or move them out of the release first.`);
  const merged = r.issues.filter((i) => i.state === 'merged');
  if (!merged.length) throw new MockError(409, 'release_empty', 'Nothing was merged into the release branch, so there is nothing to release.');
  evaluateRelease(r);
  if (r.mergeState === 'conflict') throw new MockError(409, 'release_conflict', `\`${r.branch}\` no longer merges cleanly into \`main\`. Nothing was merged.`);
  if (r.mergeMethod === 'unavailable')
    throw new MockError(409, 'release_merge_method_unavailable', 'This repository only allows squash merges. Allow rebase merging or merge commits in its GitHub settings to release.');
  return finishRelease(r, by, false);
}

/** The merge and its clean-up, shared by an approval and an automatic release. */
function finishRelease(r: Release, by: User, automatic: boolean) {
  if (r.status !== 'ready') return r;
  const merged = r.issues.filter((i) => i.state === 'merged');
  for (const it of merged) {
    const issue = db.issues[r.repo]?.find((i) => i.number === it.number);
    if (issue) issue.state = 'closed';
  }
  releaseBranches.delete(`${r.repo}:${r.branch}`);
  const now = iso(Date.now());
  Object.assign(r, {
    status: 'released',
    releasedAt: now,
    endedAt: now,
    releasedBy: ownerOf(by),
    releasedAutomatically: automatic,
    mergeCommit: Array.from({ length: 40 }, (_, i) => '0123456789abcdef'[(i * 7 + r.name.length) % 16]).join(''),
    mergeState: 'clean',
    notice: null,
  });
  touchRelease(r);
  return r;
}

/** A release run reached `merged`, `needs_human`, `failed` or `cancelled`: update its issue and go on. */
function releaseRunEnded(run: Run) {
  const r = run.releaseId ? db.releases.get(run.releaseId) : undefined;
  const it = r?.issues.find((i) => i.runId === run.id);
  // Retried or taken out meanwhile, or the release was cancelled (that sets the issue states itself).
  if (!r || !it || r.status === 'cancelled') return;
  it.prNumber = run.prNumber;
  it.prUrl = run.prUrl;
  if (run.status === 'merged') {
    it.state = 'merged';
    it.note = null;
  } else if (it.state === 'running' || it.state === 'held') {
    it.state = 'held';
    it.note = run.error ?? 'Run failed';
  }
  touchRelease(r);
  advanceRelease(r);
}

export function startRelease(r: Release, by: User) {
  if (r.status === 'scheduled')
    throw new MockError(409, 'release_not_planning', `Release "${r.name}" is scheduled; cancel the scheduled start to start it now`);
  return startReleaseAs(r, by, 'planning');
}

/** "Start now" (`planning`) and the scheduled start (`scheduled`, as the owner) share this path, as on the server. */
function startReleaseAs(r: Release, by: User, from: 'planning' | 'scheduled') {
  if (r.status !== from) throw new MockError(409, 'release_not_planning', `Release "${r.name}" has already been started`);
  if (!by.hasClaudeCredential) throw new MockError(422, 'claude_credential_required', 'Register a Claude credential in Settings first');
  if (!by.hasGithubToken) throw new MockError(403, 'forbidden', 'Register a GitHub token in Settings first');
  if (r.issues.length === 0) throw new MockError(409, 'release_empty', 'Add an issue before starting the release');
  const other = releasesOf(r.repo).find((x) => x.id !== r.id && isActiveRelease(x));
  if (other) throw new MockError(409, 'release_already_building', `Release "${other.name}" is already ${other.status} in this project; only one release at a time can build`);
  const branch = `release/${r.name}`;
  if (releaseBranches.has(`${r.repo}:${branch}`)) throw new MockError(409, 'release_branch_exists', `The branch ${branch} already exists on GitHub`);
  releaseBranches.add(`${r.repo}:${branch}`);
  Object.assign(r, { status: 'building', owner: ownerOf(by), branch, startedAt: iso(Date.now()), endedAt: null, pause: null, scheduledAt: null, waitingFor: null });
  for (const it of r.issues) skipIfUnbuildable(r, it);
  touchRelease(r);
  advanceRelease(r);
  return r;
}

export function pauseRelease(r: Release, by: User) {
  guardRelease(r, by, 'ownerOrAdmin');
  if (r.status !== 'building') throw new MockError(409, 'release_not_building', `Release "${r.name}" is not building`);
  r.status = 'paused';
  r.pause = { reason: 'manual', note: `Paused by ${by.username}`, resumeAt: null };
  touchRelease(r);
  return r;
}

export function resumeRelease(r: Release, by: User) {
  guardRelease(r, by, 'owner');
  if (r.status !== 'paused') throw new MockError(409, 'release_not_paused', `Release "${r.name}" is not paused`);
  if (!by.hasClaudeCredential) throw new MockError(422, 'claude_credential_required', 'Register a Claude credential in Settings first');
  r.status = 'building';
  r.pause = null;
  touchRelease(r);
  advanceRelease(r);
  return r;
}

export function cancelRelease(r: Release, by: User) {
  if (r.status === 'released') notEditable(r);
  guardRelease(r, by, 'ownerOrAdmin');
  if (!isStartedOpen(r)) throw new MockError(409, 'release_not_building', `Release "${r.name}" is not building or paused`);
  r.status = 'cancelled';
  r.pause = null;
  r.endedAt = iso(Date.now());
  for (const it of r.issues) {
    if (it.state === 'running') {
      it.state = 'held';
      it.note = 'Cancelled with the release';
    }
    const run = it.runId ? db.runs.get(it.runId) : undefined;
    if (run && ['queued', 'spec', 'building', 'reviewing', 'approving', 'needs_human'].includes(run.status)) cancelRun(run);
  }
  touchRelease(r);
  return r;
}

/** Retry options as the server computes them, simplified: a mock branch "has commits" unless the builder made none. */
export function retryOptionsFor(run: Run | undefined): RetryOptions {
  if (!run) {
    return {
      runId: '', recommended: 'start_over', reason: 'The issue has no run to continue from.', step: null, commits: null, releaseId: null,
      options: [
        { mode: 'step', available: false, detail: 'The issue has no run.' },
        { mode: 'continue', available: false, detail: 'The issue has no run.' },
        { mode: 'start_over', available: true, detail: 'A new run from scratch.' },
      ],
    };
  }
  const blocked = /no commits/i.test(run.error ?? '') || run.stages.every((s) => s.role !== 'builder' || s.status !== 'done' && s.status !== 'failed');
  const commits = blocked ? 0 : 2;
  const canContinue = commits > 0 && ['failed', 'cancelled', 'needs_human'].includes(run.status);
  const recommended: RetryMode = !canContinue || run.status === 'cancelled' ? 'start_over' : 'continue';
  const reason = recommended === 'continue'
    ? `The run failed after ${commits} commits${run.error ? ` (${run.error.split('\n')[0].slice(0, 120)})` : ''}; continue from them.`
    : run.status === 'cancelled' && canContinue ? `The run was cancelled; its branch has ${commits} commits if you want to continue.` : "The run's branch has no commits of its own.";
  return {
    runId: run.id, recommended, reason, step: null, commits, releaseId: run.releaseId,
    options: [
      { mode: 'step', available: false, detail: 'Only when the builder finished and a later step (push, pull request, merge) failed.' },
      { mode: 'continue', available: canContinue, detail: canContinue ? `A new run starts from branch ${run.branch} (${commits} commits), reuses the spec and finishes the work.` : "Only when the run's branch has commits of its own." },
      { mode: 'start_over', available: true, detail: 'A new run on a new branch from scratch; the old branch is left as it is.' },
    ],
  };
}

function checkMode(opts: RetryOptions, mode: RetryMode | undefined): RetryMode {
  const m = mode ?? 'start_over';
  const o = opts.options.find((x) => x.mode === m);
  if (!o?.available) throw new MockError(409, 'retry_mode_unavailable', `Cannot retry with '${m}': ${o?.detail ?? ''}`);
  return m;
}

export function retryRun(r: Run, mode: RetryMode | undefined, by: User): Run {
  if (r.owner.username !== by.username) throw new MockError(403, 'forbidden', 'Only the owner of this run can retry it');
  if (r.releaseId) throw new MockError(409, 'release_run', "This run builds an issue of a release; retry the issue on the release's page");
  if (r.status !== 'failed' && r.status !== 'cancelled') throw new MockError(409, 'run_not_retryable', `Run is ${r.status}; only failed or cancelled runs can be retried`);
  const m = checkMode(retryOptionsFor(r), mode);
  const next = startRun(r.repo, r.issueNumber, by);
  if (m === 'continue') {
    next.continuesRunId = r.id;
    r.continuedByRunId = next.id;
  }
  return next;
}

export function retryReleaseIssue(r: Release, n: number, by: User, mode?: RetryMode) {
  const held = r.issues.find((i) => i.number === n);
  const oldRun = held?.runId ? db.runs.get(held.runId) : undefined;
  const m = held ? checkMode(retryOptionsFor(oldRun), mode) : 'start_over';
  const result = retryReleaseIssueOver(r, n, by);
  const now = r.issues.find((i) => i.number === n);
  const newRun = now?.runId ? db.runs.get(now.runId) : undefined;
  if (m === 'continue' && oldRun && newRun) {
    newRun.continuesRunId = oldRun.id;
    oldRun.continuedByRunId = newRun.id;
  }
  return result;
}

function retryReleaseIssueOver(r: Release, n: number, by: User) {
  if (r.status === 'released') notEditable(r);
  if (!isStartedOpen(r)) throw new MockError(409, 'release_not_building', `Release "${r.name}" is not building or paused`);
  guardRelease(r, by, 'owner');
  const it = r.issues.find((i) => i.number === n);
  if (!it) throw new MockError(404, 'not_found', `Issue #${n} is not in release "${r.name}"`);
  if (it.state !== 'held' && it.state !== 'skipped') throw new MockError(409, 'release_issue_not_held', `Issue #${n} is ${it.state}; only held or skipped issues can be retried`);
  if (r.status === 'ready') {
    if (releasesOf(r.repo).some((x) => x.id !== r.id && isActiveRelease(x))) throw slotTaken();
    Object.assign(r, { status: 'building', readyAt: null });
  }
  const run = it.runId ? db.runs.get(it.runId) : undefined;
  it.state = 'waiting';
  it.note = null;
  it.runId = null;
  if (run?.status === 'needs_human') cancelRun(run);
  touchRelease(r);
  advanceRelease(r);
  return r;
}

// ---------------------------------------------------------------- seed

const LABEL = { bug: { name: 'bug', color: 'd73a4a' }, feature: { name: 'enhancement', color: 'a2eeef' }, task: { name: 'chore', color: 'c5def5' } };
function issue(repo: string, number: number, kind: IssueKind, title: string, author: string, daysAgo: number, extra: Partial<Issue> = {}): Issue {
  return {
    number,
    title,
    state: 'open',
    kind,
    labels: [LABEL[kind]],
    author,
    assignees: [],
    comments: (number * 7) % 5,
    createdAt: ago(daysAgo * 1440),
    updatedAt: ago(daysAgo * 300),
    htmlUrl: `https://github.com/${ORG}/${repo}/issues/${number}`,
    body: `### Context\n\nReported by @${author}.\n\n### Acceptance\n\n- [ ] Works on desktop and mobile\n- [ ] Covered by tests`,
    activeRunId: null,
    release: null,
    ...extra,
  };
}

function seed() {
  const proj = (name: string, description: string, language: string, openIssues: number, pushedMin: number, priv = true): Project => ({
    name,
    fullName: `${ORG}/${name}`,
    description,
    private: priv,
    defaultBranch: 'main',
    language,
    openIssues,
    pushedAt: ago(pushedMin),
    htmlUrl: `https://github.com/${ORG}/${name}`,
    activeAgents: 0,
    activeRuns: 0,
  });
  db.projects = [
    proj('web-shop', 'Storefront and checkout for egernet.dk', 'Kotlin', 9, 4),
    proj('billing-service', 'Invoices, VAT and the payment-provider integration', 'Kotlin', 6, 38),
    proj('ios-companion', 'iPhone app for warehouse pickers', 'Swift', 4, 60 * 26),
    proj('design-system', 'Shared tokens and React components', 'TypeScript', 3, 60 * 5, false),
    proj('data-pipeline', 'Nightly ETL into the warehouse', 'Python', 2, 60 * 24 * 6),
    proj('infra', 'Terraform for the hosting environment', 'HCL', 0, 60 * 24 * 19),
  ];
  // Sandbox definitions: web-shop's is approved, billing-service's changed since its approval, design-system's is invalid.
  const webShopSandbox = 'image: docker.io/library/eclipse-temurin:21-jdk\nuser: builder\nsetup: |\n  apt-get update && apt-get install -y nodejs npm\nservices:\n  postgres:\n    image: docker.io/library/postgres:17\n    env: { POSTGRES_PASSWORD: dev }\n    ready: { command: "pg_isready -h 127.0.0.1", timeoutSeconds: 60 }\ncaches: [ /home/builder/.gradle, /home/builder/.npm ]\nresources: { cpus: 2, memory: 4g }\nplatforms:\n  jdk17:\n    image: docker.io/library/eclipse-temurin:17-jdk\n';
  db.sandboxFiles.set('web-shop', {
    content: webShopSandbox,
    sha256: '3f6c1d0e9a8b7c6d5e4f30211a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d',
    definition: {
      image: 'docker.io/library/eclipse-temurin:21-jdk',
      setup: 'apt-get update && apt-get install -y nodejs npm\n',
      env: {},
      services: [{ name: 'postgres', image: 'docker.io/library/postgres:17', env: { POSTGRES_PASSWORD: 'dev' }, ready: { command: 'pg_isready -h 127.0.0.1', timeoutSeconds: 60 } }],
      caches: ['/home/builder/.gradle', '/home/builder/.npm'],
      limits: { cpus: 2, memory: '4g', requestedCpus: 2, requestedMemory: '4g', capped: false },
      platforms: [{ name: 'jdk17', image: 'docker.io/library/eclipse-temurin:17-jdk', setup: 'apt-get update && apt-get install -y nodejs npm\n', setupInherited: true }],
      user: { name: 'builder', uid: 1000 },
    },
    errors: [],
  });
  db.sandboxApprovals.set('web-shop', { sha256: '3f6c1d0e9a8b7c6d5e4f30211a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d', content: webShopSandbox, approvedBy: ownerOf(alice), approvedAt: ago(60 * 24 * 3) });
  const billingSandbox = 'image: docker.io/library/eclipse-temurin:21-jdk\nresources: { cpus: 8, memory: 16g }\n';
  db.sandboxFiles.set('billing-service', {
    content: billingSandbox,
    sha256: 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    definition: {
      image: 'docker.io/library/eclipse-temurin:21-jdk',
      setup: null,
      env: {},
      services: [],
      caches: [],
      limits: { cpus: 2, memory: '4g', requestedCpus: 8, requestedMemory: '16g', capped: true },
      platforms: [],
      user: null,
    },
    errors: [],
  });
  db.sandboxApprovals.set('billing-service', {
    sha256: '0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0',
    content: 'image: docker.io/library/eclipse-temurin:17-jdk\n',
    approvedBy: ownerOf(alice),
    approvedAt: ago(60 * 24 * 9),
  });
  // data-pipeline: a new definition no admin has approved yet.
  db.sandboxFiles.set('data-pipeline', {
    content: 'image: docker.io/library/python:3.12-slim\nsetup: pip install -r requirements-dev.txt\nenv: { PYTHONUNBUFFERED: "1" }\nservices:\n  warehouse:\n    image: docker.io/library/postgres:17\n    env: { POSTGRES_PASSWORD: dev, POSTGRES_DB: warehouse }\ncaches: [ /root/.cache/pip ]\n',
    sha256: '5b7e4c2a90d1f3e6b8a7c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4',
    definition: {
      image: 'docker.io/library/python:3.12-slim',
      setup: 'pip install -r requirements-dev.txt',
      env: { PYTHONUNBUFFERED: '1' },
      services: [{ name: 'warehouse', image: 'docker.io/library/postgres:17', env: { POSTGRES_PASSWORD: 'dev', POSTGRES_DB: 'warehouse' }, ready: null }],
      caches: ['/root/.cache/pip'],
      limits: { cpus: 2, memory: '4g', requestedCpus: null, requestedMemory: null, capped: false },
      platforms: [],
      user: null,
    },
    errors: [],
  });
  db.sandboxFiles.set('design-system', {
    content: 'image: node:22\nprivileged: true\n',
    sha256: '9e8d7c6b5a4f30211e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a697887',
    definition: null,
    errors: [
      "image: 'node:22' is not fully qualified; name the registry, e.g. docker.io/library/node:22",
      'privileged: unknown key (allowed here: image, setup, env, services, caches, resources)',
    ],
  });

  db.issues['web-shop'] = [
    issue('web-shop', 42, 'feature', 'Saved carts for signed-in users', 'alice', 3),
    issue('web-shop', 38, 'feature', 'Gift cards at checkout', 'bob', 8),
    issue('web-shop', 51, 'bug', 'Checkout button jumps on iOS Safari', 'bob', 1),
    issue('web-shop', 53, 'task', 'Upgrade Ktor to 3.1', 'alice', 2),
    issue('web-shop', 55, 'bug', 'Search returns discontinued products', 'carl', 4),
    issue('web-shop', 57, 'feature', 'Share a wishlist by link', 'alice', 6),
    issue('web-shop', 58, 'task', 'Remove the legacy coupon endpoint', 'bob', 9),
    issue('web-shop', 60, 'bug', 'Danish characters broken in order e-mails', 'carl', 0.3),
    issue('web-shop', 47, 'bug', 'VAT rounding differs between checkout and invoice', 'alice', 12, { state: 'closed' }),
    issue('web-shop', 44, 'task', 'Move product images to the CDN', 'bob', 15),
    issue('web-shop', 61, 'feature', 'Newsletter sign-up in the footer', 'alice', 10),
    issue('web-shop', 62, 'bug', 'Receipt page shows prices without VAT', 'carl', 9),
    issue('web-shop', 63, 'task', 'Rename the order-status enum', 'bob', 11),
    issue('web-shop', 64, 'bug', 'Cart badge shows a stale count', 'carl', 7),
    issue('web-shop', 65, 'feature', 'Pay by MobilePay', 'alice', 14),
    issue('web-shop', 66, 'bug', 'Refunds fail for split payments', 'bob', 13),
    issue('web-shop', 67, 'feature', 'Product reviews with photos', 'alice', 30, { state: 'closed' }),
    issue('web-shop', 68, 'bug', 'Sitemap misses category pages', 'carl', 28, { state: 'closed' }),
    issue('web-shop', 69, 'task', 'Drop the old promo banner', 'bob', 21),
  ];
  db.issues['billing-service'] = [
    issue('billing-service', 17, 'feature', 'Switch card payments to the new provider', 'alice', 5),
    issue('billing-service', 21, 'bug', 'Credit notes show the wrong due date', 'bob', 2),
    issue('billing-service', 22, 'task', 'Nightly reconciliation report', 'alice', 7),
    issue('billing-service', 24, 'bug', 'EAN invoices rejected by NemHandel', 'carl', 1),
  ];
  db.issues['ios-companion'] = [
    issue('ios-companion', 8, 'feature', 'Scan shelf labels with the camera', 'bob', 4),
    issue('ios-companion', 9, 'bug', 'Pick list does not refresh after sync', 'alice', 2),
  ];
  db.issues['design-system'] = [
    issue('design-system', 12, 'feature', 'Dark theme tokens', 'alice', 20, { state: 'closed' }),
    issue('design-system', 14, 'task', 'Publish to the internal registry', 'bob', 3),
  ];
  db.issues['data-pipeline'] = [issue('data-pipeline', 3, 'bug', 'Duplicate orders on DST change', 'carl', 10)];
  db.issues.infra = [];

  // Release plans: one with issues, one empty, and (below, after the runs) one building.
  const spring = createRelease('web-shop', { name: '1.4', description: 'Checkout polish before the spring campaign.\nShip together.' }, alice, Date.now() - 2 * 86400_000);
  const planned = (repo: string, n: number): Release['issues'][number] => {
    const i = db.issues[repo].find((x) => x.number === n)!;
    return { number: n, title: i.title, kind: i.kind, state: 'waiting', runId: null, note: null, prNumber: null, prUrl: null };
  };
  spring.issues = [51, 55, 57].map((n) => planned('web-shop', n));
  const cleanup = createRelease('web-shop', { name: 'chat-cleanup', description: '' }, bob, Date.now() - 86400_000);
  // Scheduled by its creator for tomorrow 22:00 local time.
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(22, 0, 0, 0);
  Object.assign(cleanup, { status: 'scheduled', owner: ownerOf(bob), scheduledAt: iso(tomorrow.getTime()) });
  armSchedule(cleanup);

  // Historical runs (instant).
  const never = () => false;
  startRun('web-shop', 47, alice, null, { changes: {}, liveFrom: never }, Date.now() - 3 * 86400_000);
  startRun('design-system', 12, alice, null, { changes: {}, liveFrom: never }, Date.now() - 5 * 86400_000);
  const failed = startRun('web-shop', 44, bob, null, { changes: {}, liveFrom: never }, Date.now() - 86400_000);
  for (const st of failed.stages.slice(2)) {
    if (st.sessionId) db.sessions.delete(st.sessionId);
    Object.assign(st, { status: 'skipped', sessionId: null, verdict: null, summary: null, findings: [], startedAt: null });
  }
  failed.stages[1].status = 'failed';
  const failedBuilder = db.sessions.get(failed.stages[1].sessionId!);
  if (failedBuilder) failedBuilder.status = 'failed';
  failed.stages[1].verdict = null;
  failed.error = 'Server restarted while the builder was running (v1 does not resume runs).';
  failed.status = 'failed';
  failed.prNumber = null;
  failed.prUrl = null;
  failed.endedAt = failed.stages[1].endedAt;
  db.issues['web-shop'].find((i) => i.number === 44)!.activeRunId = null;
  db.issues['web-shop'].find((i) => i.number === 44)!.state = 'open';

  // Waiting for a human (alice owns it).
  startRun('billing-service', 17, alice, null, { changes: {}, approver: 'ESCALATE', liveFrom: never }, Date.now() - 40 * 60_000);

  // Chats.
  const chat = createChat('web-shop', alice, Date.now() - 50 * 60_000);
  for (const turn of seededChat) {
    const p = new Player('instant', Date.now() - 48 * 60_000, 20);
    play(chat, turn.steps, p, { userText: turn.user, init: true });
  }
  chat.status = 'idle';
  emit(chat.id, 'status', { status: 'idle' }, ago(45));

  const bobChat = createChat('billing-service', bob, Date.now() - 20 * 60_000);
  const bobLoop = () => {
    const p = new Player('live', Date.now());
    db.players.set(bobChat.id, p);
    play(bobChat, chatReplies[2].steps, p, {
      userText: 'Give me an overview of what changed this week',
      init: (db.events.get(bobChat.id) ?? []).length < 3,
      onDone: () => {
        setStatus(bobChat, 'idle', iso(Date.now()));
        setTimeout(bobLoop, 15_000);
      },
    });
  };
  setTimeout(bobLoop, 1500);

  // Live: alice's run on #42 is in its first review round; bob's run on #38 is building.
  const runStart = Date.now() - 12 * 60_000;
  startRun('web-shop', 42, alice, chat.id, { liveFrom: (role, round) => !(round === 0 && (role === 'spec-writer' || role === 'builder')) }, runStart);
  startRun('web-shop', 38, bob, null, { changes: {}, liveFrom: (role) => role !== 'spec-writer' }, Date.now() - 4 * 60_000);

  // A release building on release/1.3 (alice started it): one merged, one running, one held, one waiting.
  const building = createRelease('web-shop', { name: '1.3', description: 'Small fixes, shipped together.' }, alice, Date.now() - 3 * 86400_000);
  building.issues = [53, 58, 60, 44].map((n) => planned('web-shop', n));
  Object.assign(building, { status: 'building', owner: ownerOf(alice), branch: 'release/1.3', startedAt: ago(50), updatedAt: ago(1) });
  releaseBranches.add('web-shop:release/1.3');
  const seededRun = (n: number, plan: Partial<Plan>, startMin: number) => {
    const run = startRun('web-shop', n, alice, null, { changes: {}, ...plan }, Date.now() - startMin * 60_000, building);
    const it = building.issues.find((i) => i.number === n)!;
    it.runId = run.id;
    it.state = run.status === 'merged' ? 'merged' : run.status === 'needs_human' ? 'held' : 'running';
    it.note = it.state === 'held' ? run.error : null;
  };
  seededRun(53, { liveFrom: never }, 50);
  seededRun(60, { approver: 'ESCALATE', liveFrom: never }, 35);
  seededRun(58, { liveFrom: (role) => role !== 'spec-writer' && role !== 'builder' }, 3);

  // Releasing (step 3): finished releases with their runs, in every state of the release pull request.
  const finished = (name: string, description: string, owner: User, issues: [number, Partial<Plan>][], startedMin: number) => {
    const r = createRelease('web-shop', { name, description }, owner, Date.now() - (startedMin + 60) * 60_000);
    r.issues = issues.map(([n]) => planned('web-shop', n));
    Object.assign(r, { status: 'building', owner: ownerOf(owner), branch: `release/${name}`, startedAt: ago(startedMin) });
    releaseBranches.add(`web-shop:release/${name}`);
    issues.forEach(([n, plan], k) => {
      const run = startRun('web-shop', n, owner, null, { changes: {}, liveFrom: never, ...plan }, Date.now() - (startedMin - k * 20) * 60_000, r);
      const it = r.issues.find((i) => i.number === n)!;
      Object.assign(it, { runId: run.id, prNumber: run.prNumber, prUrl: run.prUrl, state: run.status === 'merged' ? 'merged' : 'held', note: run.status === 'merged' ? null : run.error });
    });
    return r;
  };
  // Ready, complete and clean, its checks still running: alice may approve it (with a warning) or let them finish.
  releaseChecks.set('web-shop:release/1.2', { state: 'pending', detail: 'Pending: e2e' });
  const ready = finished('1.2', 'Footer and receipt fixes.', alice, [[61, {}], [62, {}]], 300);
  advanceRelease(ready);
  // Ready, but main moved on and the release branch conflicts; set to release automatically, so it waits.
  conflictingReleases.add('web-shop:release/cart-fixes');
  releaseChecks.set('web-shop:release/cart-fixes', { state: 'failed', detail: 'Failed: unit-tests' });
  const conflicting = finished('cart-fixes', '', alice, [[63, {}], [64, {}]], 500);
  conflicting.mode = 'automatic';
  advanceRelease(conflicting);
  // Ready but incomplete: one issue is held (bob's release).
  const incomplete = finished('payments', 'MobilePay and refunds.', bob, [[65, {}], [66, { approver: 'ESCALATE' }]], 700);
  advanceRelease(incomplete);
  // Released two days ago (history), and a cancelled one.
  const old = finished('1.1', 'Reviews and SEO.', alice, [[67, {}], [68, {}]], 3 * 1440);
  advanceRelease(old);
  approveRelease(old, alice);
  for (const n of [67, 68]) db.issues['web-shop'].find((i) => i.number === n)!.state = 'closed';
  Object.assign(old, { releasedAt: ago(2 * 1440), endedAt: ago(2 * 1440), updatedAt: ago(2 * 1440) });
  const promo = finished('promo', 'Spring promo clean-up.', bob, [[69, { approver: 'ESCALATE' }]], 4 * 1440);
  for (const it of promo.issues) {
    const run = it.runId ? db.runs.get(it.runId) : undefined;
    if (run?.status === 'needs_human') cancelRun(run);
    Object.assign(it, { state: 'held', note: 'Cancelled with the release' });
  }
  Object.assign(promo, { status: 'cancelled', endedAt: ago(3 * 1440), updatedAt: ago(3 * 1440) });

  db.softwareRequests = [
    { id: uid(), name: 'imagemagick', reason: 'Generate product thumbnails in the image pipeline tests', repo: 'web-shop', requestedBy: 'bob', sessionId: null, status: 'pending', note: null, createdAt: ago(90), resolvedAt: null },
    { id: uid(), name: 'xcodes', reason: 'Switch Xcode versions for the iOS build', repo: 'ios-companion', requestedBy: 'alice', sessionId: null, status: 'pending', note: null, createdAt: ago(60 * 7), resolvedAt: null },
    { id: uid(), name: 'postgresql-client-16', reason: 'pg_dump for migration tests', repo: 'billing-service', requestedBy: 'alice', sessionId: null, status: 'installed', note: 'Installed via apt.', createdAt: ago(60 * 50), resolvedAt: ago(60 * 46) },
  ];
}

seed();
