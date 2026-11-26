import { useState } from 'react';
import { api, type Run } from '../../api';
import { dateTime, timeAgo } from '../../lib/format';

/** Whether the run page shows the preview panel: an approved VM while the run needs a human, or any earlier preview. */
export function showsSandboxVmPreview(run: Run): boolean {
  const vm = run.sandboxVm;
  if (!vm) return false;
  return (vm.state === 'approved' && run.status === 'needs_human') || vm.preview !== null;
}

/**
 * "Try it on the sandbox VM" on the run page (docs/SANDBOX-VMS.md "Human preview"): the owner (or an admin) of a run that
 * needs a human deploys its latest commit to the project's VM and opens it. Everyone else sees the state and the link.
 */
export function SandboxVmPreviewPanel({ run, canAct, onRun }: { run: Run; canAct: boolean; onRun: (r: Run) => void }) {
  const [busy, setBusy] = useState<'start' | 'release' | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const vm = run.sandboxVm;
  if (!vm || !showsSandboxVmPreview(run)) return null;
  const p = vm.preview;
  const name = vm.name ?? 'the sandbox VM';
  const sha7 = p?.sha ? p.sha.slice(0, 7) : null;
  // Starting is possible only while the run needs a human and its VM is approved; the server re-checks the rest.
  const canStart = canAct && vm.state === 'approved' && run.status === 'needs_human';

  const act = async (kind: 'start' | 'release') => {
    setBusy(kind);
    setErr(null);
    try {
      onRun(kind === 'start' ? await api.startSandboxVmPreview(run.id) : await api.releaseSandboxVmPreview(run.id));
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const tryButton = (label: string) =>
    canStart && (
      <button type="button" className="btn btn--primary" onClick={() => void act('start')} disabled={busy !== null}>
        {busy === 'start' ? 'Starting…' : label}
      </button>
    );

  const idle = (
    <>
      <p className="svm-pv-text">
        Deploys this run’s latest commit to <strong>{name}</strong>
        {vm.host && <> ({vm.host})</>}. The VM is shared: other runs cannot use it until you release it or {vm.previewMinutes} minutes have passed.
      </p>
      {canStart && <div className="svm-pv-actions">{tryButton('Try it on the sandbox VM')}</div>}
    </>
  );

  let body;
  switch (p?.state ?? 'idle') {
    case 'deploying':
      body = (
        <>
          <p className="svm-pv-text svm-pv-busy" role="status">
            <span className="svm-pv-spin" aria-hidden />
            <span>
              Deploying {sha7 ? <code>{sha7}</code> : 'the latest commit'} to {name}…
            </span>
          </p>
          {canAct && (
            <div className="svm-pv-actions">
              <button type="button" className="btn btn--primary" disabled>Deploy again</button>
              <button type="button" className="btn btn--ghost" disabled>Release the VM</button>
            </div>
          )}
        </>
      );
      break;
    case 'ready':
      body = (
        <>
          <p className="svm-pv-text" role="status">
            Deployed {sha7 ? <code>{sha7}</code> : 'the latest commit'} to {name}.
          </p>
          <p className="svm-pv-text">
            {p!.url && (
              <>
                <a href={p!.url} target="_blank" rel="noreferrer" className="svm-pv-open">
                  Open {p!.url} ↗
                </a>{' '}
              </>
            )}
            {p!.expiresAt && (
              <span className="muted small">
                Held until <time dateTime={p!.expiresAt}>{dateTime(p!.expiresAt)}</time>
              </span>
            )}
          </p>
          {canAct && (
            <div className="svm-pv-actions">
              {canStart && (
                <button type="button" className="btn btn--primary" onClick={() => void act('start')} disabled={busy !== null}>
                  {busy === 'start' ? 'Deploying…' : 'Deploy again'}
                </button>
              )}
              <button type="button" className="btn btn--ghost" onClick={() => void act('release')} disabled={busy !== null}>
                {busy === 'release' ? 'Releasing…' : 'Release the VM'}
              </button>
            </div>
          )}
        </>
      );
      break;
    case 'failed':
      body = (
        <>
          <p className="svm-pv-text svm-pv-fail" role="alert">
            Deploying to {name} failed at {p!.failedStep ?? 'a step'}: {p!.error ?? 'unknown error'}
          </p>
          {p!.output && (
            <details className="svm-pv-output">
              <summary>Output</summary>
              <pre className="sbx-pre sbx-pre--small">{p!.output}</pre>
            </details>
          )}
          {canStart && <div className="svm-pv-actions">{tryButton('Try again')}</div>}
        </>
      );
      break;
    case 'released':
      body = (
        <>
          {idle}
          <p className="muted small svm-pv-ended">
            {p!.expired ? `The preview ended after ${vm.previewMinutes} minutes.` : `Released ${timeAgo(p!.endedAt)}.`}
          </p>
        </>
      );
      break;
    default:
      body = idle;
  }

  return (
    <section className={`svm-pv svm-pv--${p?.state ?? 'idle'}`} aria-labelledby="svm-pv-h">
      <h2 id="svm-pv-h" className="svm-pv-h">
        Sandbox VM
      </h2>
      {body}
      {err && (
        <p className="form-error svm-pv-err" role="alert">
          {err}
        </p>
      )}
    </section>
  );
}
