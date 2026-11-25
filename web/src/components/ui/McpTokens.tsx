import { useState } from 'react';
import type { McpToken } from '../../api';
import { dateTime, timeAgo } from '../../lib/format';
import { Dialog } from './ui';

/** Personal MCP tokens as a table (Settings: the caller's own; Admin: everyone's, with a user column). */
export function McpTokenTable({ tokens, showUser, onRevoke }: { tokens: McpToken[]; showUser?: boolean; onRevoke: (t: McpToken) => void }) {
  return (
    <div className="table-wrap">
      <table className="table mcp-tokens-table">
        <thead>
          <tr>
            {showUser && <th scope="col">user</th>}
            <th scope="col">name</th>
            <th scope="col">project</th>
            <th scope="col">created</th>
            <th scope="col">last used</th>
            <th scope="col">expires</th>
            <th scope="col"><span className="sr-only">actions</span></th>
          </tr>
        </thead>
        <tbody>
          {tokens.map((t) => (
            <tr key={t.id}>
              {showUser && (
                <td data-label="user"><strong>{t.owner.displayName}</strong> <span className="muted">{t.owner.username}</span></td>
              )}
              <td data-label="name"><strong>{t.name}</strong></td>
              <td data-label="project"><code>{t.repo}</code></td>
              <td data-label="created" title={dateTime(t.createdAt)}>{timeAgo(t.createdAt)}</td>
              <td data-label="last used" title={t.lastUsedAt ? dateTime(t.lastUsedAt) : undefined}>{t.lastUsedAt ? timeAgo(t.lastUsedAt) : 'never'}</td>
              <td data-label="expires">
                {t.expired ? (
                  <span className="mcp-expired">expired</span>
                ) : t.expiresAt ? (
                  <span title={dateTime(t.expiresAt)}>{new Date(t.expiresAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}</span>
                ) : (
                  'never'
                )}
              </td>
              <td>
                <button type="button" className="btn btn--sm btn--ghost btn--danger" onClick={() => onRevoke(t)} aria-label={`Revoke token ${t.name} for ${t.repo}`}>
                  Revoke
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The confirmation before revoking [target]; [revoke] does it and resolves when done (an error is shown inline).
 * [admin]: the text names the token's user.
 */
export function RevokeMcpTokenDialog({ target, admin, onClose, revoke }: {
  target: McpToken | null;
  admin?: boolean;
  onClose: () => void;
  revoke: (t: McpToken) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const close = () => {
    if (busy) return;
    setErr(null);
    onClose();
  };
  const go = async () => {
    if (!target) return;
    setBusy(true);
    setErr(null);
    try {
      await revoke(target);
      onClose();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={!!target} onClose={close} title={`Revoke token "${target?.name ?? ''}"?`}>
      <div className="form">
        <p>
          {admin ? `${target?.owner.username ?? ''}'s Claude` : 'Claude on that computer'} loses access to <code>{target?.repo}</code> immediately.
        </p>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={close} disabled={busy} autoFocus>Cancel</button>
          <button type="button" className="btn btn--danger" onClick={() => void go()} disabled={busy}>{busy ? 'Revoking…' : 'Revoke'}</button>
        </div>
      </div>
    </Dialog>
  );
}
