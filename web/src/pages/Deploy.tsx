import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, type DeployCheck, type DeployStatus, type DeployTarget } from '../api';
import { checkSentence, DeployPill } from '../components/deploy/DeployBits';
import { shortHash } from '../components/sandbox/SandboxBits';
import { CopyButton, Dialog, Empty, ErrorBox, Loading, Notice } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { timeAgo } from '../lib/format';
import { useFeedReconnects } from '../lib/realtime';
import { DiffCard } from './Sandbox';

const EXAMPLE = `targets:
  kiosk:                                   # lower-case letters, digits and '-'
    host: kiosk.example.net                # DNS name or IP address
    port: 22                               # optional, default 22
    user: deploy                           # a dedicated, unprivileged user
    hostKey: ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI…   # ssh-keyscan -t ed25519 <host>, without the first field
    description: Kiosk in the lobby        # optional`;

type Dialogs = 'approve' | 'revoke' | 'rotate' | null;
type CheckState = { busy: boolean; result?: DeployCheck; error?: string };

const who = (o: { username: string; displayName: string }) => o.displayName || o.username;
const endpoint = (t: DeployTarget) => `${t.user}@${t.host}:${t.port}`;

/** A project's deploy targets, their approval, the project's key, and (for admins) approve / revoke / rotate / check (docs/DEPLOY.md). */
export function DeployPage() {
  const { repo = '' } = useParams();
  const me = useMe();
  const reconnects = useFeedReconnects();
  const q = useAsync(() => api.deployStatus(repo), [repo, reconnects]);
  const [dialog, setDialog] = useState<Dialogs>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [checks, setChecks] = useState<Record<string, CheckState>>({});

  useEffect(() => {
    document.title = `Deploy · ${repo} · Agern`;
  }, [repo]);

  if (q.error && !q.data)
    return (
      <div className="page page--narrow">
        {q.error instanceof ApiError && q.error.status === 404 ? (
          <Empty title={`No project called “${repo}”`}>
            It doesn’t exist in the organization, or your GitHub token can’t read it. <Link to="/">All projects</Link>
          </Empty>
        ) : (
          <ErrorBox error={q.error} retry={q.reload} />
        )}
      </div>
    );
  const st = q.data;
  if (!st) return <div className="page page--narrow"><Loading label="Loading the deploy targets" /></div>;

  const mutate = async (kind: 'approve' | 'revoke' | 'generate' | 'rotate') => {
    setBusy(true);
    setErr(null);
    setDone(null);
    try {
      const next =
        kind === 'approve'
          ? await api.approveDeploy(repo, st.sha256 ?? '')
          : kind === 'revoke'
            ? await api.revokeDeployApproval(repo)
            : kind === 'generate'
              ? await api.generateDeployKey(repo)
              : await api.rotateDeployKey(repo);
      q.setData(() => next);
      setChecks({});
      setDone(
        {
          approve: 'Approved. Chat agents in this project can now use these targets.',
          revoke: 'Approval revoked. No target can be used until an admin approves a version again.',
          generate: 'Key generated. Add the public key to authorized_keys on every target.',
          rotate: 'Key rotated. Replace the old public key in authorized_keys on every target.',
        }[kind],
      );
      setDialog(null);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'deploy_definition_changed') {
        setErr('The file changed on the default branch while you were reading it. Review the new version below and approve again.');
        q.reload();
        setDialog(null);
      } else setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const check = async (t: DeployTarget) => {
    setChecks((c) => ({ ...c, [t.name]: { busy: true } }));
    try {
      const result = await api.checkDeployTarget(repo, t.name);
      setChecks((c) => ({ ...c, [t.name]: { busy: false, result } }));
    } catch (e) {
      setChecks((c) => ({ ...c, [t.name]: { busy: false, error: (e as Error).message } }));
    }
  };

  const changed = st.state === 'pending' && !!st.approval;
  const canApprove = me.isAdmin && st.state === 'pending' && !!st.sha256;
  const canRevoke = me.isAdmin && !!st.approval;
  const canCheck = me.isAdmin && st.state === 'approved' && !!st.key;
  const approvers = st.approvers.map((a) => (a.displayName && a.displayName !== a.username ? `${a.displayName} (${a.username})` : a.username));

  return (
    <div className="page sbx-page dpl-page">
      <header className="run-head sbx-head">
        <div className="rh-crumbs">
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
          <span className="crumb">deploy</span>
        </div>
        <div className="rh-title">
          <h1>Deploy</h1>
          <DeployPill status={st} />
        </div>
        <p className="sbx-about">
          Chat agents of <strong>{repo}</strong> can install a new version on the machines listed in <code>{st.path}</code> on <code>{st.branch}</code>, once an admin
          approved exactly that version. Agern holds the project’s SSH key and makes the connection; the agent never sees the key, and every command is recorded in the chat.
        </p>
        <div className="rh-actions">
          {canApprove && (
            <button type="button" className="btn btn--approve" onClick={() => (setErr(null), setDialog('approve'))}>
              Approve…
            </button>
          )}
          {canRevoke && (
            <button type="button" className="btn btn--ghost btn--danger" onClick={() => (setErr(null), setDialog('revoke'))}>
              Revoke…
            </button>
          )}
        </div>
      </header>

      {err && <p className="form-error" role="alert">{err}</p>}
      {done && <p className="form-ok" role="status">{done}</p>}

      {!st.enabled && (
        <Notice title="Deploy targets are switched off on this server">
          <code>FACTORY_DEPLOY=off</code>: chat agents get no deploy tools. Files can still be approved and keys generated; they are used once an admin switches deploy targets on.
        </Notice>
      )}
      {st.enabled && st.mode === 'fake' && (
        <Notice tone="warn" title="Development mode">
          <code>FACTORY_DEPLOY=fake</code>: commands only pretend to run; no connection is made.
        </Notice>
      )}

      <KeyCard st={st} isAdmin={me.isAdmin} busy={busy} onGenerate={() => void mutate('generate')} onRotate={() => (setErr(null), setDialog('rotate'))} />

      <StateNotice st={st} />

      {!me.isAdmin && st.state !== 'none' && (
        <p className="muted small sbx-who">
          Only an admin can approve, revoke or check targets{approvers.length ? <>: {approvers.join(', ')}</> : ''}.
        </p>
      )}

      {changed && st.content !== null && <DiffCard before={st.approval!.content} after={st.content} approvedSha={st.approval!.sha256} sha={st.sha256} />}

      {st.targets.length > 0 && (
        <section className="card sbx-card" aria-labelledby="dpl-targets">
          <h2 id="dpl-targets" className="sbx-h">Targets</h2>
          <ul className="dpl-targets">
            {st.targets.map((t) => {
              const c = checks[t.name];
              return (
                <li key={t.name} className="dpl-target">
                  <div className="dpl-target-main">
                    <strong className="mono">{t.name}</strong> <code>{endpoint(t)}</code>
                    {t.description && <div className="muted small">{t.description}</div>}
                    <div className="muted small dpl-hostkey">
                      host key {t.hostKeyType} <code>{t.hostKeyFingerprint}</code>
                    </div>
                    {c && !c.busy && (c.result || c.error) && (
                      <p role="status" className={`dpl-check ${c.result?.ok ? 'dpl-check--ok' : 'dpl-check--fail'}`}>
                        {c.result ? checkSentence(c.result, t.user) : c.error}
                      </p>
                    )}
                  </div>
                  {canCheck && (
                    <button type="button" className="btn btn--ghost btn--sm" disabled={c?.busy} onClick={() => void check(t)} aria-label={c?.busy ? `Checking ${t.name}…` : `Check ${t.name}`}>
                      {c?.busy ? 'Checking…' : 'Check'}
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {st.content !== null ? (
        <details className="card sbx-card sbx-file" open={st.state === 'invalid'}>
          <summary>
            <span>The file</span> <code className="muted">{st.path}</code> <span className="muted small">sha256 {shortHash(st.sha256)}</span>
          </summary>
          <pre className="sbx-pre">{st.content}</pre>
        </details>
      ) : (
        <section className="card sbx-card" aria-labelledby="dpl-ex">
          <h2 id="dpl-ex" className="sbx-h">Add deploy targets</h2>
          <p className="muted">
            Commit <code>{st.path}</code> to <code>{st.branch}</code> with the machines to deploy to and their pinned host keys. An admin then approves it here. For example:
          </p>
          <pre className="sbx-pre">{EXAMPLE}</pre>
        </section>
      )}

      <Dialog open={dialog === 'approve'} onClose={() => setDialog(null)} title={`Approve the deploy targets of ${repo}?`} wide>
        <div className="form">
          <ul className="sbx-images">
            {st.targets.map((t) => (
              <li key={t.name}>
                <span className="muted small sbx-img-role">{t.name}</span> <code>{endpoint(t)}</code> <span className="muted small">host key</span> <code>{t.hostKeyFingerprint}</code>
              </li>
            ))}
          </ul>
          <p>
            <strong>
              Everyone who can open a chat in {repo} can then have its agent run any command on these machines as the listed user, until the file changes or the approval is revoked.
            </strong>{' '}
            Restrict what the key may do in authorized_keys on the target.
          </p>
          <p className="muted small">
            You approve exactly this version (sha256 <code>{shortHash(st.sha256)}</code>); any change to the file needs a new approval.
          </p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--approve" onClick={() => void mutate('approve')} disabled={busy}>
              {busy ? 'Approving…' : 'Approve this version'}
            </button>
          </div>
        </div>
      </Dialog>

      <Dialog open={dialog === 'revoke'} onClose={() => setDialog(null)} title={`Revoke the deploy approval of ${repo}?`}>
        <div className="form">
          <p>Chat agents of {repo} then cannot use any target until an admin approves a version again. Commands already running are not stopped.</p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void mutate('revoke')} disabled={busy}>
              {busy ? 'Revoking…' : 'Revoke approval'}
            </button>
          </div>
        </div>
      </Dialog>

      <Dialog open={dialog === 'rotate'} onClose={() => setDialog(null)} title={`Rotate the deploy key of ${repo}?`}>
        <div className="form">
          <p>A new key pair is generated and the old one is deleted at once. Deployments fail until the new public key is in authorized_keys on every target.</p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void mutate('rotate')} disabled={busy}>
              {busy ? 'Rotating…' : 'Rotate key'}
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

function KeyCard({ st, isAdmin, busy, onGenerate, onRotate }: { st: DeployStatus; isAdmin: boolean; busy: boolean; onGenerate: () => void; onRotate: () => void }) {
  const key = st.key;
  // The key's comment, `agern-deploy:<org>/<repo>`, for the lockdown example.
  const comment = key?.publicKey.split(' ')[2] ?? `agern-deploy:<org>/${st.repo}`;
  return (
    <section className="card sbx-card" aria-labelledby="dpl-key">
      <h2 id="dpl-key" className="sbx-h">Deploy key</h2>
      {!key ? (
        <>
          <p className="muted">No deploy key yet. Agern generates an Ed25519 key pair for the project; the private key never leaves the server.</p>
          {isAdmin ? (
            <button type="button" className="btn btn--approve" onClick={onGenerate} disabled={busy}>
              {busy ? 'Generating…' : 'Generate key'}
            </button>
          ) : (
            <p className="muted small">Only an admin can generate the key.</p>
          )}
        </>
      ) : (
        <>
          <label className="muted small" htmlFor="dpl-pub">Public key</label>
          <textarea id="dpl-pub" className="sbx-pre dpl-pub" readOnly rows={3} value={key.publicKey} spellCheck={false} />
          <div className="dpl-key-row">
            <CopyButton text={key.publicKey} label="Copy public key" />
            <span className="muted small">
              fingerprint <code className="dpl-fp">{key.fingerprint}</code> · created by {who(key.createdBy)} {timeAgo(key.createdAt)}
            </span>
          </div>
          <p className="muted small">
            Add this line to <code>~/.ssh/authorized_keys</code> of the target’s user — ideally a dedicated, unprivileged user.
          </p>
          <details className="dpl-lock">
            <summary>Lock the key down</summary>
            <p className="muted small">
              Options in front of the key limit what it can do: <code>restrict</code> turns off forwarding and PTYs, and <code>command="…"</code> runs one script whatever the agent asks for
              (the request is in <code>$SSH_ORIGINAL_COMMAND</code>; a forced command also stops copies). For example:
            </p>
            <pre className="sbx-pre sbx-pre--small">{`restrict,command="/usr/local/bin/deploy-app" ssh-ed25519 AAAA… ${comment}`}</pre>
          </details>
          {isAdmin && (
            <div className="dpl-key-actions">
              <button type="button" className="btn btn--ghost btn--danger btn--sm" onClick={onRotate} disabled={busy}>
                Rotate key…
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}

function StateNotice({ st }: { st: DeployStatus }) {
  const by = st.approval ? `${who(st.approval.approvedBy)}, ${timeAgo(st.approval.approvedAt)}` : '';
  switch (st.state) {
    case 'none':
      return (
        <Notice title="No deploy targets">
          This project has no <code>{st.path}</code> on <code>{st.branch}</code>. Chat agents cannot deploy anywhere.
        </Notice>
      );
    case 'invalid':
      return (
        <div className="notice notice--error sbx-invalid">
          <div className="notice-text">
            <strong>The file is invalid</strong>
            <div>No target can be used until it is fixed on {st.branch}.</div>
            <ul className="sbx-errors">
              {st.errors.map((e, i) => <li key={i}><code>{e}</code></li>)}
            </ul>
          </div>
        </div>
      );
    case 'pending':
      return st.approval ? (
        <Notice tone="warn" title="Changed since it was approved">
          {by && <>Version <code>{shortHash(st.approval.sha256)}</code> was approved by {by}. </>}The file on {st.branch} is now <code>{shortHash(st.sha256)}</code>: no target can be used
          until an admin approves the new version (changes below).
        </Notice>
      ) : (
        <Notice tone="warn" title="Waiting for an admin’s approval">
          Version <code>{shortHash(st.sha256)}</code> has not been approved. No target can be used until it is.
        </Notice>
      );
    case 'approved':
      return (
        <Notice title="Approved">
          Version <code>{shortHash(st.sha256)}</code>, approved by {by}. Chat agents can use these targets{st.key ? '' : ' once an admin generates the key'}.
        </Notice>
      );
  }
}
