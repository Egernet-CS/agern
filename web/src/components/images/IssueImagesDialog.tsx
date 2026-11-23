import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api';
import { useAsync } from '../../lib/async';
import { bytes } from '../../lib/format';
import { Dialog, ErrorBox, Loading } from '../ui/ui';

/**
 * The images selected for an issue in a chat (docs/ARCHITECTURE.md §10): who selected them, which run uses them, and
 * Remove for their owner until a run locks them. [onChanged] runs after a removal (the issue list reloads its counts).
 */
export function IssueImagesDialog({ repo, issue, me, onClose, onChanged }: { repo: string; issue: number | null; me: string; onClose: () => void; onChanged: () => void }) {
  return (
    <Dialog open={issue !== null} onClose={onClose} title={issue !== null ? `Images for #${issue}` : 'Images'} wide>
      {issue !== null && <IssueImages repo={repo} issue={issue} me={me} onChanged={onChanged} />}
    </Dialog>
  );
}

function IssueImages({ repo, issue, me, onChanged }: { repo: string; issue: number; me: string; onChanged: () => void }) {
  const q = useAsync(() => api.issueImages(repo, issue), [repo, issue]);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const remove = async (id: string) => {
    setBusy(id);
    setErr(null);
    try {
      await api.unselectImage(id);
      q.setData((list) => list?.filter((x) => x.id !== id));
      onChanged();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
    }
  };
  if (q.loading && !q.data) return <Loading />;
  if (q.error) return <ErrorBox error={q.error} retry={q.reload} />;
  const list = q.data ?? [];
  return (
    <div className="issue-images">
      {err && <p className="form-error" role="alert">{err}</p>}
      {list.length === 0 ? (
        <p className="muted">No images selected for this issue.</p>
      ) : (
        <ul className="issue-images-list">
          {list.map((img) => {
            const sel = img.selection;
            return (
              <li key={img.id} className="issue-image">
                <a className="shot-thumb issue-image-thumb" href={api.imageUrl(img.id)} target="_blank" rel="noopener noreferrer" aria-label={`Open image ${sel?.name ?? img.purpose} full size`}>
                  <img src={api.imageUrl(img.id)} alt={img.purpose} loading="lazy" />
                </a>
                <div className="issue-image-text">
                  <strong>{sel?.name ?? img.purpose}</strong>
                  <span className="muted small">purpose: {img.purpose}</span>
                  <span className="muted small">
                    {img.width}×{img.height} · {bytes(img.size)} · selected by {img.owner.username}
                  </span>
                  {sel?.locked && sel.usedByRunId && (
                    <span className="small">
                      in use by run <Link to={`/p/${repo}/runs/${sel.usedByRunId}`}><code>{sel.usedByRunId.slice(0, 8)}</code></Link>
                    </span>
                  )}
                </div>
                {img.owner.username === me && !sel?.locked && (
                  <button type="button" className="btn btn--sm btn--ghost btn--danger" disabled={busy !== null} onClick={() => void remove(img.id)}>
                    {busy === img.id ? 'Removing…' : 'Remove'}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
