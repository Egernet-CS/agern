import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, type SandboxVm, type SandboxVmCheck, type SandboxVmLease, type SandboxVmStatus } from '../api';
import { shortHash } from '../components/sandbox/SandboxBits';
import { SandboxVmPill, sandboxVmCheckSentence } from '../components/sandboxvm/SandboxVmBits';
import { CopyButton, Dialog, Empty, ErrorBox, Loading, Notice } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { dateTime, timeAgo } from '../lib/format';
import { useFeedReconnects } from '../lib/realtime';
import { DiffCard } from './Sandbox';

const EXAMPLE = `name: sandbox                       # optional label (default sandbox-vm)
host: sandbox.example.net           # DNS name or IP address
port: 22                            # optional, default 22
user: agern                         # a dedicated, unprivileged user
hostKey: ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI…   # ssh-keyscan -t ed25519 <host>, without the first field
description: Staging VM             # optional
url: https://sandbox.example.net/   # optional: what a human opens after a deploy
checkout: |                         # unpacks the run's commit ($AGERN_ARCHIVE, a .tar.gz)
  rm -rf /srv/app/src && mkdir -p /srv/app/src && tar xzf "$AGERN_ARCHIVE" -C /srv/app/src
setup: sudo systemctl restart app   # optional
ready:                              # optional: repeated until it exits 0
  command: curl -fsS http://127.0.0.1:8080/health
  timeoutSeconds: 60
reset: /srv/app/bin/restore-test-db # optional: runs when a run or a human releases the VM`;

type Dialogs = 'approve' | 'revoke' | 'release' | null;
type CheckState = { busy: boolean; result?: SandboxVmCheck; error?: string };

const who = (o: { username: string; displayName: string }) => o.displayName || o.username;
const endpoint = (vm: SandboxVm) => `${vm.user}@${vm.host}:${vm.port}`;

/** The commands of a VM in the order Agern runs them, with what each is for. */
function commandsOf(vm: SandboxVm): { name: string; when: string; command: string }[] {
  return [
    { name: 'checkout', when: 'after the upload of the run’s commit', command: vm.checkout },
    ...(vm.setup ? [{ name: 'setup', when: 'after checkout', command: vm.setup }] : []),
    ...(vm.ready ? [{ name: 'ready', when: `repeated until it exits 0, for up to ${vm.ready.timeoutSeconds} s`, command: vm.ready.command }] : []),
    ...(vm.reset ? [{ name: 'reset', when: 'when a run or a human releases the VM', command: vm.reset }] : []),
  ];
}

