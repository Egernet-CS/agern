// A run or release that waits for the network (docs/ARCHITECTURE.md §6 "Transient failures"): the banner on the run
// page and the confirmation before giving up.
import type { RunWait } from '../../api';
import { dateTime, timeAgo } from '../../lib/format';
import { shortClock } from '../../lib/networkWait';
import { Dialog } from './ui';

/** The text a run's give-up dialog shows. */
export const GIVE_UP_RUN_TEXT =
  'The run fails now, as it would have without waiting. Its worktree is removed; the branch and any pull request stay on GitHub, and you can retry the run afterwards.';

/**
 * The run page's banner while [wait] is set. [onRetry] and [onGiveUp] are shown only when given (the owner or an
 * admin); [retrying] disables "Retry now" while its request runs.
 */
export function NetworkWaitBanner({
  wait, now, onRetry, onGiveUp, retrying, disabled,
}: {
  wait: RunWait;
  now: number;
  onRetry?: () => void;
  onGiveUp?: () => void;
  retrying?: boolean;
  disabled?: boolean;
}) {
  return (
    <div className="notice notice--wait network-wait" role="status">
      <div className="notice-text">
        <strong>Waiting for the network</strong>
        since {dateTime(wait.since)} ({timeAgo(wait.since, now)}). <span className="network-wait-detail">{wait.detail}</span>{' '}
        Next try at {shortClock(wait.nextAttemptAt, now)} (attempt {wait.attempt + 1}).
      </div>
      {(onRetry || onGiveUp) && (
        <div className="notice-action network-wait-actions">
          {onRetry && (
            <button type="button" className="btn btn--sm btn--primary" onClick={onRetry} disabled={retrying || disabled}>
              {retrying ? 'Retrying…' : 'Retry now'}
            </button>
          )}
          {onGiveUp && (
            <button type="button" className="btn btn--sm btn--ghost btn--danger" onClick={onGiveUp} disabled={retrying || disabled} aria-haspopup="dialog">
              Give up…
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/** "Give up waiting?" for a run, or for a release whose next issue could not start ([text] says which). */
export function GiveUpDialog({
  open, title = 'Give up waiting?', text, busy, onClose, onConfirm,
}: {
  open: boolean;
  title?: string;
  text: string;
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog open={open} onClose={onClose} title={title}>
      <p>{text}</p>
      <div className="dialog-actions">
        <button type="button" className="btn btn--ghost" onClick={onClose} data-autofocus>Keep waiting</button>
        <button type="button" className="btn btn--danger" onClick={onConfirm} disabled={busy}>{busy ? 'Giving up…' : 'Give up'}</button>
      </div>
    </Dialog>
  );
}
