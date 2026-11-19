import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, type SandboxDefinition, type SandboxStatus } from '../api';
import { foldUnchanged, lineDiff } from '../components/sandbox/diff';
import { SandboxPill, shortHash } from '../components/sandbox/SandboxBits';
import { Dialog, Empty, ErrorBox, Loading, Notice } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { timeAgo } from '../lib/format';
import { useFeedReconnects, useSandboxApprovalFeed, withApproval } from '../lib/realtime';

const EXAMPLE = `image: docker.io/library/eclipse-temurin:21-jdk
user: builder                      # optional: commands run as this user, setup as root
setup: |
  apt-get update && apt-get install -y nodejs npm
services:
  postgres:
    image: docker.io/library/postgres:17
    env: { POSTGRES_PASSWORD: dev }
    ready: { command: "pg_isready -h 127.0.0.1", timeoutSeconds: 60 }
caches: [ /home/builder/.gradle, /home/builder/.npm ]
resources: { cpus: 2, memory: 4g }
platforms:                         # optional: more images to test on
  jdk17:
    image: docker.io/library/eclipse-temurin:17-jdk`;

/** Every image an approval lets run: the default one, the platforms', the services'. */
function imagesOf(def: SandboxDefinition): { role: string; image: string }[] {
  return [
    { role: 'default', image: def.image },
    ...def.platforms.map((p) => ({ role: `platform ${p.name}`, image: p.image })),
    ...def.services.map((s) => ({ role: `service ${s.name}`, image: s.image })),
  ];
}

