import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError, isReleaseFinished, isReleaseOpen, isRunWorking, isSessionActive, type AgentSession, type CheckoutInfo, type Issue, type IssueKind, type Release, type ReleaseMode, type Run, type Workspace } from '../api';
import { ChatView } from '../components/chat/ChatView';
import { IssueImagesDialog } from '../components/images/IssueImagesDialog';
import { REVIEWERS, roundName } from '../components/pipeline/model';
import { sandboxSummary } from '../components/sandbox/SandboxBits';
import { ProjectIconControl } from '../components/ui/ProjectIconControl';
import { Dialog, Empty, ErrorBox, KindBadge, Loading, Notice, ProjectIcon, ReleaseStatusPill, RoleBadge, RunStatusPill, SessionStatusPill } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { dateTime, modelLabel, timeAgo } from '../lib/format';
import { useFeed, useFeedReconnects, useIssueFeed, useLiveRuns, useLiveSessions, useProjectReleases, useSandboxApprovalFeed, withApproval } from '../lib/realtime';
import { useNow } from '../lib/useNow';

type Tab = 'chat' | 'issues' | 'activity';

/** The user may close this chat: its owner or an admin (the server enforces the same rule). */
const closable = (s: AgentSession, me: { username: string; isAdmin: boolean }) => s.kind === 'chat' && (s.owner.username === me.username || me.isAdmin);
const chatLabel = (s: AgentSession, username: string) => (s.owner.username === username ? 'my chat' : `${s.owner.username}’s chat`);

export function ProjectPage() {
  const { repo = '' } = useParams();
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<Tab>('chat');
  const reconnects = useFeedReconnects();
  const project = useAsync(() => api.project(repo), [repo, reconnects]);
  // Every creator gets the frame too, so this is the only place the counter moves.
  useIssueFeed(repo, (i) => {
    if (i.state === 'open') project.setData((p) => p && { ...p, openIssues: p.openIssues + 1 });
  });
  const sessionsBase = useAsync(() => api.projectSessions(repo), [repo]);
  const runsBase = useAsync(() => api.projectRuns(repo), [repo]);
  const sessions = useLiveSessions(sessionsBase.data, (s) => s.repo === repo);
  const runs = useLiveRuns(runsBase.data, (r) => r.repo === repo);
  const releases = useProjectReleases(repo);
  const { closedSessions, markSessionClosed } = useFeed();
  const [closing, setClosing] = useState<AgentSession | null>(null);
  const [iconError, setIconError] = useState<string | null>(null);
  // Chats this user closed (set before the request): they never get the "was closed" notice for them.
  const closedByMe = useRef(new Set<string>());

  useEffect(() => {
    document.title = `${repo} · Agern`;
    setIconError(null);
  }, [repo]);

  const chats = sessions.filter((s) => s.kind === 'chat');
  const mine = chats.filter((s) => s.owner.username === me.username);
  const requested = params.get('session');
  const closedNotice = !!requested && closedSessions.has(requested) && !closedByMe.current.has(requested);
  const current = (requested && sessions.find((s) => s.id === requested)) || mine.find((s) => !['stopped', 'failed', 'done'].includes(s.status)) || mine[0] || null;

  const selectSession = (id: string) => {
    params.set('session', id);
    setParams(params, { replace: true });
    setTab('chat');
  };
  const clearSession = (id?: string) =>
    setParams(
      (prev) => {
        if (id !== undefined && prev.get('session') !== id) return prev;
        const next = new URLSearchParams(prev);
        next.delete('session');
        return next;
      },
      { replace: true },
    );
  const onClosed = (id: string) => {
    markSessionClosed(id);
    sessionsBase.setData((d) => d?.filter((s) => s.id !== id));
    clearSession(id);
  };

  if (project.error) {
    return (
      <div className="page">
        {project.error instanceof ApiError && project.error.status === 404 ? (
          <Empty title={`No project called “${repo}”`}>It doesn’t exist in the organization, or your GitHub token can’t read it. <Link to="/">All projects</Link></Empty>
        ) : (
          <ErrorBox error={project.error} retry={project.reload} />
        )}
      </div>
    );
  }

  const p = project.data;
  const activeAgents = sessions.filter(isSessionActive).length;
  const activeRuns = runs.filter(isRunWorking).length;

  return (
    <div className="project" data-tab={tab}>
      <header className="project-head">
        <div className="ph-title">
          <Link to="/" className="crumb">projects /</Link>
          {me.isAdmin && p ? (
            <ProjectIconControl
              repo={repo}
              iconUrl={p.iconUrl}
              size={28}
              defaultBranch={p.defaultBranch}
              onChanged={(np) => project.setData((old) => ({ ...np, workspace: old?.workspace ?? np.workspace }))}
              onError={setIconError}
            />
          ) : (
            <ProjectIcon name={repo} iconUrl={p?.iconUrl} size={28} />
          )}
          <h1>{repo}</h1>
          {p?.private && <span className="tag">private</span>}
        </div>
        <p className="ph-desc">{p?.description ?? ' '}</p>
        <div className="ph-meta">
          <span className={activeAgents ? 'ph-live' : ''}><span className="live-dot" aria-hidden /> {activeAgents} agent{activeAgents === 1 ? '' : 's'}</span>
          <span>{activeRuns} run{activeRuns === 1 ? '' : 's'} in flight</span>
          {p && <span>{p.openIssues} open issues</span>}
          {p?.workspace?.valid && (
            <span className="tag" title={`Several repositories checked out side by side (${p.workspace.path})`}>
              workspace · {plural(p.workspace.members.length, 'member')}
            </span>
          )}
          {p && <a href={p.htmlUrl} target="_blank" rel="noreferrer">GitHub ↗</a>}
        </div>
        {iconError && (
          <div className="ph-alert" role="alert">
            <span>{iconError}</span>
            <button type="button" className="btn btn--ghost btn--sm" onClick={() => setIconError(null)}>Dismiss</button>
          </div>
        )}
      </header>

      <div className="project-tabs" role="tablist" aria-label="Project sections">
        {(['chat', 'issues', 'activity'] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'is-on' : ''} onClick={() => setTab(t)}>
            {t === 'activity' ? `agents & runs${activeAgents + activeRuns ? ` · ${activeAgents + activeRuns}` : ''}` : t}
          </button>
        ))}
      </div>

      <div className="project-grid3">
        <aside className="panel panel--issues" aria-label="Issues">
          <IssuesPanel repo={repo} releases={releases.releases} workspace={p?.workspace ?? null} />
        </aside>
        <section className="panel panel--chat" aria-label="Chat">
          <ChatPanel
            repo={repo}
            current={current}
            chats={chats}
            closedNotice={closedNotice}
            onDismissClosed={() => clearSession()}
            onSelect={selectSession}
            onCreated={(s) => (sessionsBase.setData((d) => [s, ...(d ?? [])]), selectSession(s.id))}
            onCloseChat={setClosing}
          />
        </section>
        <aside className="panel panel--activity" aria-label="Agents and runs">
          <ActivityPanel
            repo={repo}
            runs={runs}
            sessions={sessions}
            loading={!runsBase.data || !sessionsBase.data}
            releases={releases}
            workspace={p?.workspace ?? null}
            onSelectChat={selectSession}
            onCloseChat={setClosing}
            currentId={closedNotice ? null : current?.id ?? null}
          />
        </aside>
      </div>
      <CloseChatDialog chat={closing} onClose={() => setClosing(null)} closedByMe={closedByMe.current} onClosed={onClosed} />
    </div>
  );
}

