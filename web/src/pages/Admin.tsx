import { useEffect, useRef, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api, ApiError, type McpToken, type RunningSandbox, type Settings, type SoftwareRequest, type StageRole, type User } from '../api';
import { shortHash, shortImage } from '../components/sandbox/SandboxBits';
import { McpTokenTable, RevokeMcpTokenDialog } from '../components/ui/McpTokens';
import { Dialog, ErrorBox, Loading, Notice } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { between, timeAgo } from '../lib/format';
import { useFeedReconnects, useSandboxesFeed } from '../lib/realtime';
import { useNow } from '../lib/useNow';

const MODELS = ['opus', 'sonnet', 'haiku', 'fable'];
const ROLES: StageRole[] = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver'];

export function AdminPage() {
  const me = useMe();
  useEffect(() => {
    document.title = 'Admin · Agern';
  }, []);
  if (!me.isAdmin) return <Navigate to="/" replace />;
  return (
    <div className="page">
      <h1 className="page-title">Admin</h1>
      <div className="admin-grid">
        <SettingsCard />
        <RequestsCard />
      </div>
      <SandboxesCard />
      <UsersCard />
      <McpTokensCard />
    </div>
  );
}

function ModelSelect({ id, value, onChange }: { id: string; value: string; onChange: (v: string) => void }) {
  const opts = MODELS.includes(value) ? MODELS : [...MODELS, value];
  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
      {opts.map((m) => <option key={m} value={m}>{m}</option>)}
    </select>
  );
}

function SettingsCard() {
  const q = useAsync(() => api.settings(), []);
  const [draft, setDraft] = useState<Settings | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (q.data) setDraft(q.data);
  }, [q.data]);
  if (q.error) return <ErrorBox error={q.error} retry={q.reload} />;
  if (!draft) return <section className="card"><Loading /></section>;
  const dirty = JSON.stringify(draft) !== JSON.stringify(q.data);
  const save = async () => {
    setBusy(true);
    setErr(null);
    try {
      q.setData(() => draft);
      setDraft(await api.putSettings(draft));
      setMsg('Saved. Applies to agents started from now on.');
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card settings-card" aria-labelledby="as-h">
      <header className="sc-head"><h2 id="as-h">Factory settings</h2></header>
      <label className="toggle">
        <input type="checkbox" checked={draft.agentsMayInstallSoftware} onChange={(e) => setDraft({ ...draft, agentsMayInstallSoftware: e.target.checked })} />
        <span className="toggle-ui" aria-hidden />
        <span>
          <strong>Agents may install software</strong>
          <span className="muted small block">
            {draft.agentsMayInstallSoftware ? 'Agents can install system-wide packages themselves.' : 'Agents are told not to; they file a request below instead (factory_request_software).'}
          </span>
        </span>
      </label>
      <h3 className="sd-h">Default models <span className="muted">(used when a user has not chosen their own)</span></h3>
      <p className="muted small">Each user can choose their own models and fix rounds under Settings → Models &amp; cost; agents run on their own Claude account.</p>
      <div className="form-grid">
        <label htmlFor="m-chat">chat</label>
        <ModelSelect id="m-chat" value={draft.chatModel} onChange={(v) => setDraft({ ...draft, chatModel: v })} />
        {ROLES.map((r) => (
          <FragmentRow key={r} role={r} value={draft.pipelineModels[r]} onChange={(v) => setDraft({ ...draft, pipelineModels: { ...draft.pipelineModels, [r]: v } })} />
        ))}
        <label htmlFor="m-rounds">default and maximum fix rounds</label>
        <input id="m-rounds" type="number" min={0} max={10} value={draft.maxFixRounds} onChange={(e) => setDraft({ ...draft, maxFixRounds: Math.min(10, Math.max(0, Number(e.target.value))) })} />
      </div>
      {err && <p className="form-error" role="alert">{err}</p>}
      {msg && !dirty && <p className="form-ok" role="status">{msg}</p>}
      <div className="row">
        <button className="btn btn--primary" disabled={!dirty || busy} onClick={() => void save()}>Save settings</button>
        {dirty && <button className="btn btn--ghost" onClick={() => setDraft(q.data!)}>Revert</button>}
      </div>
    </section>
  );
}

