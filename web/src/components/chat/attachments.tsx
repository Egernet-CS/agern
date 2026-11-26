import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError, type Attachment, type ClientConfig } from '../../api';
import { bytes } from '../../lib/format';
import { Dialog } from '../ui/ui';
import { fitWidth } from './model';

// ---------------------------------------------------------------- limits (from GET /api/config)

type Limits = ClientConfig['attachments'];
const FALLBACK: Limits = { maxFileBytes: 20 << 20, maxMessageBytes: 50 << 20, maxCount: 10, imageTypes: ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] };
let limitsPromise: Promise<Limits> | null = null;
function loadLimits(): Promise<Limits> {
  limitsPromise ??= api.config().then(
    (c) => c.attachments,
    () => {
      limitsPromise = null;
      return FALLBACK;
    },
  );
  return limitsPromise;
}

// ---------------------------------------------------------------- drafts being composed

export type Draft = {
  key: string;
  name: string;
  size: number;
  /** Local object URL for an image preview while composing. */
  preview: string | null;
  status: 'uploading' | 'done' | 'error';
  progress: number;
  error: string | null;
  meta: Attachment | null;
};

let seq = 0;

/** Pasted screenshots arrive as "image.png"; give them a name that says what and when. */
function nameFor(f: File, pasted: boolean): string {
  if (!pasted || !/^image\.(png|jpe?g|gif|webp)$/i.test(f.name)) return f.name || 'attachment';
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `screenshot-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.${f.name.split('.').pop()}`;
}

/**
 * Attachments being composed for the next message of [sessionId]: uploads start at once (with progress),
 * client-side limit checks give immediate per-file errors (the server checks again), and uploads that are
 * removed or abandoned (unmount) are deleted on the server.
 */
export function useAttachmentDrafts(sessionId: string, enabled: boolean) {
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [limits, setLimits] = useState<Limits>(FALLBACK);
  const aborts = useRef(new Map<string, AbortController>());
  const live = useRef<Draft[]>([]);
  live.current = drafts;

  useEffect(() => {
    if (enabled) void loadLimits().then(setLimits);
  }, [enabled]);

  const patch = useCallback((key: string, p: Partial<Draft>) => setDrafts((ds) => ds.map((d) => (d.key === key ? { ...d, ...p } : d))), []);

  const add = useCallback(
    (files: File[], pasted = false) => {
      if (!enabled || files.length === 0) return;
      const current = live.current.filter((d) => d.status !== 'error');
      let count = current.length;
      let total = current.reduce((n, d) => n + d.size, 0);
      const next: Draft[] = [];
      for (const f of files) {
        const key = `d${++seq}`;
        const name = nameFor(f, pasted);
        const preview = limits.imageTypes.includes(f.type) ? URL.createObjectURL(f) : null;
        const base: Draft = { key, name, size: f.size, preview, status: 'uploading', progress: 0, error: null, meta: null };
        let error: string | null = null;
        if (count >= limits.maxCount) error = `Too many — at most ${limits.maxCount} per message`;
        else if (f.size > limits.maxFileBytes) error = `Too large — at most ${bytes(limits.maxFileBytes)} per file`;
        else if (f.size === 0) error = 'Empty file';
        else if (total + f.size > limits.maxMessageBytes) error = `Too large — at most ${bytes(limits.maxMessageBytes)} per message`;
        if (error) {
          next.push({ ...base, status: 'error', error });
          continue;
        }
        count++;
        total += f.size;
        next.push(base);
        const ctl = new AbortController();
        aborts.current.set(key, ctl);
        const named = name === f.name ? f : new File([f], name, { type: f.type });
        api
          .uploadAttachment(sessionId, named, { signal: ctl.signal, onProgress: (l, t) => patch(key, { progress: t ? l / t : 0 }) })
          .then((meta) => {
            aborts.current.delete(key);
            // Removed while uploading: the server copy is not wanted.
            if (!live.current.some((d) => d.key === key)) void api.deleteAttachment(sessionId, meta.id).catch(() => {});
            else patch(key, { status: 'done', progress: 1, meta });
          })
          .catch((e: unknown) => {
            aborts.current.delete(key);
            if (e instanceof ApiError && e.code === 'aborted') return;
            patch(key, { status: 'error', error: e instanceof ApiError ? e.message : 'Upload failed' });
          });
      }
      setDrafts((ds) => [...ds, ...next]);
    },
    [enabled, limits, sessionId, patch],
  );

  const remove = useCallback(
    (key: string) => {
      const d = live.current.find((x) => x.key === key);
      if (!d) return;
      aborts.current.get(key)?.abort();
      aborts.current.delete(key);
      if (d.preview) URL.revokeObjectURL(d.preview);
      if (d.meta) void api.deleteAttachment(sessionId, d.meta.id).catch(() => {});
      setDrafts((ds) => ds.filter((x) => x.key !== key));
    },
    [sessionId],
  );

  /** After a successful send: forget the drafts (the server keeps them, now attached to the message). */
  const clearSent = useCallback(() => {
    for (const d of live.current) if (d.preview) URL.revokeObjectURL(d.preview);
    setDrafts([]);
  }, []);

  // Leaving the chat abandons the draft: stop uploads and delete what was uploaded but not sent.
  useEffect(() => {
    const ab = aborts.current;
    return () => {
      ab.forEach((c) => c.abort());
      for (const d of live.current) {
        if (d.preview) URL.revokeObjectURL(d.preview);
        if (d.meta) void api.deleteAttachment(sessionId, d.meta.id).catch(() => {});
      }
    };
  }, [sessionId]);

  const state = useMemo(
    () => ({
      uploading: drafts.some((d) => d.status === 'uploading'),
      failed: drafts.some((d) => d.status === 'error'),
      ready: drafts.filter((d) => d.status === 'done' && d.meta).map((d) => d.meta!),
    }),
    [drafts],
  );
  return { drafts, add, remove, clearSent, limits, ...state };
}

