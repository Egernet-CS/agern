import { useEffect, useState } from 'react';
import type { RetryMode, RetryOptions } from '../../api';
import { Dialog, Loading } from './ui';

export const RETRY_LABEL: Record<RetryMode, string> = {
  step: 'Retry the failed step',
  continue: 'Continue from the commits',
  start_over: 'Start over',
};

/**
 * Asks how to retry a failed run (docs/ARCHITECTURE.md §6 "Retrying a run"): the failed step, continue from its commits,
 * or start over, with the server's recommendation preselected and its one-line reason shown.
 */
export function RetryDialog({
  open,
  title,
  load,
  onClose,
  onRetry,
}: {
  open: boolean;
  title: string;
  load: () => Promise<RetryOptions>;
  onClose: () => void;
  onRetry: (mode: RetryMode) => Promise<void>;
}) {
  const [opts, setOpts] = useState<RetryOptions | null>(null);
  const [mode, setMode] = useState<RetryMode>('start_over');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    let live = true;
    setOpts(null);
    setErr(null);
    load()
      .then((o) => {
        if (!live) return;
        setOpts(o);
        setMode(o.recommended);
      })
      .catch((e: Error) => live && setErr(e.message));
    return () => {
      live = false;
    };
    // `load` is a fresh closure on every render; reload only when the dialog opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const submit = async () => {
    setBusy(true);
    setErr(null);
    try {
      await onRetry(mode);
      onClose();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} title={title}>
      {!opts && !err && <Loading label="Loading options" />}
      {opts && (
        <>
          <p className="retry-reason" role="status">
            <strong>Recommended: {RETRY_LABEL[opts.recommended]}.</strong> {opts.reason}
          </p>
          <fieldset className="field mode-choice retry-choice">
            <legend className="sr-only">How to retry</legend>
            {opts.options.map((o) => (
              <label key={o.mode} className={o.available ? '' : 'is-disabled'}>
                <input type="radio" name="retry-mode" value={o.mode} checked={mode === o.mode} disabled={!o.available} onChange={() => setMode(o.mode)} />
                <span>
                  <span className="retry-label">
                    {RETRY_LABEL[o.mode]}
                    {o.mode === opts.recommended && <span className="retry-badge">recommended</span>}
                  </span>
                  <span className="muted small retry-detail">{o.detail}</span>
                </span>
              </label>
            ))}
          </fieldset>
        </>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}
      <div className="dialog-actions">
        <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
        <button type="button" className="btn btn--primary" onClick={() => void submit()} disabled={busy || !opts}>
          {busy ? 'Retrying…' : RETRY_LABEL[mode]}
        </button>
      </div>
    </Dialog>
  );
}
