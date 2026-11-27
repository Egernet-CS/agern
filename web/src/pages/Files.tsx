import { useCallback, useEffect, useRef, useState, type DragEvent, type FormEvent, type KeyboardEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, type Owner, type ProjectFile, type ProjectFiles, type ProjectFileUpload } from '../api';
import { UploadRow, useProjectFileUploads } from '../components/files/FilesBits';
import { CopyButton, Dialog, Empty, ErrorBox, Loading } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { bytes, timeAgo } from '../lib/format';
import { fileNameProblem, limitProblem, normalizeSha256, shortSha } from '../lib/projectFiles';
import { useFeedReconnects } from '../lib/realtime';

const who = (o: Owner) => o.displayName || o.username;

/** A file chosen for upload, waiting for its dialog. `replace`: Replace on a row (the name is fixed); `resume`: an open upload. */
type Queued = { key: string; file: File; replace: ProjectFile | null; resume: ProjectFileUpload | null };
let qseq = 0;

/**
 * A project's files: uploaded once per project, handed read-only to every chat and run, never committed. Managers upload
 * (chunked and resumable), replace, annotate, allow downloads and delete; admins also choose the managers.
 */
export function FilesPage() {
  const { repo = '' } = useParams();
  const me = useMe();
  const reconnects = useFeedReconnects();
  const q = useAsync(() => api.projectFiles(repo), [repo, reconnects]);
  const [live, setLive] = useState('');
  const [queue, setQueue] = useState<Queued[]>([]);
  const [editing, setEditing] = useState<ProjectFile | null>(null);
  const [deleting, setDeleting] = useState<ProjectFile | null>(null);
  const [rowErr, setRowErr] = useState<string | null>(null);
  const [busyIds, setBusyIds] = useState<Set<string>>(new Set());
  // The download switch shows the requested state while the server works on it (and falls back on an error).
  const [pendingDownload, setPendingDownload] = useState<Map<string, boolean>>(new Map());
  const pickRef = useRef<HTMLInputElement>(null);
  const replaceRef = useRef<HTMLInputElement>(null);
  const replaceTarget = useRef<ProjectFile | null>(null);
  const resumeRef = useRef<HTMLInputElement>(null);
  const resumeTarget = useRef<ProjectFileUpload | null>(null);
  const [dragging, setDragging] = useState(false);

  const announce = useCallback((t: string) => setLive(t), []);
  const reload = q.reload;
  const up = useProjectFileUploads(repo, reload, announce);

  useEffect(() => {
    document.title = `Files · ${repo} · Agern`;
  }, [repo]);

  if (q.error && !q.data)
    return (
      <div className="page page--narrow">
        {q.error instanceof ApiError && q.error.status === 404 ? (
          <Empty title={`No project called “${repo}”`}>
            It doesn’t exist in the organization, or your GitHub token can’t read it. <Link to="/">All projects</Link>
          </Empty>
        ) : (
          <ErrorBox error={q.error} retry={q.reload} />
        )}
      </div>
    );
  const pf = q.data;
  if (!pf) return <div className="page page--narrow"><Loading label="Loading the project’s files" /></div>;

  const manage = pf.canManage;
  const enqueue = (files: File[], replace: ProjectFile | null = null, resume: ProjectFileUpload | null = null) => {
    if (!manage || files.length === 0) return;
    setQueue((qs) => [...qs, ...files.map((file) => ({ key: `q${++qseq}`, file, replace, resume }))]);
  };
  const openPicker = () => pickRef.current?.click();
  const onZoneKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      openPicker();
    }
  };
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    enqueue(Array.from(e.dataTransfer.files));
  };

  const busy = (id: string, on: boolean) =>
    setBusyIds((s) => {
      const n = new Set(s);
      if (on) n.add(id);
      else n.delete(id);
      return n;
    });
  const replaceFile = (f: ProjectFile, next: ProjectFile) => q.setData((d) => d && { ...d, files: d.files.map((x) => (x.id === f.id ? next : x)) });

  const setDownload = async (f: ProjectFile, downloadable: boolean) => {
    setRowErr(null);
    busy(f.id, true);
    setPendingDownload((m) => new Map(m).set(f.id, downloadable));
    try {
      replaceFile(f, await api.updateProjectFile(repo, f.id, { downloadable }));
      announce(downloadable ? `Download of ${f.name} allowed` : `Download of ${f.name} switched off`);
    } catch (e) {
      setRowErr((e as Error).message);
    } finally {
      busy(f.id, false);
      setPendingDownload((m) => {
        const next = new Map(m);
        next.delete(f.id);
        return next;
      });
    }
  };

  // Open uploads on the server that this tab is not sending (from another tab, or before a reload).
  const active = new Set(up.uploads.map((u) => u.uploadId).filter(Boolean));
  const stale = pf.uploads.filter((u) => !active.has(u.id));
  const current = queue[0] ?? null;

  return (
    <div className="page sbx-page pf-page">
      <header className="run-head sbx-head">
        <div className="rh-crumbs">
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
          <span className="crumb">files</span>
        </div>
        <div className="rh-title">
          <h1>Files</h1>
        </div>
        <p className="muted small pf-usage">
          {bytes(pf.usage.projectBytes)} of {bytes(pf.limits.maxProjectBytes)} in this project · {bytes(pf.usage.totalBytes)} of {bytes(pf.limits.maxTotalBytes)} on this server ·
          largest file {bytes(pf.limits.maxFileBytes)}
        </p>
        <p className="sbx-about">
          Files here are available read-only to every chat and run of <strong>{repo}</strong> and are never committed or sent to GitHub.
        </p>
      </header>

      <div className="pf-live form-ok" aria-live="polite">{live}</div>

      {!manage && (
        <p className="muted small sbx-who">
          {pf.managers.length ? <>Files are managed by admins and: {pf.managers.map(who).join(', ')}.</> : 'Files are managed by admins.'}
        </p>
      )}

      {manage && (
        <section className="card sbx-card pf-uploader" aria-labelledby="pf-up-h">
          <h2 id="pf-up-h" className="sbx-h">Upload</h2>
          <div
            className={`pf-drop ${dragging ? 'is-dragging' : ''}`}
            role="button"
            tabIndex={0}
            aria-label="Drop files here, or press Enter to choose files to upload"
            onKeyDown={onZoneKey}
            onClick={openPicker}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
          >
            Drop files here to upload them, or choose them.
          </div>
          <div className="row">
            <button type="button" className="btn btn--primary" onClick={openPicker}>
              Upload file
            </button>
            <span className="muted small">Uploads continue where they stopped if the connection drops.</span>
          </div>
          <input
            ref={pickRef}
            type="file"
            multiple
            hidden
            className="pf-input"
            aria-label="Choose files to upload"
            onChange={(e) => {
              enqueue(Array.from(e.target.files ?? []));
              e.target.value = '';
            }}
          />
          <input
            ref={replaceRef}
            type="file"
            hidden
            className="pf-input-replace"
            aria-label="Choose the replacement file"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f && replaceTarget.current) enqueue([f], replaceTarget.current);
              e.target.value = '';
            }}
          />
          <input
            ref={resumeRef}
            type="file"
            hidden
            className="pf-input-resume"
            aria-label="Choose the same file again to resume"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f && resumeTarget.current) enqueue([f], null, resumeTarget.current);
              e.target.value = '';
            }}
          />
          {(up.uploads.length > 0 || stale.length > 0) && (
            <ul className="pf-uploads" aria-label="Uploads">
              {up.uploads.map((u) => (
                <UploadRow key={u.key} u={u} onCancel={() => up.cancel(u.key)} onResume={() => up.resume(u.key)} onDismiss={() => up.dismiss(u.key)} />
              ))}
              {stale.map((u) => (
                <StaleUpload
                  key={u.id}
                  u={u}
                  onResume={() => {
                    resumeTarget.current = u;
                    resumeRef.current?.click();
                  }}
                  onCancel={async () => {
                    setRowErr(null);
                    try {
                      await api.cancelProjectFileUpload(repo, u.id);
                      announce(`Cancelled the upload of ${u.name}`);
                      q.reload();
                    } catch (e) {
                      setRowErr((e as Error).message);
                    }
                  }}
                />
              ))}
            </ul>
          )}
        </section>
      )}

      {rowErr && <p className="form-error" role="alert">{rowErr}</p>}

      <section className="card sbx-card" aria-labelledby="pf-list-h">
        <h2 id="pf-list-h" className="sbx-h">
          Files <span className="muted small">{pf.files.length}</span>
        </h2>
        {pf.files.length === 0 ? (
          <div className="pf-empty">
            <p>No files yet.</p>
            {manage && <p className="muted small">Drop files on the area above, or use “Upload file”.</p>}
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table pf-table">
              <thead>
                <tr>
                  <th scope="col">name</th>
                  <th scope="col">size</th>
                  <th scope="col">version</th>
                  <th scope="col">SHA-256</th>
                  <th scope="col">note</th>
                  <th scope="col">uploaded</th>
                  <th scope="col">download</th>
                  {manage && <th scope="col"><span className="sr-only">actions</span></th>}
                </tr>
              </thead>
              <tbody>
                {pf.files.map((f) => (
                  <tr key={f.id} data-file={f.name}>
                    <td data-label="name"><code className="pf-name">{f.name}</code></td>
                    <td data-label="size" className="num">{bytes(f.size)}</td>
                    <td data-label="version">v{f.version}</td>
                    <td data-label="SHA-256" className="pf-sha">
                      <code title={f.sha256}>{shortSha(f.sha256)}</code> <CopyButton text={f.sha256} label="Copy" ariaLabel={`Copy the SHA-256 of ${f.name}`} />
                    </td>
                    <td data-label="note" className="pf-note">{f.note ?? <span className="muted">—</span>}</td>
                    <td data-label="uploaded" className="muted small">
                      {f.uploadedBy ? who(f.uploadedBy) : 'unknown'} · <time dateTime={f.uploadedAt} title={new Date(f.uploadedAt).toLocaleString()}>{timeAgo(f.uploadedAt)}</time>
                    </td>
                    <td data-label="download" className="pf-dl">
                      {f.downloadable ? (
                        <a href={api.projectFileUrl(repo, f.id)} download aria-label={`Download ${f.name}`}>Download</a>
                      ) : (
                        <span className="muted">download off</span>
                      )}
                      {manage && (
                        <label className="pf-dl-toggle small">
                          <input type="checkbox" checked={pendingDownload.get(f.id) ?? f.downloadable} disabled={busyIds.has(f.id)} onChange={(e) => void setDownload(f, e.target.checked)} aria-label={`Allow download of ${f.name}`} />
                          <span aria-hidden>Allow download</span>
                        </label>
                      )}
                    </td>
                    {manage && (
                      <td>
                        <div className="pf-actions">
                          <button
                            type="button"
                            className="btn btn--sm btn--ghost"
                            aria-label={`Replace ${f.name}`}
                            onClick={() => {
                              replaceTarget.current = f;
                              replaceRef.current?.click();
                            }}
                          >
                            Replace
                          </button>
                          <button type="button" className="btn btn--sm btn--ghost" aria-label={`Edit the note of ${f.name}`} onClick={() => (setRowErr(null), setEditing(f))}>
                            Edit note
                          </button>
                          <button type="button" className="btn btn--sm btn--ghost btn--danger" aria-label={`Delete ${f.name}`} onClick={() => (setRowErr(null), setDeleting(f))}>
                            Delete
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {me.isAdmin && <ManagersCard repo={repo} pf={pf} onChange={(next) => q.setData(() => next)} />}

      {current && (
        <UploadDialog
          key={current.key}
          item={current}
          pf={pf}
          remaining={queue.length - 1}
          onClose={() => setQueue((qs) => qs.slice(1))}
          onUpload={(req) => {
            setQueue((qs) => qs.slice(1));
            up.start(req);
          }}
        />
      )}

      {editing && (
        <NoteDialog
          repo={repo}
          file={editing}
          onClose={() => setEditing(null)}
          onSaved={(next) => {
            replaceFile(editing, next);
            setEditing(null);
            announce(`Saved the note of ${next.name}`);
          }}
        />
      )}

      <DeleteDialog
        repo={repo}
        file={deleting}
        onClose={() => setDeleting(null)}
        onDeleted={(f) => {
          setDeleting(null);
          q.setData((d) => d && { ...d, files: d.files.filter((x) => x.id !== f.id) });
          announce(`Deleted ${f.name}`);
          q.reload();
        }}
      />
    </div>
  );
}

/** An open upload on the server that this tab is not sending: choosing the same file again goes on from `received`. */
function StaleUpload({ u, onResume, onCancel }: { u: ProjectFileUpload; onResume: () => void; onCancel: () => void }) {
  const pct = u.size ? Math.floor((u.received / u.size) * 100) : 0;
  return (
    <li className="pf-upload pf-upload--interrupted">
      <div className="pf-upload-top">
        <code className="pf-upload-name">{u.name}</code>
        <span className="muted small">
          Upload interrupted · {pct}% · {bytes(u.received)} of {bytes(u.size)}
          {u.createdBy && <> · by {who(u.createdBy)}</>} · <time dateTime={u.touchedAt}>{timeAgo(u.touchedAt)}</time>
        </span>
      </div>
      <span className="pf-bar" role="progressbar" aria-label={`Upload of ${u.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-valuetext={`${pct}% · ${bytes(u.received)} of ${bytes(u.size)}`}>
        <span style={{ width: `${pct}%` }} />
      </span>
      <p className="muted small">Choose the same file again to resume. Kept until <time dateTime={u.expiresAt}>{new Date(u.expiresAt).toLocaleString()}</time>.</p>
      <div className="pf-upload-actions">
        <button type="button" className="btn btn--sm btn--primary" onClick={onResume} aria-label={`Resume the upload of ${u.name}`}>
          Resume
        </button>
        <button type="button" className="btn btn--sm btn--ghost btn--danger" onClick={onCancel} aria-label={`Cancel the upload of ${u.name}`}>
          Cancel
        </button>
      </div>
    </li>
  );
}

/** Name (editable unless replacing or resuming), note and expected SHA-256 for one chosen file, checked before any request. */
function UploadDialog({
  item,
  pf,
  remaining,
  onClose,
  onUpload,
}: {
  item: Queued;
  pf: ProjectFiles;
  remaining: number;
  onClose: () => void;
  onUpload: (req: { file: File; name: string; note: string | null; sha256: string | null }) => void;
}) {
  const fixed = item.replace?.name ?? item.resume?.name ?? null;
  const [name, setName] = useState(fixed ?? item.file.name);
  const [note, setNote] = useState(item.replace?.note ?? item.resume?.note ?? '');
  const [sha, setSha] = useState(item.resume?.sha256 ?? '');
  const [err, setErr] = useState<string | null>(null);
  const existing = pf.files.find((f) => f.name === name.trim());

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const n = name.trim();
    const expected = sha.trim() ? normalizeSha256(sha) : null;
    const problem =
      fileNameProblem(n) ??
      (sha.trim() && !expected ? 'The expected SHA-256 must be 64 hexadecimal digits.' : null) ??
      (item.resume && item.resume.size !== item.file.size
        ? `This is not the same file: the interrupted upload is ${bytes(item.resume.size)}, this file is ${bytes(item.file.size)}.`
        : null) ??
      limitProblem(pf, n, item.file.size);
    if (problem) {
      setErr(problem);
      return;
    }
    onUpload({ file: item.file, name: n, note: note.trim() || null, sha256: expected });
  };

  const title = item.replace ? `Replace ${item.replace.name}` : item.resume ? `Resume ${item.resume.name}` : 'Upload a file';
  return (
    <Dialog open onClose={onClose} title={title}>
      <form className="form" onSubmit={submit} noValidate>
        <p className="muted small">
          <code>{item.file.name}</code> · {bytes(item.file.size)}
          {remaining > 0 && <> · {remaining} more after this one</>}
        </p>
        <label htmlFor="pf-name">Name</label>
        <input
          id="pf-name"
          className="mono"
          value={name}
          readOnly={fixed !== null}
          onChange={(e) => (setName(e.target.value), setErr(null))}
          aria-describedby="pf-name-hint"
          autoComplete="off"
          spellCheck={false}
          data-autofocus={fixed === null ? true : undefined}
        />
        <span id="pf-name-hint" className="muted small">
          {existing && !item.replace ? <>Replaces the existing {existing.name} (v{existing.version}). </> : null}
          Folders with “/”, e.g. <code>vendor/sdk.zip</code>.
        </span>
        <label htmlFor="pf-note">Note <span className="muted">(optional)</span></label>
        <input id="pf-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} autoComplete="off" data-autofocus={fixed !== null ? true : undefined} />
        <label htmlFor="pf-sha">Expected SHA-256 <span className="muted">(optional)</span></label>
        <input id="pf-sha" className="mono" value={sha} onChange={(e) => (setSha(e.target.value), setErr(null))} readOnly={!!item.resume} autoComplete="off" spellCheck={false} aria-describedby="pf-sha-hint" />
        <span id="pf-sha-hint" className="muted small">The server refuses the file if its hash differs.</span>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn--primary">
            Upload
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function NoteDialog({ repo, file, onClose, onSaved }: { repo: string; file: ProjectFile; onClose: () => void; onSaved: (f: ProjectFile) => void }) {
  const [note, setNote] = useState(file.note ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      onSaved(await api.updateProjectFile(repo, file.id, { note: note.trim() }));
    } catch (x) {
      setErr((x as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open onClose={onClose} title={`Note for ${file.name}`}>
      <form className="form" onSubmit={(e) => void save(e)}>
        <label htmlFor="pf-edit-note">Note</label>
        <input id="pf-edit-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} autoComplete="off" data-autofocus />
        <span className="muted small">Leave empty to remove the note.</span>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Saving…' : 'Save note'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function DeleteDialog({ repo, file, onClose, onDeleted }: { repo: string; file: ProjectFile | null; onClose: () => void; onDeleted: (f: ProjectFile) => void }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => setErr(null), [file]);
  const remove = async () => {
    if (!file) return;
    setBusy(true);
    setErr(null);
    try {
      await api.deleteProjectFile(repo, file.id);
      onDeleted(file);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={!!file} onClose={onClose} title={`Delete ${file?.name ?? ''}?`}>
      <div className="form">
        <p>
          Delete <code>{file?.name}</code>? Runs that already started keep their copy; new runs and chats no longer get it.
        </p>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={busy} data-autofocus>
            Cancel
          </button>
          <button type="button" className="btn btn--danger" onClick={() => void remove()} disabled={busy}>
            {busy ? 'Deleting…' : 'Delete'}
          </button>
        </div>
      </div>
    </Dialog>
  );
}

/** Admins: who besides the admins may manage this project's files. */
function ManagersCard({ repo, pf, onChange }: { repo: string; pf: ProjectFiles; onChange: (next: ProjectFiles) => void }) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const save = async (usernames: string[]) => {
    setBusy(true);
    setErr(null);
    try {
      onChange(await api.setProjectFileManagers(repo, usernames));
      return true;
    } catch (e) {
      setErr((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  };
  const current = pf.managers.map((m) => m.username);
  const add = async (e: FormEvent) => {
    e.preventDefault();
    const u = name.trim();
    if (!u) return;
    if (current.includes(u)) {
      setErr(`${u} already manages the files.`);
      return;
    }
    if (await save([...current, u])) setName('');
  };
  return (
    <section className="card sbx-card pf-managers" aria-labelledby="pf-mgr-h">
      <h2 id="pf-mgr-h" className="sbx-h">Who can manage files</h2>
      <p className="muted small">Admins always can. People listed here can also upload, replace and delete this project’s files.</p>
      {pf.managers.length === 0 ? (
        <p className="muted">Only admins.</p>
      ) : (
        <ul className="pf-mgr-list">
          {pf.managers.map((m) => (
            <li key={m.username}>
              <span>
                <strong>{who(m)}</strong> {m.displayName && m.displayName !== m.username && <span className="muted">{m.username}</span>}
              </span>
              <button type="button" className="btn btn--sm btn--ghost btn--danger" disabled={busy} onClick={() => void save(current.filter((x) => x !== m.username))} aria-label={`Remove ${m.username}`}>
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      <form className="row pf-mgr-add" onSubmit={(e) => void add(e)}>
        <label htmlFor="pf-mgr-name" className="sr-only">Username</label>
        <input id="pf-mgr-name" placeholder="username" value={name} onChange={(e) => (setName(e.target.value), setErr(null))} autoComplete="off" spellCheck={false} />
        <button type="submit" className="btn btn--ghost" disabled={busy || !name.trim()}>
          Add
        </button>
      </form>
      {err && <p className="form-error" role="alert">{err}</p>}
    </section>
  );
}
