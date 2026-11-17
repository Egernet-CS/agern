import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { api, type Settings, type SoftwareRequest, type StageRole, type User } from '../api';
import { Dialog, ErrorBox, Loading } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { timeAgo } from '../lib/format';

const MODELS = ['opus', 'sonnet', 'haiku'];
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
      <UsersCard />
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
      <div className="form-grid">
        <label htmlFor="m-chat">chat</label>
        <ModelSelect id="m-chat" value={draft.chatModel} onChange={(v) => setDraft({ ...draft, chatModel: v })} />
        {ROLES.map((r) => (
          <FragmentRow key={r} role={r} value={draft.pipelineModels[r]} onChange={(v) => setDraft({ ...draft, pipelineModels: { ...draft.pipelineModels, [r]: v } })} />
        ))}
        <label htmlFor="m-rounds">max fix rounds</label>
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
