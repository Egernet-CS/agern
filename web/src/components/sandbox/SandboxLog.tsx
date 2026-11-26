import { useId, useMemo, useState } from 'react';
import type { SandboxLog } from '../../api/types';

/** Lines of output rendered when a block is opened (its end); "Show all" renders the rest. */
export const SANDBOX_TAIL_LINES = 200;

function seconds(ms: number) {
  return ms < 10_000 ? `${(ms / 1000).toFixed(1)} s` : ms < 120_000 ? `${Math.round(ms / 1000)} s` : `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
}

/**
 * A sandbox command (or the setup script) in a builder's or UI reviewer's transcript: collapsed to one line with exit code and duration;
 * opened, the end of its output in the terminal style, with "Show all" for long output. Nothing of the output is
 * rendered while collapsed, so long logs cost nothing until someone looks.
 */
export function SandboxLogBlock({ log }: { log: SandboxLog }) {
  const [open, setOpen] = useState(false);
  const [all, setAll] = useState(false);
  const id = useId();
  const failed = log.timedOut || log.exitCode !== 0;
  const setup = log.kind === 'setup';
  const firstLine = log.command.split('\n')[0];
  const multiLine = log.command.trim().includes('\n');
  const outcome = log.timedOut ? 'timed out' : `exit ${log.exitCode}`;
  return (
    <div className={`t-sbx ${failed ? 't-sbx--fail' : 't-sbx--ok'}`}>
      <button type="button" className="t-sbx-head" aria-expanded={open} aria-controls={id} onClick={() => setOpen((o) => !o)}>
        <span className="t-sbx-caret" aria-hidden>
          {open ? '▾' : '▸'}
        </span>
        <span className="t-sbx-cmd">
          <span className="sr-only">
            {log.review ? 'Review sandbox' : 'Sandbox'} {setup ? 'setup script' : 'command'}
            {log.platform ? ` on platform ${log.platform}` : ''}:{' '}
          </span>
          {log.review && (
            <span className="sbx-plat t-sbx-plat t-sbx-review" aria-hidden title="Ran in the read-only review sandbox">
              review
            </span>
          )}
          {log.platform && (
            <span className="sbx-plat t-sbx-plat" aria-hidden title={`Ran on platform ${log.platform}`}>
              {log.platform}
            </span>
          )}
          {setup ? <span className="t-sbx-setup">setup script</span> : <><span className="t-sbx-prompt" aria-hidden>$ </span>{firstLine}{multiLine && ' …'}</>}
        </span>
        <span className="t-sbx-meta">
          <span className={`t-sbx-exit ${failed ? 't-sbx-exit--fail' : ''}`}>{failed ? (log.timedOut ? '✗ timed out' : `✗ ${outcome}`) : `✓ ${outcome}`}</span>
          <span>{seconds(log.durationMs)}</span>
          {log.outputTruncated && <span className="t-sbx-trunc">truncated</span>}
        </span>
      </button>
      {open && <SandboxLogBody id={id} log={log} all={all} onAll={() => setAll(true)} showCommand={setup || multiLine} />}
    </div>
  );
}

function SandboxLogBody({ id, log, all, onAll, showCommand }: { id: string; log: SandboxLog; all: boolean; onAll: () => void; showCommand: boolean }) {
  const { shown, total, hidden } = useMemo(() => {
    const text = log.output.replace(/\n$/, '');
    if (!text) return { shown: '', total: 0, hidden: 0 };
    const lines = text.split('\n');
    if (all || lines.length <= SANDBOX_TAIL_LINES) return { shown: text, total: lines.length, hidden: 0 };
    return { shown: lines.slice(-SANDBOX_TAIL_LINES).join('\n'), total: lines.length, hidden: lines.length - SANDBOX_TAIL_LINES };
  }, [log.output, all]);
  return (
    <div className="t-sbx-body" id={id}>
      {showCommand && (
        <details className="t-sbx-script">
          <summary>{log.kind === 'setup' ? 'script' : 'command'}</summary>
          <pre>{log.command}</pre>
        </details>
      )}
      {log.outputTruncated && <p className="t-sbx-note">The command wrote more than the server keeps: only the end of its output is shown.</p>}
      {log.timedOut && <p className="t-sbx-note t-sbx-note--fail">Stopped at the time limit; every process in the sandbox was killed (files are kept).</p>}
      {hidden > 0 && (
        <p className="t-sbx-note">
          Showing the last {SANDBOX_TAIL_LINES} of {total.toLocaleString('en-GB')} lines.{' '}
          <button type="button" className="t-expand t-sbx-all" onClick={onAll}>
            Show all {total.toLocaleString('en-GB')} lines
          </button>
        </p>
      )}
      <pre className="t-sbx-out" tabIndex={0} aria-label="Output">
        {shown || '(no output)'}
      </pre>
    </div>
  );
}
