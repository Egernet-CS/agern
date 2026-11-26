import { useId, useMemo, useState } from 'react';
import type { DeployLog } from '../../api/types';
import { SANDBOX_TAIL_LINES } from '../sandbox/SandboxLog';

function seconds(ms: number) {
  return ms < 10_000 ? `${(ms / 1000).toFixed(1)} s` : ms < 120_000 ? `${Math.round(ms / 1000)} s` : `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
}

function size(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** The outcome of a deploy operation in a few words, and whether it failed. */
export function deployOutcome(log: DeployLog): { failed: boolean; text: string } {
  if (log.error) return { failed: true, text: log.timedOut ? 'timed out' : 'error' };
  if (log.timedOut) return { failed: true, text: 'timed out' };
  if (log.kind === 'copy') return { failed: false, text: log.bytes != null ? size(log.bytes) : 'copied' };
  if (log.exitCode == null) return { failed: true, text: 'no exit status' };
  return { failed: log.exitCode !== 0, text: `exit ${log.exitCode}` };
}

/**
 * A deploy command or copy in a chat transcript (docs/DEPLOY.md): one line with the target, the command (or
 * `path → dest`), the outcome and the duration; opened, the error and the output in the sandbox log's terminal style.
 * Nothing of the output is rendered while collapsed.
 */
export function DeployLogBlock({ log }: { log: DeployLog }) {
  const [open, setOpen] = useState(false);
  const [all, setAll] = useState(false);
  const id = useId();
  const { failed, text } = deployOutcome(log);
  const command = log.command ?? '';
  const firstLine = command.split('\n')[0];
  const multiLine = command.trim().includes('\n');
  const what = log.kind === 'copy' ? `${log.path ?? '?'} → ${log.dest ?? '?'}` : firstLine + (multiLine ? ' …' : '');
  const label = log.kind === 'copy' ? `Deploy copy to ${log.target}: ${log.path ?? ''}` : `Deploy command on ${log.target}: ${firstLine}`;
  return (
    <div className={`t-sbx t-deploy ${failed ? 't-sbx--fail' : 't-sbx--ok'}`}>
      <button type="button" className="t-sbx-head" aria-expanded={open} aria-controls={id} aria-label={label} onClick={() => setOpen((o) => !o)}>
        <span className="t-sbx-caret" aria-hidden>
          {open ? '▾' : '▸'}
        </span>
        <span className="t-sbx-cmd">
          <span className="sbx-plat t-sbx-plat" title={`${log.user}@${log.host}:${log.port}`}>
            {log.target}
          </span>
          {log.kind === 'copy' ? <span className="t-sbx-setup">copy</span> : <span className="t-sbx-prompt" aria-hidden>$ </span>} {what}
        </span>
        <span className="t-sbx-meta">
          <span className={`t-sbx-exit ${failed ? 't-sbx-exit--fail' : ''}`}>{failed ? `✗ ${text}` : `✓ ${text}`}</span>
          <span>{seconds(log.durationMs)}</span>
          {log.outputTruncated && <span className="t-sbx-trunc">truncated</span>}
        </span>
      </button>
      {open && <DeployLogBody id={id} log={log} all={all} onAll={() => setAll(true)} showCommand={multiLine} />}
    </div>
  );
}

function DeployLogBody({ id, log, all, onAll, showCommand }: { id: string; log: DeployLog; all: boolean; onAll: () => void; showCommand: boolean }) {
  const { shown, total, hidden } = useMemo(() => {
    const text = log.output.replace(/\n$/, '');
    if (!text) return { shown: '', total: 0, hidden: 0 };
    const lines = text.split('\n');
    if (all || lines.length <= SANDBOX_TAIL_LINES) return { shown: text, total: lines.length, hidden: 0 };
    return { shown: lines.slice(-SANDBOX_TAIL_LINES).join('\n'), total: lines.length, hidden: lines.length - SANDBOX_TAIL_LINES };
  }, [log.output, all]);
  return (
    <div className="t-sbx-body" id={id}>
      <p className="t-sbx-note">
        {log.kind === 'copy' ? 'Copied to' : 'Ran on'} <code>{`${log.user}@${log.host}:${log.port}`}</code> ({log.target}).
      </p>
      {showCommand && (
        <details className="t-sbx-script">
          <summary>command</summary>
          <pre>{log.command}</pre>
        </details>
      )}
      {log.error && <p className="t-sbx-note t-sbx-note--fail">{log.error}</p>}
      {log.outputTruncated && <p className="t-sbx-note">The command wrote more than the server keeps: only the end of its output is shown.</p>}
      {log.timedOut && <p className="t-sbx-note t-sbx-note--fail">Stopped at the time limit; the connection was closed. The remote process may still be running.</p>}
      {hidden > 0 && (
        <p className="t-sbx-note">
          Showing the last {SANDBOX_TAIL_LINES} of {total.toLocaleString('en-GB')} lines.{' '}
          <button type="button" className="t-expand t-sbx-all" onClick={onAll}>
            Show all {total.toLocaleString('en-GB')} lines
          </button>
        </p>
      )}
      {(log.kind === 'exec' || shown) && (
        <pre className="t-sbx-out" tabIndex={0} aria-label="Output">
          {shown || '(no output)'}
        </pre>
      )}
    </div>
  );
}