/** A project's sandbox VM (docs/SANDBOX-VMS.md): the definition, its approval, the shared deploy key, the lease and (for admins) approve / revoke / check / release. */
export function SandboxVmPage() {
  const { repo = '' } = useParams();
  const me = useMe();
  const reconnects = useFeedReconnects();
  const q = useAsync(() => api.getSandboxVm(repo), [repo, reconnects]);
  const [dialog, setDialog] = useState<Dialogs>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [check, setCheck] = useState<CheckState | null>(null);

  useEffect(() => {
    document.title = `Sandbox VM · ${repo} · Agern`;
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
  if (!st) return <div className="page page--narrow"><Loading label="Loading the sandbox VM" /></div>;

  const mutate = async (kind: 'approve' | 'revoke' | 'generate' | 'release') => {
    setBusy(true);
    setErr(null);
    setDone(null);
    try {
      let next: SandboxVmStatus;
      if (kind === 'generate') {
        await api.generateDeployKey(repo);
        next = await api.getSandboxVm(repo);
      } else {
        next =
          kind === 'approve'
            ? await api.approveSandboxVm(repo, st.sha256 ?? '')
            : kind === 'revoke'
              ? await api.revokeSandboxVm(repo)
              : await api.releaseSandboxVmLease(repo);
      }
      q.setData(() => next);
      setCheck(null);
      setDone(
        {
          approve: 'Approved. Builders of new runs can now deploy to this VM, and run owners can try their changes on it.',
          revoke: 'Approval revoked. No run can use the VM until an admin approves a version again.',
          generate: 'Key generated. Add the public key to authorized_keys of the VM’s user.',
          release: 'The VM was released. Its reset runs now.',
        }[kind],
      );
      setDialog(null);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'sandbox_vm_definition_changed') {
        setErr('The file changed on the default branch while you were reading it. Review the new version below and approve again.');
        q.reload();
        setDialog(null);
      } else setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const runCheck = async () => {
    setCheck({ busy: true });
    try {
      setCheck({ busy: false, result: await api.checkSandboxVm(repo) });
    } catch (e) {
      setCheck({ busy: false, error: (e as Error).message });
    }
  };

  const vm = st.vm;
  const changed = st.state === 'pending' && !!st.approval;
  const canApprove = me.isAdmin && st.state === 'pending' && !!st.sha256;
  const canRevoke = me.isAdmin && !!st.approval;
  const canCheck = me.isAdmin && st.enabled && st.state === 'approved' && !!st.key;
  const approvers = st.approvers.map((a) => (a.displayName && a.displayName !== a.username ? `${a.displayName} (${a.username})` : a.username));

  return (
    <div className="page sbx-page svm-page">
      <header className="run-head sbx-head">
        <div className="rh-crumbs">
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
          <span className="crumb">sandbox VM</span>
        </div>
        <div className="rh-title">
          <h1>Sandbox VM</h1>
          <SandboxVmPill status={st} />
        </div>
        <p className="sbx-about">
          Runs of <strong>{repo}</strong> can deploy their commit to the machine described in <code>{st.path}</code> on <code>{st.branch}</code>, once an admin approved
          exactly that version: the builder checks its work there, and a run’s owner can try it before merging. One run holds the VM at a time. Agern connects with the
          project’s deploy key; the agent never sees the key, and every step is recorded in the run’s transcript.
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
        <Notice title="Sandbox VMs are switched off on this server">
          <code>FACTORY_SANDBOX_VM=off</code>: builders get no sandbox VM tools and nobody can start a preview. Files can still be approved; they are used once an admin
          switches sandbox VMs on.
        </Notice>
      )}
      {st.enabled && st.mode === 'fake' && (
        <Notice tone="warn" title="Development mode">
          <code>FACTORY_SANDBOX_VM=fake</code>: commands only pretend to run; no connection is made.
        </Notice>
      )}

      <StateNotice st={st} />

      {!me.isAdmin && st.state !== 'none' && (
        <p className="muted small sbx-who">
          Only an admin can approve, revoke, check or release the VM{approvers.length ? <>: {approvers.join(', ')}</> : ''}.
        </p>
      )}

      {changed && st.content !== null && <DiffCard before={st.approval!.content} after={st.content} approvedSha={st.approval!.sha256} sha={st.sha256} />}

      {vm && (
        <section className="card sbx-card" aria-labelledby="svm-vm">
          <h2 id="svm-vm" className="sbx-h">The VM</h2>
          <dl className="sbx-dl-grid svm-vm">
            <dt>name</dt>
            <dd><strong className="mono">{vm.name}</strong>{vm.description && <span className="muted"> — {vm.description}</span>}</dd>
            <dt>connects to</dt>
            <dd><code>{endpoint(vm)}</code></dd>
            <dt>host key</dt>
            <dd>
              {vm.hostKeyType} <code className="dpl-fp">{vm.hostKeyFingerprint}</code>
            </dd>
            <dt>url</dt>
            <dd>
              {vm.url ? (
                <a href={vm.url} target="_blank" rel="noreferrer">
                  {vm.url} ↗
                </a>
              ) : (
                <span className="muted">none — previews show no link</span>
              )}
            </dd>
            {commandsOf(vm).map((c) => (
              <div key={c.name} className="svm-cmd">
                <dt>{c.name}</dt>
                <dd>
                  <span className="muted small">{c.when}</span>
                  <pre className="sbx-pre sbx-pre--small">{c.command}</pre>
                </dd>
              </div>
            ))}
          </dl>
          {canCheck && (
            <div className="svm-check">
              <button type="button" className="btn btn--ghost btn--sm" disabled={check?.busy} onClick={() => void runCheck()}>
                {check?.busy ? 'Checking…' : 'Check'}
              </button>
              <span className="muted small">Connects, verifies the host key and the key{vm.ready ? ', then runs the ready command once' : ''}.</span>
            </div>
          )}
          {check && !check.busy && (check.result || check.error) && (
            <div role="status" className={`dpl-check svm-check-result ${check.result?.ok ? 'dpl-check--ok' : 'dpl-check--fail'}`}>
              <p>{check.result ? sandboxVmCheckSentence(check.result, vm.user) : check.error}</p>
              {check.result?.output && (
                <details className="svm-pv-output">
                  <summary>Output</summary>
                  <pre className="sbx-pre sbx-pre--small">{check.result.output}</pre>
                </details>
              )}
            </div>
          )}
        </section>
      )}

      {st.state !== 'none' && <LeaseCard st={st} isAdmin={me.isAdmin} busy={busy} onRelease={() => (setErr(null), setDialog('release'))} />}

      <KeyCard st={st} isAdmin={me.isAdmin} busy={busy} onGenerate={() => void mutate('generate')} />

      {st.content !== null ? (
        <details className="card sbx-card sbx-file" open={st.state === 'invalid'}>
          <summary>
            <span>The file</span> <code className="muted">{st.path}</code> <span className="muted small">sha256 {shortHash(st.sha256)}</span>
          </summary>
          <pre className="sbx-pre">{st.content}</pre>
        </details>
      ) : (
        <section className="card sbx-card" aria-labelledby="svm-ex">
          <h2 id="svm-ex" className="sbx-h">Add a sandbox VM</h2>
          <p className="muted">
            Commit <code>{st.path}</code> to <code>{st.branch}</code> with the machine, its pinned host key and the commands that install a commit on it. An admin then
            approves it here. For example:
          </p>
          <pre className="sbx-pre">{EXAMPLE}</pre>
        </section>
      )}

      <details className="card sbx-card sbx-file svm-setup">
        <summary>
          <span>Recommended VM setup</span>
        </summary>
        <ul className="svm-setup-list">
          <li>A dedicated, unprivileged user for Agern, whose login shell is POSIX-compatible (sh, bash, dash).</li>
          <li>
            In its <code>authorized_keys</code>: <code>restrict</code> and <code>from="&lt;the Agern host&gt;"</code> in front of the key. A forced <code>command=</code> is
            not supported: it breaks the upload and the builder’s commands.
          </li>
          <li>Narrow sudoers rules for exactly what <code>setup</code> and <code>reset</code> need (e.g. restarting one service).</li>
          <li>Mocks or scrubbed data only, never production credentials; a firewall so the VM cannot reach production.</li>
          <li>
            <code>checkout</code> and <code>setup</code> that can run again on a used VM (idempotent) and do not depend on <code>reset</code> having run.
          </li>
          <li>Everyone who can see {repo} can read the output of every command run on the VM.</li>
        </ul>
      </details>

      <Dialog open={dialog === 'approve'} onClose={() => setDialog(null)} title={`Approve the sandbox VM of ${repo}?`} wide>
        <div className="form">
          {vm && (
            <>
              <ul className="sbx-images">
                <li>
                  <span className="muted small sbx-img-role">{vm.name}</span> <code>{endpoint(vm)}</code> <span className="muted small">host key</span>{' '}
                  <code>{vm.hostKeyFingerprint}</code>
                </li>
              </ul>
              <ul className="svm-dialog-cmds">
                {commandsOf(vm).map((c) => (
                  <li key={c.name}>
                    <span className="muted small">{c.name}</span>
                    <pre className="sbx-pre sbx-pre--small">{c.command}</pre>
                  </li>
                ))}
              </ul>
            </>
          )}
          <p>
            <strong>
              The builder of every new run of {repo} can then deploy its commit to this machine and run any command there as {vm?.user ?? 'the listed user'}, and the owner
              of a run that needs a human can deploy it for a preview, until the file changes or the approval is revoked.
            </strong>{' '}
            Restrict what the key may do on the VM.
          </p>
          <p className="muted small">
            You approve exactly this version (sha256 <code>{shortHash(st.sha256)}</code>); any change to the file needs a new approval.
          </p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} data-autofocus>
              Cancel
            </button>
            <button type="button" className="btn btn--approve" onClick={() => void mutate('approve')} disabled={busy}>
              {busy ? 'Approving…' : 'Approve this version'}
            </button>
          </div>
        </div>
      </Dialog>

      <Dialog open={dialog === 'revoke'} onClose={() => setDialog(null)} title={`Revoke the sandbox VM approval of ${repo}?`}>
        <div className="form">
          <p>No run can use the VM until an admin approves a version again. A run or a preview that holds the VM loses it at once, without running reset.</p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} data-autofocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void mutate('revoke')} disabled={busy}>
              {busy ? 'Revoking…' : 'Revoke approval'}
            </button>
          </div>
        </div>
      </Dialog>

      <Dialog open={dialog === 'release'} onClose={() => setDialog(null)} title={`Release the sandbox VM of ${repo}?`}>
        <div className="form">
          <p>
            The {st.lease?.kind === 'preview' ? 'preview' : 'run'} that holds the VM loses it at once; a command that is running is stopped.{' '}
            {vm?.reset ? 'The reset command runs, then the VM is free.' : 'The VM is free at once.'}
          </p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} data-autofocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void mutate('release')} disabled={busy}>
              {busy ? 'Releasing…' : 'Release the VM'}
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

