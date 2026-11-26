import { useId, useMemo, useState } from 'react';
import type { SandboxVmLog } from '../../api/types';
import { SANDBOX_TAIL_LINES } from '../sandbox/SandboxLog';

function seconds(ms: number) {
  return ms < 10_000 ? `${(ms / 1000).toFixed(1)} s` : ms < 120_000 ? `${Math.round(ms / 1000)} s` : `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
}

/** The outcome of a sandbox VM operation in a few words, and whether it failed. */
export function sandboxVmOutcome(log: SandboxVmLog): { failed: boolean; text: string } {
  if (log.error) return { failed: true, text: log.timedOut ? 'timed out' : 'error' };
  if (log.timedOut) return { failed: true, text: 'timed out' };
  if (log.step === 'upload' && log.exitCode == null) return { failed: false, text: 'uploaded' };
  if (log.exitCode == null) return { failed: true, text: 'no exit status' };
  return { failed: log.exitCode !== 0, text: `exit ${log.exitCode}` };
}

/** What the line names: the deploy step, `reset`, or the command's first line. */
function whatOf(log: SandboxVmLog): { tag: string | null; line: string; multiLine: boolean } {
  const command = log.command ?? '';
  const first = command.split('\n')[0];
  const multiLine = command.trim().includes('\n');
  if (log.kind === 'deploy') {
    const sha = log.sha ? ` ${log.sha.slice(0, 7)}` : '';
    return { tag: `deploy ${log.step ?? ''}`.trim() + sha, line: log.step === 'upload' ? 'agern-sandbox-vm.tar.gz' : first + (multiLine ? ' …' : ''), multiLine };
  }
  if (log.kind === 'reset') return { tag: 'reset', line: first + (multiLine ? ' …' : ''), multiLine };
  return { tag: null, line: first + (multiLine ? ' …' : ''), multiLine };
}

/**
 * A step of a sandbox VM deploy, a command or a reset in a builder's transcript (docs/SANDBOX-VMS.md), in the look of the
 * deploy block with a "sandbox VM" chip: one line with the VM, the step or command, the outcome and the duration;
 * opened, the error and the output in the sandbox log's terminal style. Nothing of the output is rendered while collapsed.
 */
export function SandboxVmLogBlock({ log }: { log: SandboxVmLog }) {
  const [open, setOpen] = useState(false);
  const [all, setAll] = useState(false);
  const id = useId();
  const { failed, text } = sandboxVmOutcome(log);
  const { tag, line, multiLine } = whatOf(log);
  const label = `Sandbox VM ${tag ?? 'command'} on ${log.vm}${line ? `: ${line}` : ''}`;
  return (
    <div className={`t-sbx t-deploy t-svm ${failed ? 't-sbx--fail' : 't-sbx--ok'}`}>
      <button type="button" className="t-sbx-head" aria-expanded={open} aria-controls={id} aria-label={label} onClick={() => setOpen((o) => !o)}>
        <span className="t-sbx-caret" aria-hidden>
          {open ? '▾' : '▸'}
        </span>
        <span className="t-sbx-cmd">
          <span className="sbx-plat t-sbx-plat t-svm-chip" title={`${log.user}@${log.host}:${log.port}`}>
            sandbox VM
          </span>
          <span className="t-svm-vm">{log.vm}</span>{' '}
          {tag ? <span className="t-sbx-setup">{tag}</span> : <span className="t-sbx-prompt" aria-hidden>$</span>} {line}
        </span>
        <span className="t-sbx-meta">
          <span className={`t-sbx-exit ${failed ? 't-sbx-exit--fail' : ''}`}>{failed ? `✗ ${text}` : `✓ ${text}`}</span>
          <span>{seconds(log.durationMs)}</span>
          {log.outputTruncated && <span className="t-sbx-trunc">truncated</span>}
        </span>
      </button>
      {open && <SandboxVmLogBody id={id} log={log} all={all} onAll={() => setAll(true)} showCommand={multiLine} />}
    </div>
  );
}

function SandboxVmLogBody({ id, log, all, onAll, showCommand }: { id: string; log: SandboxVmLog; all: boolean; onAll: () => void; showCommand: boolean }) {
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
        {log.step === 'upload' ? 'Uploaded to' : 'Ran on'} <code>{`${log.user}@${log.host}:${log.port}`}</code> (sandbox VM {log.vm})
        {log.sha && (
          <>
            , commit <code>{log.sha.slice(0, 12)}</code>
          </>
        )}
        .
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
      {(log.kind !== 'deploy' || log.step !== 'upload' || shown) && (
        <pre className="t-sbx-out" tabIndex={0} aria-label="Output">
          {shown || '(no output)'}
        </pre>
      )}
    </div>
  );
}