/** A project's sandbox definition, its approval and (for admins) approve / revoke (docs/ARCHITECTURE.md §10). */
export function SandboxPage() {
  const { repo = '' } = useParams();
  const me = useMe();
  const reconnects = useFeedReconnects();
  const q = useAsync(() => api.sandboxStatus(repo), [repo, reconnects]);
  useSandboxApprovalFeed(repo, (approval) => q.setData((st) => st && withApproval(st, approval)));
  const [dialog, setDialog] = useState<'approve' | 'revoke' | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    document.title = `Sandbox · ${repo} · Agern`;
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
  if (!st) return <div className="page page--narrow"><Loading label="Loading the sandbox definition" /></div>;

  const act = async (kind: 'approve' | 'revoke') => {
    setBusy(true);
    setErr(null);
    setDone(null);
    try {
      const next = kind === 'approve' ? await api.approveSandbox(repo, st.sha256 ?? '') : await api.revokeSandboxApproval(repo);
      q.setData(() => next);
      setDone(kind === 'approve' ? 'Approved. Runs started from now on use this sandbox.' : 'Approval revoked. New runs build and test on the host.');
      setDialog(null);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'sandbox_definition_changed') {
        setErr('The file changed on the default branch while you were reading it. Review the new version below and approve again.');
        q.reload();
        setDialog(null);
      } else setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const changed = st.state === 'pending' && !!st.approval;
  const canApprove = me.isAdmin && st.state === 'pending' && !!st.sha256;
  const canRevoke = me.isAdmin && !!st.approval;
  const setups = setupScripts(st.definition);
  const approvers = st.approvers.map((a) => a.displayName && a.displayName !== a.username ? `${a.displayName} (${a.username})` : a.username);

  return (
    <div className="page sbx-page">
      <header className="run-head sbx-head">
        <div className="rh-crumbs">
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
          <span className="crumb">sandbox</span>
        </div>
        <div className="rh-title">
          <h1>Sandbox</h1>
          <SandboxPill status={st} />
        </div>
        <p className="sbx-about">
          Builds and tests of <strong>{repo}</strong> run in a container described by <code>{st.path}</code> on <code>{st.branch}</code>. A run reads the file
          from the commit it starts from and uses it only if an admin approved exactly that version; otherwise it builds and tests on the host as before.
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
        <Notice title="Sandboxes are switched off on this server">
          <code>FACTORY_SANDBOX=off</code>: every run builds and tests on the host. Definitions can still be approved; they are used once an admin switches sandboxes on.
        </Notice>
      )}

      <StateNotice st={st} />

      {!me.isAdmin && st.state !== 'none' && (
        <p className="muted small sbx-who">
          Only an admin can approve or revoke{approvers.length ? <>: {approvers.join(', ')}</> : ''}.
        </p>
      )}

      {changed && st.content !== null && <DiffCard before={st.approval!.content} after={st.content} approvedSha={st.approval!.sha256} sha={st.sha256} />}

      {st.definition && <DefinitionCard def={st.definition} />}

      {st.content !== null ? (
        <details className="card sbx-card sbx-file" open={st.state === 'invalid'}>
          <summary>
            <span>The file</span> <code className="muted">{st.path}</code> <span className="muted small">sha256 {shortHash(st.sha256)}</span>
          </summary>
          <pre className="sbx-pre">{st.content}</pre>
        </details>
      ) : (
        <section className="card sbx-card" aria-labelledby="sbx-ex">
          <h2 id="sbx-ex" className="sbx-h">Add a sandbox</h2>
          <p className="muted">
            Commit <code>{st.path}</code> to <code>{st.branch}</code>. Images must be fully qualified and come from an allowed registry. An admin then approves it here. For example:
          </p>
          <pre className="sbx-pre">{EXAMPLE}</pre>
        </section>
      )}

      <Dialog open={dialog === 'approve'} onClose={() => setDialog(null)} title={`Approve the sandbox of ${repo}?`} wide>
        <div className="form">
          <p>
            <strong>
              The images below{setups.length ? ` and the setup script${setups.length === 1 ? '' : 's'}` : ''} will run on the factory host, in rootless containers, for every run of {repo}
            </strong>{' '}
            until the file changes or the approval is revoked.
          </p>
          <ul className="sbx-images">
            {st.definition &&
              imagesOf(st.definition).map((im, i) => (
                <li key={i}>
                  <span className="muted small sbx-img-role">{im.role}</span> <code>{im.image}</code>
                </li>
              ))}
          </ul>
          {setups.map((x) => (
            <div key={x.label}>
              <p className="muted small">Setup script {x.label} (runs as root when that container is created):</p>
              <pre className="sbx-pre sbx-pre--small">{x.script}</pre>
            </div>
          ))}
          <p className="muted small">
            You approve exactly this version (sha256 <code>{shortHash(st.sha256)}</code>); any change to the file needs a new approval. The sandbox gives the project its toolchain, services and
            limits — it does not contain the agent itself.
          </p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--approve" onClick={() => void act('approve')} disabled={busy}>
              {busy ? 'Approving…' : 'Approve this version'}
            </button>
          </div>
        </div>
      </Dialog>

      <Dialog open={dialog === 'revoke'} onClose={() => setDialog(null)} title={`Revoke the sandbox approval of ${repo}?`}>
        <div className="form">
          <p>New runs of {repo} then build and test on the host, without a sandbox, until an admin approves a version again. Runs already started keep their sandbox.</p>
          {err && <p className="form-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setDialog(null)} disabled={busy} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void act('revoke')} disabled={busy}>
              {busy ? 'Revoking…' : 'Revoke approval'}
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

/** The distinct setup scripts that will run: the top-level one (also for platforms that inherit it) and platforms' own. */
function setupScripts(def: SandboxDefinition | null): { label: string; script: string }[] {
  if (!def) return [];
  const out: { label: string; script: string }[] = [];
  const inheriting = def.platforms.filter((p) => p.setupInherited).map((p) => p.name);
  if (def.setup) out.push({ label: inheriting.length ? `of the default image and ${inheriting.join(', ')}` : 'of the default image', script: def.setup });
  for (const p of def.platforms) if (!p.setupInherited && p.setup) out.push({ label: `of platform ${p.name}`, script: p.setup });
  return out;
}

function StateNotice({ st }: { st: SandboxStatus }) {
  const by = st.approval ? `${st.approval.approvedBy.displayName || st.approval.approvedBy.username}, ${timeAgo(st.approval.approvedAt)}` : '';
  switch (st.state) {
    case 'none':
      return (
        <Notice title="No sandbox definition">
          This project has no <code>{st.path}</code> on <code>{st.branch}</code>. Runs build and test on the host.
        </Notice>
      );
    case 'invalid':
      return (
        <div className="notice notice--error sbx-invalid">
          <div className="notice-text">
            <strong>The definition is invalid</strong>
            <div>Runs build and test on the host until it is fixed on {st.branch}.</div>
            <ul className="sbx-errors">
              {st.errors.map((e, i) => <li key={i}><code>{e}</code></li>)}
            </ul>
          </div>
        </div>
      );
    case 'pending':
      return st.approval ? (
        <Notice tone="warn" title="Changed since it was approved">
          {by && <>Version <code>{shortHash(st.approval.sha256)}</code> was approved by {by}. </>}The file on {st.branch} is now <code>{shortHash(st.sha256)}</code>: runs build and test on the host until an admin approves the new version (changes below).
        </Notice>
      ) : (
        <Notice tone="warn" title="Waiting for an admin’s approval">
          Version <code>{shortHash(st.sha256)}</code> has not been approved. Runs build and test on the host until it is.
        </Notice>
      );
    case 'approved':
      return (
        <Notice title="Approved">
          Version <code>{shortHash(st.sha256)}</code>, approved by {by}. Runs started from {st.branch} with this version get the sandbox.
        </Notice>
      );
  }
}

function DiffCard({ before, after, approvedSha, sha }: { before: string; after: string; approvedSha: string; sha: string | null }) {
  const hunks = useMemo(() => foldUnchanged(lineDiff(before, after)), [before, after]);
  const added = hunks.reduce((n, h) => n + (h.kind === 'lines' ? h.lines.filter((l) => l.op === 'add').length : 0), 0);
  const removed = hunks.reduce((n, h) => n + (h.kind === 'lines' ? h.lines.filter((l) => l.op === 'del').length : 0), 0);
  return (
    <section className="card sbx-card" aria-labelledby="sbx-diff">
      <h2 id="sbx-diff" className="sbx-h">
        Changes since the approved version
        <span className="sbx-diff-n">
          <span className="sbx-add">+{added}</span> <span className="sbx-del">−{removed}</span>
        </span>
      </h2>
      <p className="muted small">
        <code>{shortHash(approvedSha)}</code> (approved) → <code>{shortHash(sha)}</code> (on the default branch)
      </p>
      <div className="sbx-diff" role="group" aria-label="Line diff">
        {hunks.map((h, i) =>
          h.kind === 'skip' ? (
            <div key={i} className="sbx-dl sbx-dl--skip">
              ⋯ {h.count} unchanged line{h.count === 1 ? '' : 's'}
            </div>
          ) : (
            h.lines.map((l, j) => (
              <div key={`${i}-${j}`} className={`sbx-dl sbx-dl--${l.op}`}>
                <span className="sbx-dl-sign" aria-hidden>{l.op === 'add' ? '+' : l.op === 'del' ? '−' : ' '}</span>
                {l.op !== 'same' && <span className="sr-only">{l.op === 'add' ? 'added: ' : 'removed: '}</span>}
                <span className="sbx-dl-text">{l.text || ' '}</span>
              </div>
            ))
          ),
        )}
      </div>
    </section>
  );
}

function EnvList({ env }: { env: Record<string, string> }) {
  const entries = Object.entries(env);
  if (!entries.length) return <span className="muted">none</span>;
  return (
    <ul className="sbx-env">
      {entries.map(([k, v]) => (
        <li key={k}>
          <code>
            {k}=<span className="sbx-env-v">{v}</span>
          </code>
        </li>
      ))}
    </ul>
  );
}

function DefinitionCard({ def }: { def: SandboxDefinition }) {
  const l = def.limits;
  return (
    <section className="card sbx-card" aria-labelledby="sbx-def">
      <h2 id="sbx-def" className="sbx-h">Definition</h2>
      <dl className="sbx-dl-grid">
        <dt>image</dt>
        <dd><code className="sbx-image">{def.image}</code></dd>
        <dt>user</dt>
        <dd>
          {def.user ? (
            <>
              commands run as <code>{def.user.name ?? `uid ${def.user.uid}`}</code>
              {def.user.name && <span className="muted"> (uid {def.user.uid})</span>}; setup runs as root.{' '}
              <span className="muted small">Files the commands write in the worktree belong to Agern’s service user on the host.</span>
            </>
          ) : (
            <span className="muted">root inside the container (Agern’s service user on the host)</span>
          )}
        </dd>
        <dt>setup</dt>
        <dd>{def.setup ? <pre className="sbx-pre sbx-pre--small">{def.setup}</pre> : <span className="muted">none</span>}</dd>
        <dt>platforms</dt>
        <dd>
          {def.platforms.length === 0 ? (
            <span className="muted">none — commands run in the image above only</span>
          ) : (
            <ul className="sbx-services sbx-platforms">
              {def.platforms.map((p) => (
                <li key={p.name}>
                  <strong className="mono">{p.name}</strong> <code>{p.image}</code>
                  <div className="muted small">
                    {p.setupInherited ? (def.setup ? 'setup: the top-level script' : 'setup: none (the top level has none)') : p.setup ? 'setup: its own script' : 'setup: none'}
                  </div>
                  {!p.setupInherited && p.setup && <pre className="sbx-pre sbx-pre--small">{p.setup}</pre>}
                </li>
              ))}
            </ul>
          )}
        </dd>
        <dt>environment</dt>
        <dd><EnvList env={def.env} /></dd>
        <dt>services</dt>
        <dd>
          {def.services.length === 0 ? (
            <span className="muted">none</span>
          ) : (
            <ul className="sbx-services">
              {def.services.map((s) => (
                <li key={s.name}>
                  <strong className="mono">{s.name}</strong> <code>{s.image}</code>
                  {Object.keys(s.env).length > 0 && <EnvList env={s.env} />}
                  <div className="muted small sbx-ready">
                    {s.ready ? (
                      <>
                        ready when <code>{s.ready.command}</code> succeeds (waits up to {s.ready.timeoutSeconds} s)
                      </>
                    ) : (
                      'no readiness check — commands may start before it accepts connections'
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </dd>
        <dt>caches</dt>
        <dd>{def.caches.length ? def.caches.map((c) => <code key={c} className="sbx-chip">{c}</code>) : <span className="muted">none</span>}</dd>
        <dt>resources</dt>
        <dd>
          {l.cpus} CPU{l.cpus === 1 ? '' : 's'} · {l.memory} memory <span className="muted">per container</span>
          {def.platforms.length > 0 && (
            <span className="muted small">
              {' '}
              — each platform in use is another container with these limits
            </span>
          )}
          {l.capped && (
            <span className="sbx-capped">
              {' '}
              (asks for {l.requestedCpus ?? l.cpus} CPUs · {l.requestedMemory ?? l.memory}; capped by the server)
            </span>
          )}
        </dd>
      </dl>
    </section>
  );
}
