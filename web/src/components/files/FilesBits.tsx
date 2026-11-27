import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, type ProjectFile, type ProjectFiles, type Run } from '../../api';
import { bytes } from '../../lib/format';
import { CHUNK_RETRIES, filesCount, nextChunk, retryDelayMs, shortSha } from '../../lib/projectFiles';

/** The Project page's Files row: "No files", or "<n> file(s) · <used> of <project limit>". */
export function filesSummary(pf: ProjectFiles): string {
  const n = pf.files.length;
  if (n === 0) return 'No files';
  return `${filesCount(n)} · ${bytes(pf.usage.projectBytes)} of ${bytes(pf.limits.maxProjectBytes)}`;
}

/** One line on the run page, below the sandbox lines: the project files the run got. Nothing when it got none. */
export function RunProjectFilesLine({ run }: { run: Run }) {
  const pf = run.projectFiles;
  if (!pf || pf.files.length === 0) return null;
  return (
    <div className="run-pf">
      <span className="run-sbx-k">Project files ({pf.files.length})</span>
      <ul className="run-pf-list">
        {pf.files.map((f) => (
          <li key={f.name}>
            <code className="run-pf-name">{f.name}</code> <span className="muted">v{f.version}</span>{' '}
            <code className="muted" title={`SHA-256 ${f.sha256}`}>{shortSha(f.sha256)}</code> <span className="muted">{bytes(f.size)}</span>
            {f.required && <span className="tag">required by sandbox</span>}
            {f.previousSha256 && (
              <span className="tag pf-tag--changed" title={`Was ${shortSha(f.previousSha256)} in the previous run`}>
                changed since the previous run
              </span>
            )}
          </li>
        ))}
      </ul>
      <Link to={`/p/${pf.repo}/files`} className="run-sbx-link" aria-label={`Files of ${pf.repo}`}>
        Files
      </Link>
    </div>
  );
}

// ---------------------------------------------------------------- uploads

export type FileUpload = {
  key: string;
  file: File;
  /** The name it is stored under. */
  name: string;
  note: string | null;
  /** The expected SHA-256, when one was given. */
  sha256: string | null;
  size: number;
  /** `interrupted`: retries ran out, "Resume" goes on from what the server has. */
  status: 'uploading' | 'completing' | 'retrying' | 'interrupted' | 'error' | 'done';
  /** Bytes the server confirmed. */
  received: number;
  /** Bytes of the chunk in flight that have left the browser. */
  sending: number;
  uploadId: string | null;
  error: string | null;
};

export type UploadRequest = { file: File; name: string; note: string | null; sha256: string | null };

let seq = 0;
const sleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const t = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => (clearTimeout(t), resolve()));
  });

/** Failures worth trying again: the network, the server's own trouble, and an offset that drifted (read it again). */
const retryable = (e: unknown) =>
  e instanceof ApiError &&
  (e.status === 0 || (e.status >= 500 && e.status !== 507) || e.code === 'upload_offset_mismatch' || e.code === 'upload_busy' || e.status === 408 || e.status === 429);

const aborted = (e: unknown) => e instanceof ApiError && e.code === 'aborted';
const message = (e: unknown) => (e instanceof Error ? e.message : 'Upload failed');

/**
 * Resumable chunked uploads to a project's files (modelled on useAttachmentDrafts): each upload is created on the server
 * (or the caller's open one with the same name, size and hash is resumed), then sent in 8 MiB slices; a failed chunk is
 * retried up to 5 times with back-off, each time after reading `received` from the server, before the upload shows as
 * interrupted with "Resume". [onDone] runs when a file is published; [announce] gets the status line to speak.
 */
