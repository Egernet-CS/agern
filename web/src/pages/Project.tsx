import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError, isRunWorking, isSessionActive, type AgentSession, type Issue, type IssueKind, type Run } from '../api';
import { ChatView } from '../components/chat/ChatView';
import { REVIEWERS, roundName } from '../components/pipeline/model';
import { Dialog, Empty, ErrorBox, KindBadge, Loading, Notice, RoleBadge, RunStatusPill, SessionStatusPill } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { modelLabel, timeAgo } from '../lib/format';
import { useLiveRuns, useLiveSessions } from '../lib/realtime';
import { useNow } from '../lib/useNow';

type Tab = 'chat' | 'issues' | 'activity';

export function ProjectPage() {
  const { repo = '' } = useParams();
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<Tab>('chat');
  const project = useAsync(() => api.project(repo), [repo]);
  const sessionsBase = useAsync(() => api.projectSessions(repo), [repo]);
  const runsBase = useAsync(() => api.projectRuns(repo), [repo]);
  const sessions = useLiveSessions(sessionsBase.data, (s) => s.repo === repo);
  const runs = useLiveRuns(runsBase.data, (r) => r.repo === repo);

  useEffect(() => {
    document.title = `${repo} · Agern`;
  }, [repo]);

  const chats = sessions.filter((s) => s.kind === 'chat');
  const mine = chats.filter((s) => s.owner.username === me.username);
  const requested = params.get('session');
  const current = (requested && sessions.find((s) => s.id === requested)) || mine.find((s) => !['stopped', 'failed', 'done'].includes(s.status)) || mine[0] || null;

  const selectSession = (id: string) => {
    params.set('session', id);
    setParams(params, { replace: true });
    setTab('chat');
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
          <h1>{repo}</h1>
          {p?.private && <span className="tag">private</span>}
        </div>
        <p className="ph-desc">{p?.description ?? ' '}</p>
        <div className="ph-meta">
          <span className={activeAgents ? 'ph-live' : ''}><span className="live-dot" aria-hidden /> {activeAgents} agent{activeAgents === 1 ? '' : 's'}</span>
          <span>{activeRuns} run{activeRuns === 1 ? '' : 's'} in flight</span>
          {p && <span>{p.openIssues} open issues</span>}
          {p && <a href={p.htmlUrl} target="_blank" rel="noreferrer">GitHub ↗</a>}
        </div>
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
          <IssuesPanel repo={repo} />
        </aside>
        <section className="panel panel--chat" aria-label="Chat">
          <ChatPanel repo={repo} current={current} chats={chats} onSelect={selectSession} onCreated={(s) => (sessionsBase.setData((d) => [s, ...(d ?? [])]), selectSession(s.id))} />
        </section>
        <aside className="panel panel--activity" aria-label="Agents and runs">
          <ActivityPanel repo={repo} runs={runs} sessions={sessions} loading={!runsBase.data || !sessionsBase.data} onSelectChat={selectSession} currentId={current?.id ?? null} />
        </aside>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- chat

function ChatPanel({ repo, current, chats, onSelect, onCreated }: { repo: string; current: AgentSession | null; chats: AgentSession[]; onSelect: (id: string) => void; onCreated: (s: AgentSession) => void }) {
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

  const header = (
    <div className="chat-head">
      <label className="chat-pick">
        <span className="sr-only">Chat session</span>
        <select value={current?.id ?? ''} onChange={(e) => onSelect(e.target.value)} disabled={chats.length === 0}>
          {!current && <option value="">No chat yet</option>}
          {chats.map((s) => (
            <option key={s.id} value={s.id}>
              {s.owner.username === me.username ? 'my chat' : `${s.owner.username}’s chat`} · {s.status} · {timeAgo(s.updatedAt)}
            </option>
          ))}
        </select>
      </label>
      <span className="chat-head-title mono">~/{repo}</span>
      <button type="button" className="btn btn--ghost btn--sm" onClick={() => void create()} disabled={!canStart || busy} title={canStart ? 'Start a fresh conversation' : 'Needs your Claude credential'}>
        + New chat
      </button>
    </div>
  );

  if (!current) {
    return (
      <div className="chat chat--empty">
        {header}
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
  return <ChatView key={current.id} sessionId={current.id} session={current} header={header} disabledReason={canStart ? undefined : noClaude} />;
}

// ---------------------------------------------------------------- issues

const KINDS: ('all' | IssueKind)[] = ['all', 'feature', 'bug', 'task'];

function IssuesPanel({ repo }: { repo: string }) {
  const me = useMe();
  const nav = useNavigate();
  const [state, setState] = useState<'open' | 'closed'>('open');
  const [kind, setKind] = useState<'all' | IssueKind>('all');
  const [creating, setCreating] = useState(false);
  const [starting, setStarting] = useState<number | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const issues = useAsync(() => api.issues(repo, state), [repo, state]);
  const list = (issues.data ?? []).filter((i) => kind === 'all' || i.kind === kind);
  const counts = useMemo(() => {
    const c = { all: 0, feature: 0, bug: 0, task: 0 } as Record<string, number>;
    for (const i of issues.data ?? []) {
      c.all++;
      c[i.kind]++;
    }
    return c;
  }, [issues.data]);

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
      </div>
      {err && <p className="form-error" role="alert">{err}</p>}
      {issues.loading && !issues.data && <Loading />}
      {issues.error && <ErrorBox error={issues.error} retry={issues.reload} />}
      {issues.data && list.length === 0 && <p className="muted pad">No {state} {kind === 'all' ? 'issues' : `${kind}s`}.</p>}
      <ul className="issue-list">
        {list.map((i) => (
          <li key={i.number} className="issue">
            <div className="issue-main">
              <KindBadge kind={i.kind} />
              <a href={i.htmlUrl} target="_blank" rel="noreferrer" className="issue-title">
                <span className="issue-num">#{i.number}</span> {i.title}
              </a>
            </div>
            <div className="issue-meta">
              <span>{i.author}</span>
              <span>· {timeAgo(i.updatedAt)}</span>
              {i.comments > 0 && <span>· {i.comments} comment{i.comments === 1 ? "" : "s"}</span>}
              <span className="issue-action">
                {i.activeRunId ? (
                  <Link to={`/p/${repo}/runs/${i.activeRunId}`} className="run-link"><span className="live-dot" aria-hidden /> run in progress</Link>
                ) : i.state === 'open' ? (
                  <button
                    className="btn btn--sm btn--ghost btn--run"
                    onClick={() => void start(i)}
                    disabled={!me.hasClaudeCredential || starting !== null}
                    title={me.hasClaudeCredential ? 'Hand this issue to the pipeline' : 'Needs your Claude credential (Settings)'}
                  >
                    {starting === i.number ? 'Starting…' : '▶ Start run'}
                  </button>
                ) : null}
              </span>
            </div>
          </li>
        ))}
      </ul>
      {!me.hasClaudeCredential && <p className="muted small pad">Starting runs needs your Claude credential. <Link to="/settings#claude">Settings</Link></p>}
      <NewIssueDialog repo={repo} open={creating} onClose={() => setCreating(false)} onCreated={(i) => issues.setData((d) => [i, ...(d ?? [])])} />
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

// ---------------------------------------------------------------- activity

export function runStageLine(r: Run): string {
  const running = r.stages.filter((s) => s.status === 'running').map((s) => s.role);
  if (r.status === 'reviewing') {
    const done = r.stages.filter((s) => REVIEWERS.includes(s.role) && s.round === r.round && s.status === 'done').length;
    return `reviewing ${done}/4 · ${roundName(r.round)}`;
  }
  if (running.length) return `${running.join(', ')} · ${roundName(r.round)}`;
  if (r.status === 'merged') return r.prNumber ? `merged PR #${r.prNumber}` : 'merged';
  if (r.status === 'failed') return r.error ?? 'failed';
  return r.status.replace('_', ' ');
}

function ActivityPanel({ repo, runs, sessions, loading, onSelectChat, currentId }: { repo: string; runs: Run[]; sessions: AgentSession[]; loading: boolean; onSelectChat: (id: string) => void; currentId: string | null }) {
  useNow(5000);
  const active = sessions.filter(isSessionActive);
  const runList = runs.slice(0, 12);
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
                {s.status === 'running' && <span className="al-caret" aria-hidden>⏺</span>} {s.lastActivity ?? (s.kind === 'chat' ? 'waiting for input' : 'starting…')}
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
                <Link className="agent-btn" to={`/p/${repo}/runs/${s.runId}?stage=${s.kind}`}>{inner}</Link>
              )}
            </li>
          );
        })}
      </ul>
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
    </>
  );
}