// ---------------------------------------------------------------- composer chips

export function DraftChips({ drafts, onRemove }: { drafts: Draft[]; onRemove: (key: string) => void }) {
  if (drafts.length === 0) return null;
  return (
    <ul className="att-chips" aria-label="Attachments for the next message">
      {drafts.map((d) => (
        <li key={d.key} className={`att-chip att-chip--${d.status}`}>
          {d.preview ? <img className="att-chip-thumb" src={d.preview} alt="" /> : <span className="att-chip-icon" aria-hidden>▤</span>}
          <span className="att-chip-text">
            <span className="att-chip-name" title={d.name}>{d.name}</span>
            <span className="att-chip-meta">
              {d.status === 'error' ? (
                <span role="alert">{d.error}</span>
              ) : d.status === 'uploading' ? (
                <>uploading {Math.round(d.progress * 100)}% · {bytes(d.size)}</>
              ) : (
                <>{bytes(d.size)}{d.meta?.inline === 'downscaled' ? ' · downscaled for the model' : d.meta?.kind === 'image' && d.meta.inline === 'none' ? ' · file only' : ''}</>
              )}
            </span>
          </span>
          {d.status === 'uploading' && (
            <span className="att-chip-bar" role="progressbar" aria-label={`Uploading ${d.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(d.progress * 100)}>
              <span style={{ width: `${Math.round(d.progress * 100)}%` }} />
            </span>
          )}
          <button type="button" className="att-chip-x" onClick={() => onRemove(d.key)} aria-label={`Remove ${d.name}`} title="Remove">
            ×
          </button>
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------- transcript

export function MessageAttachments({ sessionId, list }: { sessionId: string; list: Attachment[] }) {
  const [open, setOpen] = useState<Attachment | null>(null);
  if (list.length === 0) return null;
  const images = list.filter((a) => a.kind === 'image');
  const files = list.filter((a) => a.kind !== 'image');
  return (
    <div className="t-att">
      {images.length > 0 && (
        <div className="t-att-images">
          {images.map((a) => {
            // Known size: reserve the thumbnail's box before it loads, so the transcript does not shift.
            const fitted = fitWidth(a.width, a.height);
            return (
            <figure key={a.id} className="t-att-figure">
              <button type="button" className="t-att-img" onClick={() => setOpen(a)} aria-label={`Open image ${a.name}`}>
                {fitted != null ? (
                  <img src={api.attachmentUrl(sessionId, a.id, { preview: true })} alt={a.name} loading="lazy" width={a.width ?? undefined} height={a.height ?? undefined} style={{ width: fitted }} />
                ) : (
                  <img src={api.attachmentUrl(sessionId, a.id, { preview: true })} alt={a.name} loading="lazy" />
                )}
              </button>
              <figcaption>
                <span className="t-att-name" title={a.name}>{a.name}</span>
                {a.inline === 'none' && <span className="t-att-note" title={a.note ?? undefined}> · file only, not shown to the model</span>}
                {a.inline === 'downscaled' && <span className="t-att-note" title={a.note ?? undefined}> · downscaled for the model</span>}
              </figcaption>
            </figure>
            );
          })}
        </div>
      )}
      {files.length > 0 && (
        <ul className="t-att-files">
          {files.map((a) => (
            <li key={a.id}>
              <a className="t-att-file" href={api.attachmentUrl(sessionId, a.id)} download={a.name} title={`Download ${a.name}`}>
                <span aria-hidden>▤</span> <span className="t-att-name">{a.name}</span> <span className="t-att-size">{bytes(a.size)}</span>
              </a>
            </li>
          ))}
        </ul>
      )}
      <Dialog open={open !== null} onClose={() => setOpen(null)} title={open?.name ?? 'Image'} wide>
        {open && (
          <div className="att-lightbox">
            <img src={api.attachmentUrl(sessionId, open.id)} alt={open.name} />
            <p className="muted small">
              {open.mediaType} · {bytes(open.size)}
              {open.width && open.height ? ` · ${open.width}×${open.height}` : ''}
              {open.note ? ` · ${open.note}` : ''} · <a href={api.attachmentUrl(sessionId, open.id)} download={open.name}>download</a>
            </p>
          </div>
        )}
      </Dialog>
    </div>
  );
}