export function useProjectFileUploads(repo: string, onDone: (f: ProjectFile) => void, announce: (text: string) => void) {
  const [uploads, setUploads] = useState<FileUpload[]>([]);
  const live = useRef<FileUpload[]>([]);
  live.current = uploads;
  const aborts = useRef(new Map<string, AbortController>());
  const done = useRef(onDone);
  done.current = onDone;
  const say = useRef(announce);
  say.current = announce;

  const patch = useCallback((key: string, p: Partial<FileUpload>) => {
    // Keep the ref current at once: the loop reads it between awaits.
    live.current = live.current.map((u) => (u.key === key ? { ...u, ...p } : u));
    setUploads((us) => us.map((u) => (u.key === key ? { ...u, ...p } : u)));
  }, []);

  const drive = useCallback(
    async (key: string) => {
      const ctl = new AbortController();
      aborts.current.set(key, ctl);
      const signal = ctl.signal;
      const get = () => live.current.find((u) => u.key === key);
      let attempt = 0;
      try {
        let up = get();
        if (!up) return;
        // Create (or find again) the upload; a known one is re-read so it goes on from what the server has.
        for (;;) {
          try {
            const u = up.uploadId
              ? await api.projectFileUpload(repo, up.uploadId)
              : await api.createProjectFileUpload(repo, {
                  name: up.name,
                  size: up.size,
                  ...(up.sha256 ? { sha256: up.sha256 } : {}),
                  ...(up.note ? { note: up.note } : {}),
                });
            patch(key, { uploadId: u.id, received: u.received, sending: 0, status: 'uploading', error: null });
            break;
          } catch (e) {
            if (signal.aborted) return;
            if (!retryable(e) || ++attempt > CHUNK_RETRIES) throw e;
            patch(key, { status: 'retrying', error: null });
            await sleep(retryDelayMs(attempt), signal);
            if (signal.aborted) return;
          }
        }
        attempt = 0;
        for (;;) {
          up = get();
          if (!up || signal.aborted) return;
          const range = nextChunk(up.size, up.received);
          if (!range) {
            patch(key, { status: 'completing' });
            try {
              const f = await api.completeProjectFileUpload(repo, up.uploadId!);
              patch(key, { status: 'done', received: up.size, sending: 0 });
              say.current(`Uploaded ${f.name}`);
              done.current(f);
              return;
            } catch (e) {
              // The server has less than we thought: read `received` and send the rest.
              if (e instanceof ApiError && e.code === 'upload_incomplete' && ++attempt <= CHUNK_RETRIES) {
                const u = await api.projectFileUpload(repo, up.uploadId!);
                patch(key, { received: u.received, status: 'uploading' });
                continue;
              }
              throw e;
            }
          }
          const [start, end] = range;
          try {
            const u = await api.putProjectFileChunk(repo, up.uploadId!, start, up.file.slice(start, end), {
              signal,
              onProgress: (loaded) => patch(key, { sending: Math.min(loaded, end - start) }),
            });
            attempt = 0;
            patch(key, { received: u.received, sending: 0, status: 'uploading', error: null });
          } catch (e) {
            if (signal.aborted || aborted(e)) return;
            if (!retryable(e)) throw e;
            if (++attempt > CHUNK_RETRIES) {
              patch(key, { status: 'interrupted', sending: 0, error: message(e) });
              say.current(`Upload of ${up.name} interrupted`);
              return;
            }
            patch(key, { status: 'retrying', sending: 0 });
            await sleep(retryDelayMs(attempt), signal);
            if (signal.aborted) return;
            // Go on from what the server really has (the failed chunk may have arrived in part or in full).
            try {
              const u = await api.projectFileUpload(repo, up.uploadId!);
              patch(key, { received: u.received, status: 'uploading' });
            } catch (g) {
              if (signal.aborted) return;
              if (!retryable(g)) throw g;
            }
          }
        }
      } catch (e) {
        if (signal.aborted || aborted(e)) return;
        const cur = get();
        if (retryable(e) && cur?.uploadId) {
          patch(key, { status: 'interrupted', sending: 0, error: message(e) });
          say.current(`Upload of ${cur.name} interrupted`);
        } else {
          patch(key, { status: 'error', sending: 0, error: message(e) });
        }
      } finally {
        if (aborts.current.get(key) === ctl) aborts.current.delete(key);
      }
    },
    [repo, patch],
  );

  const start = useCallback(
    (req: UploadRequest) => {
      const key = `u${++seq}`;
      const u: FileUpload = { key, ...req, size: req.file.size, status: 'uploading', received: 0, sending: 0, uploadId: null, error: null };
      live.current = [...live.current, u];
      setUploads((us) => [...us, u]);
      void drive(key);
    },
    [drive],
  );

  const resume = useCallback(
    (key: string) => {
      patch(key, { status: 'uploading', error: null });
      void drive(key);
    },
    [drive, patch],
  );

  /** Stops the upload and deletes it on the server. */
  const cancel = useCallback(
    (key: string) => {
      const u = live.current.find((x) => x.key === key);
      aborts.current.get(key)?.abort();
      aborts.current.delete(key);
      live.current = live.current.filter((x) => x.key !== key);
      setUploads((us) => us.filter((x) => x.key !== key));
      if (u?.uploadId && u.status !== 'done') void api.cancelProjectFileUpload(repo, u.uploadId).catch(() => {});
      if (u && u.status !== 'done') say.current(`Cancelled the upload of ${u.name}`);
    },
    [repo],
  );

  /** Forgets a finished or failed row (a failed upload's bytes are deleted on the server). */
  const dismiss = useCallback(
    (key: string) => {
      const u = live.current.find((x) => x.key === key);
      live.current = live.current.filter((x) => x.key !== key);
      setUploads((us) => us.filter((x) => x.key !== key));
      if (u?.status === 'error' && u.uploadId) void api.cancelProjectFileUpload(repo, u.uploadId).catch(() => {});
    },
    [repo],
  );

  // Leaving the page stops sending; the server keeps the open uploads so choosing the same file again resumes.
  useEffect(() => {
    const ab = aborts.current;
    return () => ab.forEach((c) => c.abort());
  }, [repo]);

  return { uploads, start, resume, cancel, dismiss };
}