function leaseHolder(lease: SandboxVmLease) {
  if (!lease.visible || !lease.runId || !lease.repo) return <>a run of another project</>;
  return (
    <Link to={`/p/${lease.repo}/runs/${lease.runId}`}>
      run {lease.issueNumber ? `#${lease.issueNumber}` : lease.runId.slice(0, 8)} of {lease.repo}
    </Link>
  );
}

function LeaseCard({ st, isAdmin, busy, onRelease }: { st: SandboxVmStatus; isAdmin: boolean; busy: boolean; onRelease: () => void }) {
  const lease = st.lease;
  return (
    <section className="card sbx-card" aria-labelledby="svm-lease">
      <h2 id="svm-lease" className="sbx-h">Lease</h2>
      {!lease ? (
        <p className="muted svm-lease-text">Free. A run’s builder or a human preview takes it; previews hold it for up to {st.previewMinutes} minutes.</p>
      ) : (
        <div className="svm-lease">
          <p className="svm-lease-text">
            {lease.state === 'resetting' ? (
              <>Being reset after {leaseHolder(lease)} released it.</>
            ) : (
              <>
                In use by {leaseHolder(lease)} {lease.kind === 'preview' ? '(a preview)' : '(its builder)'} since{' '}
                <time dateTime={lease.since} title={dateTime(lease.since)}>{timeAgo(lease.since)}</time>
                {lease.expiresAt && (
                  <>
                    , until <time dateTime={lease.expiresAt}>{dateTime(lease.expiresAt)}</time>
                  </>
                )}
                .
              </>
            )}
          </p>
          {isAdmin && lease.state === 'held' && (
            <button type="button" className="btn btn--ghost btn--danger btn--sm" onClick={onRelease} disabled={busy}>
              Release…
            </button>
          )}
        </div>
      )}
    </section>
  );
}

