import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type FormEvent } from 'react';
import { api, type GeneratedImage, type ImagesPayload } from '../../api';
import { bytes } from '../../lib/format';
import { Dialog } from '../ui/ui';

// ---------------------------------------------------------------- the images of a session (one load per session)

type Entry = { list: GeneratedImage[] | null; error: Error | null; loading: boolean; subs: Set<() => void> };
const entries = new Map<string, Entry>();

function entry(sessionId: string): Entry {
  let e = entries.get(sessionId);
  if (!e) {
    e = { list: null, error: null, loading: false, subs: new Set() };
    entries.set(sessionId, e);
  }
  return e;
}

/** Replaces the entry with a new object (so useSyncExternalStore sees the change) and tells its subscribers. */
function notify(sessionId: string, patch: Partial<Entry>) {
  const next = { ...entry(sessionId), ...patch };
  entries.set(sessionId, next);
  next.subs.forEach((f) => f());
}

function load(sessionId: string) {
  if (entry(sessionId).loading) return;
  notify(sessionId, { loading: true, error: null });
  api.sessionImages(sessionId).then(
    (list) => notify(sessionId, { list, loading: false }),
    (err: Error) => notify(sessionId, { error: err, loading: false }),
  );
}

function replace(sessionId: string, img: GeneratedImage) {
  notify(sessionId, { list: (entry(sessionId).list ?? []).map((x) => (x.id === img.id ? img : x)) });
}

function drop(sessionId: string, id: string) {
  notify(sessionId, { list: (entry(sessionId).list ?? []).filter((x) => x.id !== id) });
}

/** The current state of a session's images; loads once, and again when an event names images the list does not have yet. */
function useSessionImages(sessionId: string, ids: string[]) {
  const snap = useSyncExternalStore(
    (f) => {
      const e = entry(sessionId);
      e.subs.add(f);
      return () => entry(sessionId).subs.delete(f);
    },
    () => entry(sessionId),
  );
  const refreshed = useRef(false);
  useEffect(() => {
    const e = entry(sessionId);
    if (e.list === null && !e.error) load(sessionId);
    else if (e.list && !refreshed.current && ids.some((id) => !e.list!.some((x) => x.id === id))) {
      // Generated after the list was loaded (or discarded since): ask once more.
      refreshed.current = true;
      load(sessionId);
    }
  }, [sessionId, ids]);
  return snap;
}

// ---------------------------------------------------------------- the transcript block of one `images` event

/**
 * One successful image generation in a transcript (docs/ARCHITECTURE.md §10): provider, model, purpose and prompt,
 * and a tile per image. Its owner can use a chat image for an issue, or discard it; everyone else sees it read-only.
 */
export function GeneratedImages({ sessionId, payload, me }: { sessionId: string; payload: ImagesPayload; me?: string }) {
  const ids = useMemo(() => payload.images.map((i) => i.id), [payload]);
  const { list, error, loading } = useSessionImages(sessionId, ids);
  const [open, setOpen] = useState<GeneratedImage | null>(null);
  const n = payload.images.length;
  return (
    <div className="t-gen">
      <div className="t-gen-head">
        <span className="t-dot" aria-hidden>◆</span>
        <span>
          Generated {n} {n === 1 ? 'image' : 'images'} with {payload.providerLabel} · <span className="mono">{payload.model}</span>
          {payload.purpose && <> — <span className="t-gen-purpose">{payload.purpose}</span></>}
        </span>
      </div>
      {payload.prompt && (
        <details className="t-gen-prompt">
          <summary>Prompt</summary>
          <p>{payload.prompt}</p>
        </details>
      )}
      {list === null && loading && <p className="muted small">Loading images…</p>}
      {list === null && error && (
        <p className="form-error small" role="alert">
          Could not load the images: {error.message}{' '}
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => load(sessionId)}>Retry</button>
        </p>
      )}
      {list && (
        <div className="t-att-images">
          {payload.images.map((ev, i) => {
            const img = list.find((x) => x.id === ev.id);
            return img ? (
              <Tile key={ev.id} sessionId={sessionId} img={img} n={i + 1} issueHint={payload.issueNumber} me={me} onOpen={() => setOpen(img)} />
            ) : (
              <figure key={ev.id} className="t-att-figure t-gen-tile">
                <div className="t-gen-gone">discarded</div>
              </figure>
            );
          })}
        </div>
      )}
      <Dialog open={open !== null} onClose={() => setOpen(null)} title={open?.purpose || 'Generated image'} wide>
        {open && (
          <div className="att-lightbox">
            <img src={api.imageUrl(open.id)} alt={open.purpose} />
            <p className="muted small">
              {open.mediaType} · {open.width}×{open.height} · {bytes(open.size)} ·{' '}
              <a href={api.imageUrl(open.id)} target="_blank" rel="noopener noreferrer">Open full size ↗</a>
            </p>
          </div>
        )}
      </Dialog>
    </div>
  );
}