/** One upload row: name, progress bar (percent and bytes), state and its buttons. */
export function UploadRow({ u, onCancel, onResume, onDismiss }: { u: FileUpload; onCancel: () => void; onResume: () => void; onDismiss: () => void }) {
  const sent = Math.min(u.size, u.received + u.sending);
  const pct = u.size ? Math.floor((sent / u.size) * 100) : 0;
  const state =
    u.status === 'interrupted'
      ? 'Upload interrupted'
      : u.status === 'retrying'
        ? 'Connection trouble, trying again…'
        : u.status === 'completing'
          ? 'Checking…'
          : u.status === 'done'
            ? 'Uploaded'
            : u.status === 'error'
              ? 'Failed'
              : 'Uploading';
  return (
    <li className={`pf-upload pf-upload--${u.status}`}>
      <div className="pf-upload-top">
        <code className="pf-upload-name">{u.name}</code>
        <span className="muted small">
          {state} · {pct}% · {bytes(sent)} of {bytes(u.size)}
        </span>
      </div>
      <span className="pf-bar" role="progressbar" aria-label={`Upload of ${u.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-valuetext={`${pct}% · ${bytes(sent)} of ${bytes(u.size)}`}>
        <span style={{ width: `${pct}%` }} />
      </span>
      {u.error && u.status !== 'interrupted' && <p className="form-error" role="alert">{u.error}</p>}
      {u.error && u.status === 'interrupted' && <p className="muted small">{u.error}</p>}
      <div className="pf-upload-actions">
        {u.status === 'interrupted' && (
          <button type="button" className="btn btn--sm btn--primary" onClick={onResume} aria-label={`Resume the upload of ${u.name}`}>
            Resume
          </button>
        )}
        {u.status === 'done' || u.status === 'error' ? (
          <button type="button" className="btn btn--sm btn--ghost" onClick={onDismiss} aria-label={`Dismiss ${u.name}`}>
            Dismiss
          </button>
        ) : (
          <button type="button" className="btn btn--sm btn--ghost btn--danger" onClick={onCancel} disabled={u.status === 'completing'} aria-label={`Cancel the upload of ${u.name}`}>
            Cancel
          </button>
        )}
      </div>
    </li>
  );
}