// ---------------------------------------------------------------- chat

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** Why a checkout that is behind was not updated (the server's `<checkout_status>` texts, without backticks). */
function checkoutWhy(c: CheckoutInfo): string {
  switch (c.reason) {
    case 'other_branch':
      return c.branch == null ? `a detached HEAD is checked out, not ${c.defaultBranch}` : `branch ${c.branch} is checked out, not ${c.defaultBranch}`;
    case 'local_changes':
      return 'it has uncommitted changes';
    case 'local_commits':
      return `it has ${plural(c.ahead, 'local commit')} that are not on origin/${c.defaultBranch}`;
    default:
      return 'git could not fast-forward it';
  }
}

/** The chat's checkout next to the path in the chat header: commit, branch when not the default, and how far behind. */
function CheckoutBadge({ checkout: c }: { checkout: CheckoutInfo }) {
  const sha = c.commit.slice(0, 7);
  const behind = c.status === 'behind' && c.behind > 0;
  const where = c.branch === c.defaultBranch ? '' : c.branch == null ? 'detached ' : `${c.branch} `;
  const sentence = behind
    ? `Checkout at ${sha}, ${plural(c.behind, 'commit')} behind origin/${c.defaultBranch}. Not updated automatically: ${checkoutWhy(c)}.`
    : c.branch == null
      ? `Checkout at ${sha} on a detached HEAD, up to date with origin/${c.defaultBranch}.`
      : `Checkout at ${sha} on \`${c.branch}\`, up to date with origin/${c.defaultBranch}.`;
  return (
    <span className="chat-head-checkout" title={sentence}>
      <span aria-hidden>
        {' · '}
        {where}
        {sha}
        {behind && (
          <>
            {' · '}
            <span className="chat-head-behind">{c.behind} behind</span>
          </>
        )}
      </span>
      <span className="sr-only">{sentence}</span>
    </span>
  );
}

function ChatPanel({
  repo,
  current,
  chats,
  closedNotice,
  onDismissClosed,
  onSelect,
  onCreated,
  onCloseChat,
}: {
  repo: string;
  current: AgentSession | null;
  chats: AgentSession[];
  /** The chat named by `?session=` was closed by someone else: show that instead of a chat. */
  closedNotice: boolean;
  onDismissClosed: () => void;
  onSelect: (id: string) => void;
  onCreated: (s: AgentSession) => void;
  onCloseChat: (s: AgentSession) => void;
}) {
  const me = useMe();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const canStart = me.hasClaudeCredential;

  const create = async () => {
    setBusy(true);
    setErr(null);
    try {
      onCreated(await api.createChat(repo));
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const noClaude = (
    <span>
      Chat is disabled: agents run on <em>your</em> Claude credential and you haven’t registered one. <Link to="/settings#claude">Set it up in Settings</Link> — it takes a minute with <code>claude setup-token</code>.
    </span>
  );

  const header = (checkout: CheckoutInfo | null) => (
    <div className="chat-head">
      <label className="chat-pick">
        <span className="sr-only">Chat session</span>
        <select value={closedNotice ? CLOSED_OPTION : current?.id ?? ''} onChange={(e) => onSelect(e.target.value)} disabled={chats.length === 0}>
          {closedNotice && (
            <option value={CLOSED_OPTION} disabled>
              closed chat
            </option>
          )}
          {!current && !closedNotice && <option value="">No chat yet</option>}
          {chats.map((s) => (
            <option key={s.id} value={s.id}>
              {chatLabel(s, me.username)} · {s.status} · {timeAgo(s.updatedAt)}
            </option>
          ))}
        </select>
      </label>
      <span className="chat-head-title mono">
        ~/{repo}
        {checkout && <CheckoutBadge checkout={checkout} />}
      </span>
      {current && !closedNotice && closable(current, me) && (
        <button type="button" className="btn btn--ghost btn--sm btn--danger" onClick={() => onCloseChat(current)} title="Stop the agent and delete this chat">
          Close chat
        </button>
      )}
      <button type="button" className="btn btn--ghost btn--sm" onClick={() => void create()} disabled={!canStart || busy} title={canStart ? 'Start a fresh conversation' : 'Needs your Claude credential'}>
        + New chat
      </button>
    </div>
  );

  if (closedNotice) {
    return (
      <div className="chat chat--empty">
        {header(null)}
        <div className="chat-start chat-closed" role="status">
          <h3>This chat was closed</h3>
          <p className="muted">Its owner or an admin closed it. The transcript has been deleted.</p>
          <button type="button" className="btn btn--ghost" onClick={onDismissClosed}>
            Dismiss
          </button>
        </div>
      </div>
    );
  }

  if (!current) {
    return (
      <div className="chat chat--empty">
        {header(null)}
        <div className="chat-start">
          <pre className="ascii" aria-hidden>{`   ╭──────────────╮
   │  > _         │
   ╰──────────────╯`}</pre>
          <h3>Talk to the {repo} agent</h3>
          <p className="muted">A Claude Code session in your own clone of the repo. It can read code, run tests, file issues and start pipeline runs with <code>/factory</code>.</p>
          {err && <p className="form-error">{err}</p>}
          {canStart ? (
            <button className="btn btn--primary" onClick={() => void create()} disabled={busy}>{busy ? 'Starting…' : 'Start chat'}</button>
          ) : (
            <Notice tone="info" title="Chat needs your Claude credential">{noClaude}</Notice>
          )}
        </div>
      </div>
    );
  }
  return <ChatView key={current.id} sessionId={current.id} session={current} header={({ checkout }) => header(checkout)} disabledReason={canStart ? undefined : noClaude} />;
}

/** Value of the picker's placeholder while the "was closed" notice shows (never a session id). */
const CLOSED_OPTION = '__closed__';

/** Confirms closing a chat and calls the API; a 404 counts as success (the chat is already gone). */
function CloseChatDialog({ chat, onClose, closedByMe, onClosed }: { chat: AgentSession | null; onClose: () => void; closedByMe: Set<string>; onClosed: (id: string) => void }) {
  const me = useMe();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    setErr(null);
    setBusy(false);
  }, [chat?.id]);

  const confirm = async () => {
    if (!chat) return;
    const id = chat.id;
    setBusy(true);
    setErr(null);
    closedByMe.add(id);
    try {
      await api.closeChat(id);
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) {
        closedByMe.delete(id);
        setErr(e instanceof ApiError ? e.message : 'Could not close the chat.');
        setBusy(false);
        return;
      }
    }
    setBusy(false);
    onClosed(id);
    onClose();
  };

  const subject = chat && chat.owner.username !== me.username ? `${chat.owner.username}’s chat` : 'this chat';
  return (
    <Dialog open={chat !== null} onClose={onClose} title="Close chat?">
      <div className="form">
        <p>
          This stops the agent and permanently deletes {subject} with its transcript and attachments. Pipeline runs started from it keep running. This cannot be undone.
        </p>
        {err && (
          <p className="form-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={busy} autoFocus>
            Cancel
          </button>
          <button type="button" className="btn btn--danger" onClick={() => void confirm()} disabled={busy}>
            {busy ? 'Closing…' : 'Close chat'}
          </button>
        </div>
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- issues

const KINDS: ('all' | IssueKind)[] = ['all', 'feature', 'bug', 'task'];

/** Prepends `i` unless an issue with that number is already listed (dialog insert and feed frame race). */
const addIssue = (list: Issue[], i: Issue) => (list.some((x) => x.number === i.number) ? list : [i, ...list]);

/** Release filter: every issue, issues in no release, or one release's id. */
type ReleaseFilter = 'all' | 'none' | string;

/** Option value for "take the issues out of their release" in the bulk bar (never a release id). */
const NO_RELEASE = '__none__';

/**
 * Why a run cannot start on [i] in a workspace project (ARCHITECTURE §6 "Workspace runs"), or null when it can (or the
 * project is not a workspace). The server refuses the same cases.
 */
function workspaceStartBlock(ws: Workspace | null, i: Issue): string | null {
  if (!ws) return null;
  if (!ws.valid) return `Fix ${ws.path} first`;
  const m = i.workspaceMembers ?? [];
  // A workspace from .gitmodules alone: an issue without a member label changes the workspace repository itself.
  if (m.length === 0) return fromSubmodules(ws) ? null : 'Add one member label on GitHub first';
  if (m.length > 1) return 'Keep exactly one member label';
  const member = ws.members.find((x) => x.directory === m[0].directory);
  if (member && !member.accessible) return `Your GitHub token cannot read ${member.repository}`;
  return null;
}

/**
 * A workspace whose members come from `.gitmodules` alone (ARCHITECTURE §6 "Workspace runs"): its own issues (no member
 * label) are runs of the workspace repository, and it has releases and a sandbox of its own.
 */
const fromSubmodules = (ws: Workspace | null) => ws?.path === '.gitmodules';

/** The member an issue of a workspace project belongs to, as shown in its row. */
function IssueMember({ ws, issue }: { ws: Workspace; issue: Issue }) {
  const m = issue.workspaceMembers ?? [];
  if (m.length === 0 && fromSubmodules(ws)) {
    return (
      <span className="tag tag--plain issue-member issue-member--parent" title="No member label: a run changes the workspace repository itself">
        workspace repository
      </span>
    );
  }
  if (m.length === 0) return <span className="tag tag--plain issue-member issue-member--none">no member label</span>;
  if (m.length > 1) return <span className="tag tag--plain issue-member issue-member--none">several member labels</span>;
  const member = ws.members.find((x) => x.directory === m[0].directory);
  return (
    <>
      <span className="tag tag--plain issue-member" title={`Member ${m[0].directory} · ${m[0].repository}`}>{m[0].directory}</span>
      {member && !member.accessible && <span className="tag tag--plain issue-member--none">no access</span>}
    </>
  );
}

function IssuesPanel({ repo, releases, workspace }: { repo: string; releases: Release[]; workspace: Workspace | null }) {
  const me = useMe();
  const nav = useNavigate();
  const { noteReleases } = useFeed();
  const [state, setStateRaw] = useState<'open' | 'closed'>('open');
  const [kind, setKind] = useState<'all' | IssueKind>('all');
  const [releaseFilter, setReleaseFilter] = useState<ReleaseFilter>('all');
  const [creating, setCreating] = useState(false);
  const [imagesFor, setImagesFor] = useState<number | null>(null);
  const [starting, setStarting] = useState<number | null>(null);
  const [assigning, setAssigning] = useState<Set<number>>(new Set());
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkTarget, setBulkTarget] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const reconnects = useFeedReconnects();
  const issues = useAsync(() => api.issues(repo, state), [repo, state, reconnects]);
  const setState = (s: 'open' | 'closed') => {
    setStateRaw(s);
    setSelected(new Set());
  };

  // Issues created through Agern arrive on the feed. Frames that arrive mid-load are held back and merged once the
  // load has delivered, so the fresh list does not overwrite them.
  const pending = useRef<Issue[]>([]);
  const merge = (i: Issue) => {
    if (i.state === state) issues.setData((d) => d && addIssue(d, i));
  };
  useIssueFeed(repo, (i) => {
    if (issues.loading) pending.current.push(i);
    else merge(i);
  });
  useEffect(() => {
    pending.current = [];
    setSelected(new Set());
    setReleaseFilter('all');
  }, [repo]);
  useEffect(() => {
    if (issues.loading || !pending.current.length) return;
    const batch = pending.current;
    pending.current = [];
    batch.forEach(merge);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issues.data, issues.loading]);

  // Assignments come from the live release list, so changes made elsewhere show without a reload.
  const releaseOf = useMemo(() => {
    const m = new Map<number, Release>();
    for (const r of releases) for (const i of r.issues) m.set(i.number, r);
    return m;
  }, [releases]);
  const hasReleases = releases.length > 0;
  // Released and cancelled releases take no new issues; they are not offered as targets.
  const targets = useMemo(() => releases.filter(isReleaseOpen), [releases]);
  // A deleted release falls back to all issues.
  const filter: ReleaseFilter =
    releaseFilter === 'all' || releaseFilter === 'none' || releases.some((r) => r.id === releaseFilter) ? releaseFilter : 'all';
  const inFilter = (i: Issue) =>
    !hasReleases || filter === 'all' || (filter === 'none' ? !releaseOf.has(i.number) : releaseOf.get(i.number)?.id === filter);
  const list = (issues.data ?? []).filter((i) => (kind === 'all' || i.kind === kind) && inFilter(i));
  const counts = useMemo(() => {
    const c = { all: 0, feature: 0, bug: 0, task: 0 } as Record<string, number>;
    for (const i of issues.data ?? []) {
      c.all++;
      c[i.kind]++;
    }
    return c;
  }, [issues.data]);
  // Only open issues that are still listed count as selected.
  const openNumbers = new Set((issues.data ?? []).filter((i) => i.state === 'open').map((i) => i.number));
  const selection = [...selected].filter((n) => openNumbers.has(n));

  const start = async (i: Issue) => {
    setStarting(i.number);
    setErr(null);
    try {
      const run = await api.startRun(repo, i.number);
      nav(`/p/${repo}/runs/${run.id}`);
    } catch (e) {
      setErr((e as Error).message);
      setStarting(null);
      issues.reload();
    }
  };

  /** Assigns the issues to a release (null: takes them out); true on success. */
  const assign = async (numbers: number[], releaseId: string | null): Promise<boolean> => {
    setErr(null);
    setAssigning((prev) => new Set([...prev, ...numbers]));
    try {
      noteReleases(await api.assignIssues(repo, numbers, releaseId));
      return true;
    } catch (e) {
      setErr((e as Error).message);
      return false;
    } finally {
      setAssigning((prev) => new Set([...prev].filter((n) => !numbers.includes(n))));
    }
  };

  const toggle = (n: number, on: boolean) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (on) next.add(n);
      else next.delete(n);
      return next;
    });

  const assignSelected = async () => {
    if (!selection.length || !bulkTarget) return;
    if (await assign(selection, bulkTarget === NO_RELEASE ? null : bulkTarget)) setSelected(new Set());
  };

  return (
    <>
      <div className="panel-head">
        <h2>Issues</h2>
        <button className="btn btn--sm" onClick={() => setCreating(true)}>+ New</button>
      </div>
      <div className="filters">
        <div className="seg" role="radiogroup" aria-label="Issue kind">
          {KINDS.map((k) => (
            <button key={k} role="radio" aria-checked={kind === k} className={kind === k ? 'is-on' : ''} onClick={() => setKind(k)}>
              {k} <span className="seg-n">{counts[k]}</span>
            </button>
          ))}
        </div>
        <div className="seg seg--small" role="radiogroup" aria-label="Issue state">
          {(['open', 'closed'] as const).map((s) => (
            <button key={s} role="radio" aria-checked={state === s} className={state === s ? 'is-on' : ''} onClick={() => setState(s)}>{s}</button>
          ))}
        </div>
        {hasReleases && (
          <span className="release-filter">
            <select aria-label="Release" value={filter} onChange={(e) => setReleaseFilter(e.target.value)}>
              <option value="all">All issues</option>
              <option value="none">No release</option>
              {releases.map((r) => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
          </span>
        )}
      </div>
      {hasReleases && selection.length > 0 && (
        <div className="bulk-bar" role="group" aria-label="Selected issues">
          <span className="bulk-n">{selection.length} selected</span>
          <span className="bulk-target">
            <select aria-label="Assign selected to" value={bulkTarget} onChange={(e) => setBulkTarget(e.target.value)}>
              <option value="" disabled>Assign to…</option>
              {targets.map((r) => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
              <option value={NO_RELEASE}>No release</option>
            </select>
          </span>
          <button
            type="button"
            className="btn btn--sm btn--primary"
            onClick={() => void assignSelected()}
            disabled={!bulkTarget || selection.some((n) => assigning.has(n))}
          >
            Assign
          </button>
          <button type="button" className="btn btn--sm btn--ghost" onClick={() => setSelected(new Set())}>
            Clear
          </button>
        </div>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}
      {issues.loading && !issues.data && <Loading />}
      {issues.error && <ErrorBox error={issues.error} retry={issues.reload} />}
      {issues.data && list.length === 0 && (
        <p className="muted pad">
          No {state} {kind === 'all' ? 'issues' : `${kind}s`}
          {hasReleases && filter !== 'all' ? (filter === 'none' ? ' without a release' : ' in this release') : ''}.
        </p>
      )}
      <ul className="issue-list">
        {list.map((i) => {
          const rel = releaseOf.get(i.number);
          const pickable = hasReleases && i.state === 'open';
          return (
            <li key={i.number} className={`issue ${pickable && selected.has(i.number) ? 'is-selected' : ''}`}>
              <div className="issue-main">
                {pickable && (
                  <input
                    type="checkbox"
                    className="issue-check"
                    aria-label={`Select #${i.number}`}
                    checked={selected.has(i.number)}
                    onChange={(e) => toggle(i.number, e.target.checked)}
                  />
                )}
                <KindBadge kind={i.kind} />
                <a href={i.htmlUrl} target="_blank" rel="noreferrer" className="issue-title">
                  <span className="issue-num">#{i.number}</span> {i.title}
                </a>
              </div>
              <div className="issue-meta">
                {workspace && <IssueMember ws={workspace} issue={i} />}
                {rel && (
                  <Link to={`/p/${repo}/releases/${rel.id}`} className="tag release-tag" title={`Planned in release ${rel.name}`}>
                    {rel.name}
                  </Link>
                )}
                <span>{i.author}</span>
                <span>· {timeAgo(i.updatedAt)}</span>
                {i.comments > 0 && <span>· {i.comments} comment{i.comments === 1 ? "" : "s"}</span>}
                {i.imageCount > 0 && (
                  <button type="button" className="btn btn--sm btn--ghost issue-images-btn" onClick={() => setImagesFor(i.number)}>
                    {i.imageCount} {i.imageCount === 1 ? 'image' : 'images'}
                  </button>
                )}
                <span className="issue-action">
                  {pickable && (
                    <span className="release-pick">
                      <select aria-label={`Release for #${i.number}`} value={rel?.id ?? ''} disabled={assigning.has(i.number)} onChange={(e) => void assign([i.number], e.target.value || null)}>
                        <option value="">No release</option>
                        {targets.map((r) => (
                          <option key={r.id} value={r.id}>{r.name}</option>
                        ))}
                        {rel && isReleaseFinished(rel) && (
                          <option value={rel.id} disabled>{rel.name} ({rel.status})</option>
                        )}
                      </select>
                    </span>
                  )}
                  {i.activeRunId ? (
                    <Link to={`/p/${repo}/runs/${i.activeRunId}`} className="run-link"><span className="live-dot" aria-hidden /> run in progress</Link>
                  ) : i.state === 'open' ? (
                    <button
                      className="btn btn--sm btn--ghost btn--run"
                      onClick={() => void start(i)}
                      disabled={!me.hasClaudeCredential || starting !== null || workspaceStartBlock(workspace, i) !== null}
                      title={
                        !me.hasClaudeCredential
                          ? 'Needs your Claude credential (Settings)'
                          : workspaceStartBlock(workspace, i) ?? 'Hand this issue to the pipeline'
                      }
                    >
                      {starting === i.number ? 'Starting…' : '▶ Start run'}
                    </button>
                  ) : null}
                </span>
              </div>
            </li>
          );
        })}
      </ul>
      {!me.hasClaudeCredential && <p className="muted small pad">Starting runs needs your Claude credential. <Link to="/settings#claude">Settings</Link></p>}
      <IssueImagesDialog repo={repo} issue={imagesFor} me={me.username} onClose={() => setImagesFor(null)} onChanged={issues.reload} />
      <NewIssueDialog repo={repo} open={creating} onClose={() => setCreating(false)} onCreated={(i) => issues.setData((d) => addIssue(d ?? [], i))} />
    </>
  );
}

function NewIssueDialog({ repo, open, onClose, onCreated }: { repo: string; open: boolean; onClose: () => void; onCreated: (i: Issue) => void }) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [kind, setKind] = useState<IssueKind>('feature');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      onCreated(await api.createIssue(repo, { title: title.trim(), body, kind }));
      setTitle('');
      setBody('');
      onClose();
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onClose={onClose} title={`New issue in ${repo}`}>
      <form className="form" onSubmit={submit}>
        <fieldset className="seg seg--wide" aria-label="Kind">
          {(['feature', 'bug', 'task'] as IssueKind[]).map((k) => (
            <label key={k} className={kind === k ? 'is-on' : ''}>
              <input type="radio" name="kind" value={k} checked={kind === k} onChange={() => setKind(k)} className="sr-only" />
              {k}
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor="it">Title</label>
          <input id="it" value={title} onChange={(e) => setTitle(e.target.value)} required autoFocus />
        </div>
        <div className="field">
          <label htmlFor="ib">Description <span className="muted">(markdown)</span></label>
          <textarea id="ib" rows={7} value={body} onChange={(e) => setBody(e.target.value)} placeholder="What should happen, and how will we know it works?" />
        </div>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn--primary" disabled={busy || !title.trim()}>{busy ? 'Creating…' : 'Create issue'}</button>
        </div>
      </form>
    </Dialog>
  );
}

/** "N issues" while planning; "k of n merged" (plus "· h held") once started; "· automatic" until it is finished. */
function releaseProgress(r: Release): string {
  const n = r.issues.length;
  const auto = r.mode === 'automatic' && !isReleaseFinished(r) ? ' · automatic' : '';
  if (r.status === 'planning' || r.status === 'scheduled') return `${n} issue${n === 1 ? '' : 's'}${auto}`;
  const merged = r.issues.filter((i) => i.state === 'merged').length;
  const held = r.issues.filter((i) => i.state === 'held').length;
  const conflict = r.status === 'ready' && held === 0 && r.mergeState === 'conflict';
  return `${merged} of ${n} merged${held > 0 ? ` · ${held} held` : ''}${conflict ? ' · conflict' : ''}${auto}`;
}

/** Released and cancelled releases, newest first (by release time, else last change). */
function historyOf(releases: Release[]): Release[] {
  const at = (r: Release) => r.releasedAt ?? r.updatedAt;
  return releases.filter(isReleaseFinished).sort((a, b) => at(b).localeCompare(at(a)));
}

/** Released and cancelled releases, collapsed under "History (N)"; not rendered without any. */
function ReleaseHistory({ repo, releases }: { repo: string; releases: Release[] }) {
  if (releases.length === 0) return null;
  return (
    <details className="release-history">
      <summary>History ({releases.length})</summary>
      <ul className="run-list release-list">
        {releases.map((r) => {
          const n = r.issues.length;
          return (
            <li key={r.id}>
              <Link to={`/p/${repo}/releases/${r.id}`} className="run-row release-row">
                <div className="rr-top">
                  <span className="rr-title">{r.name}</span>
                </div>
                <div className="rr-bot">
                  <ReleaseStatusPill status={r.status} />
                  <span className="rr-line">
                    {n} issue{n === 1 ? '' : 's'}
                    {r.status === 'released' && r.releasedAt && <> · {timeAgo(r.releasedAt)}</>}
                  </span>
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
    </details>
  );
}

/** Creates a release; the new release shows through the shared live list (once, even though its feed frame follows). */
function NewReleaseDialog({ repo, open, onClose }: { repo: string; open: boolean; onClose: () => void }) {
  const { noteReleases } = useFeed();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [mode, setMode] = useState<ReleaseMode>('manual');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (open) setErr(null);
  }, [open]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      noteReleases([await api.createRelease(repo, { name: name.trim(), description, mode })]);
      setName('');
      setDescription('');
      setMode('manual');
      onClose();
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onClose={onClose} title={`New release in ${repo}`}>
      <form className="form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="rn">Name</label>
          <input id="rn" value={name} onChange={(e) => setName(e.target.value)} required maxLength={50} autoFocus aria-describedby="rn-hint" className="mono" />
          <p id="rn-hint" className="muted small">
            Letters, digits, <code>.</code>, <code>_</code>, <code>-</code>; becomes the branch <code>release/&lt;name&gt;</code>
          </p>
        </div>
        <div className="field">
          <label htmlFor="rd">
            Description <span className="muted">(optional)</span>
          </label>
          <textarea id="rd" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} />
        </div>
        <fieldset className="field mode-choice">
          <legend>When it is ready</legend>
          <label>
            <input type="radio" name="release-mode" value="manual" checked={mode === 'manual'} onChange={() => setMode('manual')} />
            <span>
              Manual approval <span className="muted small">— it waits until whoever starts it approves the release.</span>
            </span>
          </label>
          <label>
            <input type="radio" name="release-mode" value="automatic" checked={mode === 'automatic'} onChange={() => setMode('automatic')} />
            <span>
              Release automatically{' '}
              <span className="muted small">
                — once everything is built, merges cleanly and all checks on the release pull request have passed, Agern merges it with the
                owner&apos;s GitHub token.
              </span>
            </span>
          </label>
        </fieldset>
        {err && (
          <p className="form-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn--primary" disabled={busy || !name.trim()}>
            {busy ? 'Creating…' : 'Create release'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------------------------------------------------------------- activity

/** The project's sandbox state in one row, linking to its sandbox page; live when an admin approves or revokes. */
/** The members of a workspace project (ARCHITECTURE §5 "Workspace projects"), or why its definition is invalid. */
function MembersSection({ repo, workspace }: { repo: string; workspace: Workspace }) {
  const [missing, setMissing] = useState(workspace.missingLabels);
  const [creating, setCreating] = useState(false);
  const [labelError, setLabelError] = useState<Error | null>(null);
  useEffect(() => setMissing(workspace.missingLabels), [workspace.missingLabels]);
  const createLabels = async () => {
    setCreating(true);
    setLabelError(null);
    try {
      setMissing((await api.createMemberLabels(repo)).missing);
    } catch (e) {
      setLabelError(e instanceof Error ? e : new Error(String(e)));
    } finally {
      setCreating(false);
    }
  };
  return (
    <>
      <div className="panel-head">
        <h2>Members</h2>
        {workspace.valid && <span className="muted small">{workspace.members.length}</span>}
      </div>
      {workspace.valid && missing.length > 0 && (
        <div className="member-labels-missing">
          <Notice
            tone="warn"
            title="Member labels missing on GitHub"
            action={
              <button type="button" className="btn btn--sm" disabled={creating} onClick={() => void createLabels()}>
                {creating ? 'Creating…' : 'Create missing labels'}
              </button>
            }
          >
            Issues are routed to a member by its label. Missing: {missing.map((l, n) => (
              <span key={l}>
                {n > 0 && ', '}
                <code>{l}</code>
              </span>
            ))}
          </Notice>
          {labelError && <p className="muted small pad member-labels-error" role="alert">{labelError.message}</p>}
        </div>
      )}
      {!workspace.valid ? (
        <div className="ws-invalid" role="alert">
          <p>
            <code>{workspace.path}</code> is invalid. Runs cannot start until it is fixed.
          </p>
          <ul>
            {workspace.errors.map((e, n) => (
              <li key={n} className="mono">{e}</li>
            ))}
          </ul>
        </div>
      ) : (
        <ul className="run-list member-list">
          {workspace.members.map((m) => (
            <li key={m.directory} className="run-row member-row">
              <div className="rr-top">
                <span className="mono member-dir">{m.directory}</span>
                {m.accessible ? (
                  <Link to={`/p/${m.repository}`} className="member-repo">{m.repository}</Link>
                ) : (
                  <span className="member-repo">{m.repository}</span>
                )}
                {!m.accessible && (
                  <span className="pill member-noaccess" title="Your GitHub token cannot read this repository; it is left out of your checkouts.">
                    no access
                  </span>
                )}
                {m.submodule && (
                  <span
                    className="tag tag--plain member-submodule"
                    title="A git submodule of the workspace repository. After a run's pull request merges, Agern opens a pull request that moves the submodule pointer."
                  >
                    submodule
                  </span>
                )}
              </div>
              <div className="rr-bot">
                {m.branch ? <span className="mono member-branch">{m.branch}</span> : <span className="muted">default branch</span>}
                <span className="tag tag--plain" title="Issues with this label belong to this member">{m.label}</span>
              </div>
            </li>
          ))}
        </ul>
      )}
      {workspace.valid && workspace.contextOnly.length > 0 && (
        <p className="muted small pad member-context">
          Context only (submodules of other organizations, never a run's target):{' '}
          {workspace.contextOnly.map((c, n) => (
            <span key={c}>
              {n > 0 && ', '}
              <code>{c}</code>
            </span>
          ))}
        </p>
      )}
    </>
  );
}

function SandboxSection({ repo }: { repo: string }) {
  const reconnects = useFeedReconnects();
  const q = useAsync(() => api.sandboxStatus(repo), [repo, reconnects]);
  useSandboxApprovalFeed(repo, (approval) => q.setData((st) => st && withApproval(st, approval)));
  const s = q.data && sandboxSummary(q.data);
  return (
    <>
      <div className="panel-head">
        <h2>Sandbox</h2>
      </div>
      {q.loading && !q.data && <Loading />}
      {q.error && !q.data && (
        <p className="form-error pad" role="alert">
          Could not read the sandbox definition: {q.error.message}{' '}
          <button type="button" className="btn btn--ghost btn--sm" onClick={q.reload}>Retry</button>
        </p>
      )}
      {q.data && s && (
        <ul className="run-list">
          <li>
            <Link to={`/p/${repo}/sandbox`} className={`run-row sbx-row sbx-row--${s.tone}`}>
              <div className="rr-top">
                <span className="rr-title mono">{q.data.path}</span>
              </div>
              <div className="rr-bot">
                <span className={`pill pill--${s.tone}`}>
                  <span className="pill-dot" aria-hidden />
                  {s.label}
                </span>
                <span className="rr-line">{s.line}</span>
              </div>
            </Link>
          </li>
        </ul>
      )}
    </>
  );
}

export function runStageLine(r: Run): string {
  const running = r.stages.filter((s) => s.status === 'running').map((s) => s.role);
  if (r.status === 'reviewing') {
    const done = r.stages.filter((s) => REVIEWERS.includes(s.role) && s.round === r.round && s.status === 'done').length;
    return `reviewing ${done}/4 · ${roundName(r.round, r)}`;
  }
  if (running.length) return `${running.join(', ')} · ${roundName(r.round, r)}`;
  if (r.status === 'merged') return r.prNumber ? `merged PR #${r.prNumber}` : 'merged';
  if (r.status === 'failed') return r.error ?? 'failed';
  return r.status.replace('_', ' ');
}

function ActivityPanel({
  repo,
  runs,
  sessions,
  loading,
  releases,
  workspace,
  onSelectChat,
  onCloseChat,
  currentId,
}: {
  repo: string;
  runs: Run[];
  sessions: AgentSession[];
  loading: boolean;
  releases: { releases: Release[]; loading: boolean; error: Error | undefined; reload: () => void };
  /** The project's workspace definition; null for a project that is one repository. */
  workspace: Workspace | null;
  onSelectChat: (id: string) => void;
  onCloseChat: (s: AgentSession) => void;
  currentId: string | null;
}) {
  useNow(5000);
  const me = useMe();
  // Releases and the sandbox belong to the project unless it is a workspace.yml workspace (§6 "Workspace runs").
  const ownReleases = !workspace || fromSubmodules(workspace);
  const active = sessions.filter(isSessionActive);
  const runList = runs.slice(0, 12);
  const chats = sessions.filter((s) => s.kind === 'chat');
  const [creatingRelease, setCreatingRelease] = useState(false);
  return (
    <>
      <div className="panel-head">
        <h2>Agents</h2>
        <span className="muted small">{active.length} active</span>
      </div>
      {loading && <Loading />}
      {!loading && active.length === 0 && <p className="muted pad">No agents running in {repo}.</p>}
      <ul className="agent-list">
        {active.map((s) => {
          const inner = (
            <>
              <div className="al-top">
                <RoleBadge kind={s.kind} />
                <span className="al-owner">{s.owner.username}</span>
                <SessionStatusPill status={s.status} />
              </div>
              <div className="al-activity mono" title={s.lastActivity ?? ''}>
                {s.status === 'running' && <span className="al-caret" aria-hidden>⏺</span>} {s.lastActivity ?? (s.kind === 'chat' || (s.kind === 'advisor' && s.status === 'idle') ? 'waiting for input' : 'starting…')}
              </div>
              <div className="al-meta">
                {s.issueNumber && <span>#{s.issueNumber}</span>}
                <span>{modelLabel(s.model)}</span>
                <span>{timeAgo(s.updatedAt)}</span>
              </div>
            </>
          );
          return (
            <li key={s.id} className={`agent ${s.id === currentId ? 'is-current' : ''}`}>
              {s.kind === 'chat' ? (
                <button type="button" className="agent-btn" onClick={() => onSelectChat(s.id)}>{inner}</button>
              ) : (
                <Link className="agent-btn" to={`/p/${repo}/runs/${s.runId}?stage=${s.kind === 'advisor' ? 'human' : s.kind}`}>{inner}</Link>
              )}
            </li>
          );
        })}
      </ul>
      {workspace && <MembersSection repo={repo} workspace={workspace} />}
      <div className="panel-head">
        <h2>Releases</h2>
        {ownReleases && (
          <button type="button" className="btn btn--sm" aria-label="New release" onClick={() => setCreatingRelease(true)}>
            + New
          </button>
        )}
      </div>
      {releases.error && <ErrorBox error={releases.error} retry={releases.reload} />}
      {releases.loading && <Loading />}
      {!releases.loading && !releases.error && releases.releases.length === 0 && (
        <p className="muted pad">{ownReleases ? 'No releases yet. Create one to plan issues ahead.' : 'Releases are not available for workspace projects yet.'}</p>
      )}
      <ul className="run-list release-list">
        {releases.releases.filter((r) => !isReleaseFinished(r)).map((r) => (
          <li key={r.id}>
            <Link to={`/p/${repo}/releases/${r.id}`} className="run-row release-row">
              <div className="rr-top">
                <span className="rr-title">{r.name}</span>
              </div>
              <div className="rr-bot">
                <ReleaseStatusPill status={r.status} />
                <span className="rr-line">
                  {releaseProgress(r)}
                  {r.status === 'scheduled' &&
                    (r.waitingFor ? (
                      <> · waiting for {r.waitingFor.name}</>
                    ) : (
                      r.scheduledAt && (
                        <>
                          {' '}· starts <time dateTime={r.scheduledAt}>{dateTime(r.scheduledAt)}</time>
                        </>
                      )
                    ))}
                  {r.status === 'planning' && r.startFailure && <span className="rr-failed"> · start failed</span>}
                </span>
              </div>
            </Link>
          </li>
        ))}
      </ul>
      <ReleaseHistory repo={repo} releases={historyOf(releases.releases)} />
      {ownReleases && <NewReleaseDialog repo={repo} open={creatingRelease} onClose={() => setCreatingRelease(false)} />}
      {/* A workspace's sandboxes belong to its member projects (a submodule workspace's own runs use its own). */}
      {ownReleases && <SandboxSection repo={repo} />}
      <div className="panel-head">
        <h2>Runs</h2>
      </div>
      {!loading && runList.length === 0 && <p className="muted pad">No pipeline runs yet. Start one from an issue.</p>}
      <ul className="run-list">
        {runList.map((r) => (
          <li key={r.id}>
            <Link to={`/p/${repo}/runs/${r.id}`} className={`run-row run-row--${r.status}`}>
              <div className="rr-top">
                <span className="mono">#{r.issueNumber}</span>
                <span className="rr-title">{r.issueTitle}</span>
              </div>
              <div className="rr-bot">
                <RunStatusPill status={r.status} />
                <span className="rr-line">{runStageLine(r)}</span>
              </div>
              <div className="rr-meta">{r.owner.username} · {timeAgo(r.updatedAt)}</div>
            </Link>
          </li>
        ))}
      </ul>
      <div className="panel-head">
        <h2>Chats</h2>
        <span className="muted small">{chats.length}</span>
      </div>
      {!loading && chats.length === 0 && <p className="muted pad">No chats in {repo} yet.</p>}
      <ul className="chat-list">
        {chats.map((s) => {
          const title = s.title ?? 'New chat';
          return (
            <li key={s.id} data-session-id={s.id} className={`chat-item ${s.id === currentId ? 'is-current' : ''}`}>
              <button type="button" className="chat-item-btn" onClick={() => onSelectChat(s.id)}>
                <span className="al-top">
                  <span className="al-owner">{chatLabel(s, me.username)}</span>
                  <SessionStatusPill status={s.status} />
                </span>
                <span className="ci-title">{title}</span>
                <span className="al-meta">{timeAgo(s.updatedAt)}</span>
              </button>
              {closable(s, me) && (
                <button type="button" className="btn btn--ghost btn--sm btn--danger chat-item-close" aria-label={`Close chat: ${title}`} onClick={() => onCloseChat(s)}>
                  Close
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}