function FragmentRow({ role, value, onChange }: { role: StageRole; value: string; onChange: (v: string) => void }) {
  return (
    <>
      <label htmlFor={`m-${role}`}>{role}</label>
      <ModelSelect id={`m-${role}`} value={value} onChange={onChange} />
    </>
  );
}

function RequestsCard() {
  const q = useAsync(() => api.softwareRequests(), []);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const resolve = async (r: SoftwareRequest, status: SoftwareRequest['status']) => {
    const updated = await api.resolveSoftwareRequest(r.id, status, notes[r.id] || undefined);
    q.setData((d) => d?.map((x) => (x.id === r.id ? updated : x)));
  };
  const pending = (q.data ?? []).filter((r) => r.status === 'pending');
  const done = (q.data ?? []).filter((r) => r.status !== 'pending');
  return (
    <section className="card settings-card" aria-labelledby="sr-h">
      <header className="sc-head">
        <h2 id="sr-h">Software requests</h2>
        <span className="muted small">{pending.length} pending</span>
      </header>
      {q.loading && !q.data && <Loading />}
      {q.error && <ErrorBox error={q.error} retry={q.reload} />}
      <p className="muted small req-hint">
        Tools a project needs only to build or test (SDKs, browsers, a development database) can go in the project’s <code>.agern/sandbox.yml</code> instead — an image and a setup script, approved per project — rather than being installed on the host for everyone.
      </p>
      {q.data && pending.length === 0 && <p className="muted">No open requests. Install them by hand on the factory machine, then mark them here.</p>}
      <ul className="req-list">
        {pending.map((r) => (
          <li key={r.id} className="req">
            <div className="req-top">
              <code className="req-name">{r.name}</code>
              <span className="muted small">{r.repo} · {r.requestedBy} · {timeAgo(r.createdAt)}</span>
            </div>
            <p className="req-reason">{r.reason}</p>
            <div className="req-actions">
              <input placeholder="note (optional)" aria-label={`Note for ${r.name}`} value={notes[r.id] ?? ''} onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
              <button className="btn btn--sm btn--primary" onClick={() => void resolve(r, 'installed')}>Mark installed</button>
              <button className="btn btn--sm btn--ghost" onClick={() => void resolve(r, 'denied')}>Deny</button>
            </div>
          </li>
        ))}
      </ul>
      {done.length > 0 && (
        <details className="req-done">
          <summary>{done.length} resolved</summary>
          <ul>
            {done.map((r) => (
              <li key={r.id}><code>{r.name}</code> <span className={`st st--${r.status === 'installed' ? 'done' : 'failed'}`}>{r.status}</span> <span className="muted small">{r.note}</span></li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

/** Running sandboxes (with Stop), the runtime's limits and the projects whose definitions wait for an approval. */
function SandboxesCard() {
  const reconnects = useFeedReconnects();
  // Live: the server announces every sandbox container that comes or goes (admins only); the slow poll is a fallback.
  const q = useAsync(() => api.sandboxes(), [reconnects], { pollMs: 60_000 });
  const debounce = useRef<number | null>(null);
  useSandboxesFeed(() => {
    if (debounce.current !== null) window.clearTimeout(debounce.current);
    debounce.current = window.setTimeout(() => {
      debounce.current = null;
      q.reload();
    }, 250);
  });
  useEffect(() => () => {
    if (debounce.current !== null) window.clearTimeout(debounce.current);
  }, []);
  const pending = useAsync(() => api.pendingSandboxes(), [reconnects]);
  const now = useNow(5000);
  const [target, setTarget] = useState<RunningSandbox | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const stop = async () => {
    if (!target) return;
    setBusy(true);
    setErr(null);
    try {
      const o = await api.stopSandbox(target.runId);
      q.setData(() => o);
      setTarget(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        // Gone meanwhile (the run's build/review loop ended): nothing left to stop.
        q.reload();
        setTarget(null);
      } else setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const o = q.data;
  const used = o ? `${o.inUse} of ${o.limits.maxConcurrent} container${o.limits.maxConcurrent === 1 ? '' : 's'}` : '';
  return (
    <section className="card settings-card" aria-labelledby="sb-h">
      <header className="sc-head">
        <h2 id="sb-h">Sandboxes</h2>
        {o && <span className="muted small">{o.enabled ? `${used} in use` : 'switched off'}</span>}
      </header>
      {q.loading && !o && <Loading />}
      {q.error && !o && <ErrorBox error={q.error} retry={q.reload} />}
      {o && (
        <>
          {!o.enabled && (
            <Notice title="Sandboxes are switched off on this server">
              <code>FACTORY_SANDBOX=off</code>: runs build and test on the host. Definitions can still be approved.
            </Notice>
          )}
          {o.runtime === 'fake' && (
            <Notice tone="warn" title="Development runtime">
              <code>FACTORY_SANDBOX=fake</code>: sandbox commands only pretend to run.
            </Notice>
          )}
          <dl className="sbx-limits">
            <div>
              <dt>runtime</dt>
              <dd>{o.runtime === 'podman' ? 'rootless Podman' : o.runtime}</dd>
            </div>
            <div>
              <dt>in use</dt>
              <dd>
                <span className="sbx-meter" aria-hidden>
                  <span style={{ width: `${Math.min(100, (o.inUse / Math.max(1, o.limits.maxConcurrent)) * 100)}%` }} />
                </span>
                {used}
              </dd>
            </div>
            <div>
              <dt>per container</dt>
              <dd>
                up to {o.limits.maxCpus} CPUs · {o.limits.maxMemory}
              </dd>
            </div>
            <div>
              <dt>platforms</dt>
              <dd>up to {o.limits.maxPlatforms} per project</dd>
            </div>
            <div>
              <dt>command limit</dt>
              <dd>{o.limits.execTimeoutMinutes} min</dd>
            </div>
            <div>
              <dt>registries</dt>
              <dd className="mono small">{o.limits.registries.join(', ')}</dd>
            </div>
          </dl>
          <p className="muted small sbx-slots-note">
            Every container that runs commands takes one slot: a run’s default image and each platform it tests on. Services do not take a slot, but use memory.
          </p>
          <h3 className="sd-h">
            Running <span className="muted">{o.sandboxes.length}</span>
          </h3>
          {o.sandboxes.length === 0 ? (
            <p className="muted">No sandbox exists right now. A run’s sandbox is created by its builder’s first sandbox command and removed when its build and review are over.</p>
          ) : (
            <ul className="req-list">
              {o.sandboxes.map((s) => (
                <li key={s.runId} className="req sbx-run">
                  <div className="req-top">
                    <span className="sbx-run-title">
                      {s.visible ? (
                        <Link to={`/p/${s.repo}/runs/${s.runId}`}>
                          <span className="mono muted">#{s.issueNumber}</span> {s.issueTitle}
                        </Link>
                      ) : (
                        <span className="mono">run {s.runId.slice(0, 8)} · #{s.issueNumber}</span>
                      )}
                    </span>
                    <button type="button" className="btn btn--sm btn--ghost btn--danger" onClick={() => (setErr(null), setTarget(s))} aria-label={`Stop the sandbox of run #${s.issueNumber} in ${s.repo}`}>
                      Stop
                    </button>
                  </div>
                  <div className="sbx-run-meta muted small">
                    <span>{s.repo}</span>
                    <span>· {s.owner.username}</span>
                    <span>· {s.runStatus.replace('_', ' ')}</span>
                    <span>· up {between(s.createdAt, null, now)}</span>
                  </div>
                  <ul className="sbx-run-containers" aria-label="Containers">
                    {s.containers.map((c) => (
                      <li key={c.platform}>
                        <span className="sbx-plat">{c.platform}</span> <code title={c.image}>{shortImage(c.image)}</code>
                      </li>
                    ))}
                    {s.containers.length === 0 && <li className="muted small">no command container right now</li>}
                  </ul>
                  <div className="sbx-run-spec">
                    {s.services.length > 0 && <span className="muted small">services {s.services.join(', ')}</span>}
                    {s.platforms.length > s.containers.filter((c) => c.platform !== 'default').length && (
                      <span className="muted small">
                        platforms not started: {s.platforms.filter((p) => !s.containers.some((c) => c.platform === p)).join(', ')}
                      </span>
                    )}
                    <span className="muted small">
                      {s.slots} slot{s.slots === 1 ? '' : 's'} · {s.cpus} CPU{s.cpus === 1 ? '' : 's'} · {s.memory} per container · up to {s.memoryTotal} in all
                    </span>
                    <span className="muted small">definition {shortHash(s.sha256)}</span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      <h3 className="sd-h">
        Waiting for approval {pending.data && <span className="muted">{pending.data.length}</span>}
      </h3>
      {pending.loading && !pending.data && <Loading label="Reading the projects’ sandbox definitions" />}
      {pending.error && !pending.data && <ErrorBox error={pending.error} retry={pending.reload} />}
      {pending.data && pending.data.length === 0 && <p className="muted">Every valid sandbox definition you can see is approved.</p>}
      {pending.data && pending.data.length > 0 && (
        <ul className="sbx-pending">
          {pending.data.map((p) => (
            <li key={p.repo}>
              <Link to={`/p/${p.repo}/sandbox`} className="run-row sbx-row sbx-row--escalate">
                <div className="rr-top">
                  <span className="rr-title">{p.repo}</span>
                </div>
                <div className="rr-bot">
                  <span className="pill pill--escalate">
                    <span className="pill-dot" aria-hidden />
                    {p.approvedSha256 ? 'changed' : 'needs approval'}
                  </span>
                  <span className="rr-line">
                    {shortImage(p.image)} · {p.approvedSha256 ? `approved ${shortHash(p.approvedSha256)}, now ${shortHash(p.sha256)}` : `version ${shortHash(p.sha256)}`}
                  </span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <Dialog open={!!target} onClose={() => setTarget(null)} title={`Stop the sandbox of run #${target?.issueNumber ?? ''}?`}>
        <div className="form">
          <p>
            Its containers and network are removed now (cache volumes are kept), and a command running in it is killed. The run itself continues: the builder’s next sandbox command
            creates a fresh sandbox and runs the setup script again.
          </p>
          {target && (
            <p className="muted small">
              {target.repo} · {target.owner.username} · <code>{target.image}</code>
            </p>
          )}
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setTarget(null)} disabled={busy} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void stop()} disabled={busy}>
              {busy ? 'Stopping…' : 'Stop sandbox'}
            </button>
          </div>
        </div>
      </Dialog>
    </section>
  );
}

function UsersCard() {
  const me = useMe();
  const q = useAsync(() => api.users(), []);
  const [target, setTarget] = useState<User | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const reset = async () => {
    if (!target) return;
    setBusy(true);
    setErr(null);
    try {
      const u = await api.resetTwoFactor(target.id);
      q.setData((d) => d?.map((x) => (x.id === u.id ? u : x)));
      setTarget(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const yes = (b: boolean, label: string) => <span className={`flag ${b ? 'flag--on' : 'flag--off'}`}><span className="flag-dot" aria-hidden>{b ? '●' : '○'}</span> {label}</span>;
  return (
    <section className="card settings-card" aria-labelledby="u-h">
      <header className="sc-head"><h2 id="u-h">Users</h2><span className="muted small">{q.data?.length ?? ''} known</span></header>
      {q.loading && !q.data && <Loading />}
      {q.error && <ErrorBox error={q.error} retry={q.reload} />}
      {q.data && (
        <div className="table-wrap">
          <table className="table users-table">
            <thead>
              <tr><th scope="col">user</th><th scope="col">GitHub</th><th scope="col">Claude</th><th scope="col">2FA</th><th scope="col"><span className="sr-only">actions</span></th></tr>
            </thead>
            <tbody>
              {q.data.map((u) => (
                <tr key={u.id}>
                  <td data-label="user"><strong>{u.displayName}</strong> <span className="muted">{u.username}</span>{u.isAdmin && <span className="tag">admin</span>}</td>
                  <td data-label="GitHub">{yes(u.hasGithubToken, u.githubLogin ? `@${u.githubLogin}` : 'none')}</td>
                  <td data-label="Claude">{yes(u.hasClaudeCredential, u.claudeCredentialKind === 'api_key' ? 'API key' : u.claudeCredentialKind === 'oauth_token' ? 'OAuth' : 'none')}</td>
                  <td data-label="2FA">
                    {u.twoFactorLockedUntil ? (
                      <span className="flag flag--locked" title={`Locked after too many wrong codes until ${new Date(u.twoFactorLockedUntil).toLocaleString()}`}>
                        <span className="flag-dot" aria-hidden>●</span> locked until {new Date(u.twoFactorLockedUntil).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      </span>
                    ) : (
                      yes(u.twoFactorEnabled, u.twoFactorEnabled ? 'on' : 'enrols at next login')
                    )}
                  </td>
                  <td>
                    <button className="btn btn--sm btn--ghost btn--danger" disabled={!u.twoFactorEnabled && !u.twoFactorLockedUntil} onClick={() => setTarget(u)}>
                      Reset 2FA
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Dialog open={!!target} onClose={() => setTarget(null)} title={`Reset two-factor for ${target?.username ?? ''}?`}>
        <p>
          {target?.displayName} will have to set up an authenticator again at their next sign-in. Their recovery codes stop working and any lock is cleared.
          {target?.id === me.id && <strong> This is your own account.</strong>}
        </p>
        <p className="muted small">Only do this after confirming the person’s identity outside Agern. The reset is written to the audit log.</p>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button className="btn btn--ghost" onClick={() => setTarget(null)}>Cancel</button>
          <button className="btn btn--danger" onClick={() => void reset()} disabled={busy}>{busy ? 'Resetting…' : 'Reset 2FA'}</button>
        </div>
      </Dialog>
    </section>
  );
}

/** Every user's personal MCP tokens (docs/ARCHITECTURE.md §7 "Personal tokens"), with revoke. */
function McpTokensCard() {
  const q = useAsync(() => api.adminMcpTokens(), []);
  const [target, setTarget] = useState<McpToken | null>(null);
  return (
    <section className="card settings-card" aria-labelledby="mt-h">
      <header className="sc-head"><h2 id="mt-h">Personal MCP tokens</h2><span className="muted small">{q.data?.length ?? ''} active</span></header>
      <p className="muted small">Tokens users created to reach this Agern from Claude on their own computers. Each acts as its user in one project.</p>
      {q.loading && !q.data && <Loading />}
      {q.error && <ErrorBox error={q.error} retry={q.reload} />}
      {q.data && q.data.length === 0 && <p className="muted">No personal tokens.</p>}
      {q.data && q.data.length > 0 && <McpTokenTable tokens={q.data} showUser onRevoke={setTarget} />}
      <RevokeMcpTokenDialog
        target={target}
        admin
        onClose={() => setTarget(null)}
        revoke={async (t) => {
          const left = await api.adminRevokeMcpToken(t.id);
          q.setData(() => left);
        }}
      />
    </section>
  );
}