function KeyCard({ st, isAdmin, busy, onGenerate }: { st: SandboxVmStatus; isAdmin: boolean; busy: boolean; onGenerate: () => void }) {
  const key = st.key;
  return (
    <section className="card sbx-card" aria-labelledby="svm-key">
      <h2 id="svm-key" className="sbx-h">Key</h2>
      {!key ? (
        <>
          <p className="muted">
            No key yet. The VM uses the project’s deploy key: Agern generates an Ed25519 key pair for the project; the private key never leaves the server.
          </p>
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
          <label className="muted small" htmlFor="svm-pub">Public key</label>
          <textarea id="svm-pub" className="sbx-pre dpl-pub" readOnly rows={3} value={key.publicKey} spellCheck={false} />
          <div className="dpl-key-row">
            <CopyButton text={key.publicKey} label="Copy public key" />
            <span className="muted small">
              fingerprint <code className="dpl-fp svm-fp">{key.fingerprint}</code> · created by {who(key.createdBy)} {timeAgo(key.createdAt)}
            </span>
          </div>
          <p className="muted small">
            Add this line to <code>~/.ssh/authorized_keys</code> of {st.vm ? <code>{st.vm.user}</code> : 'the VM’s user'} on the VM. It is the same key as the project’s
            deploy targets use; rotate it on the <Link to={`/p/${st.repo}/deploy`}>Deploy page</Link>.
          </p>
        </>
      )}
    </section>
  );
}

function StateNotice({ st }: { st: SandboxVmStatus }) {
  const by = st.approval ? `${who(st.approval.approvedBy)}, ${timeAgo(st.approval.approvedAt)}` : '';
  switch (st.state) {
    case 'none':
      return (
        <Notice title="No sandbox VM">
          This project has no <code>{st.path}</code> on <code>{st.branch}</code>. Runs build and test in the container sandbox or on the host only.
        </Notice>
      );
    case 'invalid':
      return (
        <div className="notice notice--error sbx-invalid">
          <div className="notice-text">
            <strong>The file is invalid</strong>
            <div>No run can use the VM until it is fixed on {st.branch}.</div>
            <ul className="sbx-errors">
              {st.errors.map((e, i) => <li key={i}><code>{e}</code></li>)}
            </ul>
          </div>
        </div>
      );
    case 'pending':
      return st.approval ? (
        <Notice tone="warn" title="Changed since it was approved">
          {by && <>Version <code>{shortHash(st.approval.sha256)}</code> was approved by {by}. </>}The file on {st.branch} is now <code>{shortHash(st.sha256)}</code>: no run can
          use the VM until an admin approves the new version (changes below).
        </Notice>
      ) : (
        <Notice tone="warn" title="Waiting for an admin’s approval">
          Version <code>{shortHash(st.sha256)}</code> has not been approved. No run can use the VM until it is.
        </Notice>
      );
    case 'approved':
      return (
        <Notice title="Approved">
          Version <code>{shortHash(st.sha256)}</code>, approved by {by}. New runs can use the VM{st.key ? '' : ' once an admin generates the key'}.
        </Notice>
      );
  }
}