function Tile({ sessionId, img, n, issueHint, me, onOpen }: { sessionId: string; img: GeneratedImage; n: number; issueHint?: number; me?: string; onOpen: () => void }) {
  const [choosing, setChoosing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const mine = img.origin === 'chat' && !!me && img.owner.username === me;
  const sel = img.selection;
  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    setErr(null);
    try {
      await fn();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <figure className="t-att-figure t-gen-tile">
      <button type="button" className="t-att-img" onClick={onOpen} aria-label={`Open image ${img.purpose} ${n}`}>
        <img src={api.imageUrl(img.id)} alt={img.purpose} loading="lazy" />
      </button>
      <figcaption>
        {img.width}×{img.height} · {bytes(img.size)}
      </figcaption>
      {sel && (
        <p className="t-gen-sel small">
          selected for #{sel.issueNumber} as “{sel.name}”{sel.locked && <span className="muted"> · in use by a run</span>}
        </p>
      )}
      {mine && (
        <div className="t-gen-actions">
          {sel ? (
            !sel.locked && (
              <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                onClick={() => void act(async () => replace(sessionId, await api.unselectImage(img.id)))}>
                Remove from #{sel.issueNumber}
              </button>
            )
          ) : (
            <>
              <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={() => setChoosing(true)}>Use for issue…</button>
              <button type="button" className="btn btn--ghost btn--sm btn--danger" disabled={busy}
                onClick={() => void act(async () => {
                  await api.discardImage(img.id);
                  drop(sessionId, img.id);
                })}>
                Discard
              </button>
            </>
          )}
        </div>
      )}
      {err && <p className="form-error small" role="alert">{err}</p>}
      {mine && (
        <UseForIssueDialog
          open={choosing}
          img={img}
          issueHint={issueHint}
          onClose={() => setChoosing(false)}
          onSelected={(updated) => {
            replace(sessionId, updated);
            setChoosing(false);
          }}
        />
      )}
    </figure>
  );
}

function UseForIssueDialog({ open, img, issueHint, onClose, onSelected }: {
  open: boolean;
  img: GeneratedImage;
  issueHint?: number;
  onClose: () => void;
  onSelected: (img: GeneratedImage) => void;
}) {
  const [issue, setIssue] = useState(issueHint ? String(issueHint) : '');
  const [name, setName] = useState(img.purpose.slice(0, 60));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      onSelected(await api.selectImage(img.id, Number(issue), name.trim()));
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const valid = /^\d+$/.test(issue.trim()) && Number(issue) > 0 && name.trim().length > 0;
  return (
    <Dialog open={open} onClose={onClose} title="Use image for an issue">
      <form className="form" onSubmit={submit}>
        <div className="field">
          <label htmlFor={`gi-issue-${img.id}`}>Issue number</label>
          <input id={`gi-issue-${img.id}`} type="number" min={1} inputMode="numeric" value={issue} onChange={(e) => setIssue(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={`gi-name-${img.id}`}>Name</label>
          <input id={`gi-name-${img.id}`} maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="row">
          <button className="btn btn--primary" disabled={busy || !valid}>Use for issue</button>
          <button type="button" className="btn btn--ghost" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Dialog>
  );
}
