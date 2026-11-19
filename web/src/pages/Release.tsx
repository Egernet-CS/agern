import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, isReleaseActive, type Release, type ReleaseIssue } from '../api';
import { Dialog, Empty, ErrorBox, IssueStateBadge, KindBadge, Loading, ReleaseStatusPill } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { timeAgo } from '../lib/format';
import { useFeed, useFeedReconnects, useLiveRelease, useProjectReleases } from '../lib/realtime';

/** One release: its issues in build order, editable while planned, built one at a time once started (docs/ARCHITECTURE.md §10). */
export function ReleasePage() {
  const { repo = '', releaseId = '' } = useParams();
  const nav = useNavigate();
  const me = useMe();
  const { noteReleases, markReleaseDeleted } = useFeed();
  const reconnects = useFeedReconnects();
  const releaseQ = useAsync(() => api.release(releaseId), [releaseId, reconnects]);
  const project = useAsync(() => api.project(repo), [repo]);
  const openIssues = useAsync(() => api.issues(repo, 'open'), [repo, reconnects]);
  const all = useProjectReleases(repo);
  const { release, deleted } = useLiveRelease(releaseQ.data);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [toAdd, setToAdd] = useState('');
  const [editing, setEditing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmStart, setConfirmStart] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  // Set before this user's own delete request, so they are not told "This release was deleted".
  const deletedByMe = useRef(false);

  useEffect(() => {
    if (release) document.title = `${release.name} · ${repo} · Agern`;
  }, [release, repo]);

  // The release a planned issue is in, for the "(in <other>)" hint when adding.
  const releaseOf = useMemo(() => {
    const m = new Map<number, Release>();
    for (const r of all.releases) for (const i of r.issues) m.set(i.number, r);
    return m;
  }, [all.releases]);

  if (deleted && !deletedByMe.current)
    return (
      <div className="page page--narrow">
        <Empty title="This release was deleted." art={false}>
          Its issues stay open on GitHub. <Link to={`/p/${repo}`}>Back to {repo}</Link>
        </Empty>
      </div>
    );
  if (releaseQ.error || (release && release.repo !== repo))
    return (
      <div className="page">
        {!releaseQ.error || (releaseQ.error instanceof ApiError && releaseQ.error.status === 404) ? (
          <Empty title="Release not found">
            It may have been deleted, or belong to a repository you can’t read. <Link to={`/p/${repo}`}>Back to {repo}</Link>
          </Empty>
        ) : (
          <ErrorBox error={releaseQ.error} retry={releaseQ.reload} />
        )}
      </div>
    );
  if (!release) return <div className="page"><Loading label="Loading release" /></div>;

  const numbers = release.issues.map((i) => i.number);
  const issueUrl = (n: number) => (project.data ? `${project.data.htmlUrl}/issues/${n}` : undefined);
  const candidates = (openIssues.data ?? []).filter((i) => !numbers.includes(i.number));

  /** Runs one change; the answer goes into the shared live store so every view shows it at once. */
  const change = async (fn: () => Promise<Release | Release[]>) => {
    setBusy(true);
    setErr(null);
    try {
      const res = await fn();
      noteReleases(Array.isArray(res) ? res : [res]);
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.code === 'release_issues_changed') {
        releaseQ.reload();
        setErr('The release changed in the meantime; try again.');
      } else setErr((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const status = release.status;
  const started = status !== 'planning';
  const active = isReleaseActive(release);
  const ownerName = release.owner?.username ?? '';
  const isOwner = !!release.owner && release.owner.username === me.username;
  const ownerOrAdmin = isOwner || me.isAdmin;
  // What this user may do (the server checks the same rules; see docs/ARCHITECTURE.md §6 "Releases").
  const may = {
    start: status === 'planning',
    edit: !started || ownerOrAdmin,
    delete: status === 'planning' || (status === 'cancelled' && ownerOrAdmin),
    pause: status === 'building' && ownerOrAdmin,
    resume: status === 'paused' && isOwner,
    cancel: active && ownerOrAdmin,
    reorder: status === 'planning' || (active && isOwner),
    add: status === 'planning' || (active && isOwner),
    remove: (i: ReleaseIssue) => status === 'planning' || (ownerOrAdmin && (status === 'cancelled' || i.state === 'waiting' || i.state === 'held' || i.state === 'skipped')),
    retry: (i: ReleaseIssue) => active && isOwner && (i.state === 'held' || i.state === 'skipped'),
  };
  const mayNothing = started && !ownerOrAdmin;

  const move = (index: number, delta: -1 | 1) => {
    const next = [...numbers];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    void change(() => api.reorderRelease(release.id, next));
  };
  /** In a started release only waiting issues move, and only past another waiting one. */
  const canMove = (index: number, delta: -1 | 1) => {
    const other = release.issues[index + delta];
    if (!other) return false;
    return status === 'planning' || (release.issues[index].state === 'waiting' && other.state === 'waiting');
  };
  const remove = (n: number) => void change(() => api.assignIssues(repo, [n], null));
  const retry = (n: number) => void change(() => api.retryReleaseIssue(release.id, n));
  const add = async (e: FormEvent) => {
    e.preventDefault();
    const n = Number(toAdd);
    if (!n) return;
    if (await change(() => api.assignIssues(repo, [n], release.id))) setToAdd('');
  };
  const doDelete = async () => {
    setBusy(true);
    setErr(null);
    deletedByMe.current = true;
    try {
      await api.deleteRelease(release.id);
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) {
        deletedByMe.current = false;
        setErr((e as Error).message);
        setBusy(false);
        setConfirmDelete(false);
        return;
      }
    }
    markReleaseDeleted(release.id);
    nav(`/p/${repo}`);
  };
  const doCancel = async () => {
    await change(() => api.cancelRelease(release.id));
    setConfirmCancel(false);
  };

  const n = release.issues.length;
  const count = (state: ReleaseIssue['state']) => release.issues.filter((i) => i.state === state).length;
  const merged = count('merged');
  const held = count('held');
  const skipped = count('skipped');
  const running = release.issues.find((i) => i.state === 'running');
  const branchUrl = project.data && release.branch ? `${project.data.htmlUrl}/tree/${release.branch}` : undefined;
  const statusLine = releaseStatusLine(release, running, held);

  return (
    <div className="page releasepage">
      <header className="run-head release-head">
        <div className="rh-crumbs">
          <Link to="/" className="crumb">projects /</Link>
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
        </div>
        <div className="rh-title">
          <h1>{release.name}</h1>
          <ReleaseStatusPill status={status} />
        </div>
        <div className="rh-actions">
          {may.start && (
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => setConfirmStart(true)}
              disabled={busy || n === 0}
              aria-describedby={n === 0 ? 'release-start-hint' : undefined}
            >
              ▶ Start release
            </button>
          )}
          {may.start && n === 0 && (
            <span id="release-start-hint" className="muted small">Add an issue first</span>
          )}
          {may.pause && (
            <button type="button" className="btn" onClick={() => void change(() => api.pauseRelease(release.id))} disabled={busy}>
              Pause
            </button>
          )}
          {may.resume && (
            <button type="button" className="btn btn--primary" onClick={() => void change(() => api.resumeRelease(release.id))} disabled={busy}>
              Resume
            </button>
          )}
          {may.cancel && (
            <button type="button" className="btn btn--ghost btn--danger" onClick={() => setConfirmCancel(true)} disabled={busy}>
              Cancel release
            </button>
          )}
          {may.edit && status !== 'cancelled' && (
            <button type="button" className="btn btn--ghost" onClick={() => setEditing(true)} disabled={busy}>
              Edit
            </button>
          )}
          {may.delete && (
            <button type="button" className="btn btn--ghost btn--danger" onClick={() => setConfirmDelete(true)} disabled={busy}>
              Delete
            </button>
          )}
        </div>
        <div className="release-about">
          {release.description && <p className="release-desc">{release.description}</p>}
          <p className="muted small">
            created by {release.createdBy.username} · {timeAgo(release.createdAt)}
          </p>
          {started && (
            <>
              <p className="muted small">
                started by {ownerName} · {timeAgo(release.startedAt)}
              </p>
              <p className="release-facts small">
                {release.branch && (
                  <span className="mono">
                    {branchUrl ? (
                      <a href={branchUrl} target="_blank" rel="noreferrer">{release.branch}</a>
                    ) : (
                      release.branch
                    )}
                  </span>
                )}
                <span className="release-progress">
                  {merged} of {n} merged
                  {held > 0 && ` · ${held} held`}
                  {skipped > 0 && ` · ${skipped} skipped`}
                </span>
              </p>
            </>
          )}
          {status === 'planning' && <p className="muted small">This release is a plan. Nothing is built until it is started.</p>}
          {mayNothing && <p className="muted small">Only {ownerName}, who started this release, and admins can change it.</p>}
        </div>
      </header>

      {statusLine && (
        <p className={`release-status release-status--${status}`} role="status">
          {statusLine}
        </p>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}

      <section className="release-issues" aria-labelledby="release-issues-h">
        <div className="section-head">
          <h2 id="release-issues-h">
            Issues <span className="muted">· {n} in build order</span>
          </h2>
        </div>
        {n === 0 ? (
          <p className="muted">
            No issues in this release yet.{may.add ? ' Add open issues here or from the issues panel.' : ''}
          </p>
        ) : (
          <ol className="release-order">
            {release.issues.map((i, idx) => {
              const movable = may.reorder && (status === 'planning' || i.state === 'waiting');
              return (
                <li key={i.number} className={`ro-item ro-item--${started ? i.state : 'planned'}`}>
                  <span className="ro-pos mono" aria-hidden>{idx + 1}</span>
                  <KindBadge kind={i.kind} />
                  <span className="ro-main">
                    <a href={issueUrl(i.number)} target="_blank" rel="noreferrer" className="ro-title">
                      <span className="issue-num">#{i.number}</span> {i.title}
                    </a>
                    {started && (i.state === 'held' || i.state === 'skipped' || i.state === 'waiting') && i.note && (
                      <span className="ro-note small">{i.note}</span>
                    )}
                  </span>
                  {started && <IssueStateBadge state={i.state} />}
                  {i.runId && (
                    <Link to={`/p/${repo}/runs/${i.runId}`} className="ro-run small" aria-label={`run of #${i.number}`}>
                      run
                    </Link>
                  )}
                  <span className="ro-actions">
                    {may.retry(i) && (
                      <button type="button" className="btn btn--ghost btn--sm" aria-label={`Retry #${i.number}`} title="Build it again" disabled={busy} onClick={() => retry(i.number)}>
                        Retry
                      </button>
                    )}
                    {movable && (
                      <>
                        <button type="button" className="btn btn--ghost btn--sm" aria-label={`Move #${i.number} up`} title="Move up" disabled={busy || !canMove(idx, -1)} onClick={() => move(idx, -1)}>
                          ↑
                        </button>
                        <button type="button" className="btn btn--ghost btn--sm" aria-label={`Move #${i.number} down`} title="Move down" disabled={busy || !canMove(idx, 1)} onClick={() => move(idx, 1)}>
                          ↓
                        </button>
                      </>
                    )}
                    {may.remove(i) && (
                      <button type="button" className="btn btn--ghost btn--sm btn--danger" aria-label={`Remove #${i.number} from release`} title="Remove from release" disabled={busy} onClick={() => remove(i.number)}>
                        Remove
                      </button>
                    )}
                  </span>
                </li>
              );
            })}
          </ol>
        )}

        {may.add && (
          <form className="release-add" onSubmit={(e) => void add(e)}>
            <label htmlFor="release-add-pick" className="field-label">Add an open issue</label>
            <div className="release-add-row">
              <select id="release-add-pick" value={toAdd} onChange={(e) => setToAdd(e.target.value)} disabled={busy || candidates.length === 0}>
                {candidates.length === 0 ? (
                  <option value="">{openIssues.loading && !openIssues.data ? 'Loading issues…' : 'No open issues to add'}</option>
                ) : (
                  <>
                    <option value="" disabled>Choose an issue…</option>
                    {candidates.map((i) => {
                      const other = releaseOf.get(i.number);
                      return (
                        <option key={i.number} value={i.number}>
                          #{i.number} {i.title}{other && other.id !== release.id ? ` (in ${other.name})` : ''}
                        </option>
                      );
                    })}
                  </>
                )}
              </select>
              <button className="btn" disabled={busy || !toAdd}>Add</button>
            </div>
            {openIssues.error && <p className="form-error">{openIssues.error.message}</p>}
          </form>
        )}
      </section>

      <EditReleaseDialog release={release} open={editing} onClose={() => setEditing(false)} onSaved={(r) => noteReleases([r])} />
      <StartReleaseDialog
        release={release}
        defaultBranch={project.data?.defaultBranch}
        open={confirmStart}
        onClose={() => setConfirmStart(false)}
        onStarted={(r) => noteReleases([r])}
      />
      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} title="Cancel release?">
        <div className="form">
          <p>
            Cancel release “{release.name}”? The running issue is stopped and no more are built. What is already merged stays on{' '}
            <code>{release.branch ?? `release/${release.name}`}</code>.
          </p>
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setConfirmCancel(false)} autoFocus>
              Keep building
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void doCancel()} disabled={busy}>
              {busy ? 'Cancelling…' : 'Cancel release'}
            </button>
          </div>
        </div>
      </Dialog>
      <Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)} title="Delete release?">
        <div className="form">
          <p>
            {status === 'planning' ? (
              <>Delete release “{release.name}”? This only removes the plan. Its {n} issue{n === 1 ? '' : 's'} stay open and unassigned.</>
            ) : (
              <>
                Delete release “{release.name}”? Its {n} issue{n === 1 ? '' : 's'} stay open and unassigned; the branch{' '}
                <code>{release.branch ?? `release/${release.name}`}</code> stays on GitHub.
              </>
            )}
          </p>
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setConfirmDelete(false)} autoFocus>
              Cancel
            </button>
            <button type="button" className="btn btn--danger" onClick={() => void doDelete()} disabled={busy}>
              {busy ? 'Deleting…' : 'Delete release'}
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

/** "at 14:05" today, else with the date. */
function clockTime(isoTs: string): string {
  const d = new Date(isoTs);
  const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === new Date().toDateString()) return `at ${time}`;
  return `on ${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} at ${time}`;
}

/** Ends a note with a full stop unless it already ends a sentence. */
const sentence = (text: string) => (/[.!?]$/.test(text.trim()) ? text.trim() : `${text.trim()}.`);

/** What a started release is doing, as one plain sentence (null while planning). */
function releaseStatusLine(release: Release, running: ReleaseIssue | undefined, held: number): string | null {
  const branch = release.branch ?? `release/${release.name}`;
  switch (release.status) {
    case 'planning':
      return null;
    case 'building': {
      if (running) return `Building #${running.number} ${running.title}.`;
      const next = release.issues.find((i) => i.state === 'waiting');
      if (next) return `Starting #${next.number} ${next.title}.`;
      return `Nothing left to build.${held > 0 ? ` ${held} held issue${held === 1 ? ' needs' : 's need'} attention.` : ''}`;
    }
    case 'paused': {
      const p = release.pause;
      const note = sentence(p?.note ?? 'Paused');
      if (p?.reason === 'usage_limit') return `${note}${p.resumeAt ? ` Resumes automatically ${clockTime(p.resumeAt)}.` : ''}`;
      if (p?.reason === 'credentials') return note;
      return `${note}${running ? ` #${running.number} is finishing; no further issue starts.` : ''}`;
    }
    case 'cancelled':
      return `Cancelled ${timeAgo(release.endedAt)}. What was merged stays on ${branch}.`;
  }
}

/** Confirms starting a release; server refusals show inside the dialog. */
function StartReleaseDialog({
  release,
  defaultBranch,
  open,
  onClose,
  onStarted,
}: {
  release: Release;
  defaultBranch: string | undefined;
  open: boolean;
  onClose: () => void;
  onStarted: (r: Release) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (open) setErr(null);
  }, [open]);
  const start = async () => {
    setBusy(true);
    setErr(null);
    try {
      onStarted(await api.startRelease(release.id));
      onClose();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const n = release.issues.length;
  return (
    <Dialog open={open} onClose={onClose} title="Start release?">
      <div className="form">
        <p>
          Agern creates the branch <code>release/{release.name}</code> from <code>{defaultBranch ?? 'the default branch'}</code> and builds its {n} issue
          {n === 1 ? '' : 's'} one at a time with your Claude and GitHub credentials. Approved issues are merged into the release branch and stay open.
        </p>
        {err && (
          <p className="form-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} autoFocus>
            Cancel
          </button>
          <button type="button" className="btn btn--primary" onClick={() => void start()} disabled={busy}>
            {busy ? 'Starting…' : 'Start release'}
          </button>
        </div>
      </div>
    </Dialog>
  );
}

function EditReleaseDialog({ release, open, onClose, onSaved }: { release: Release; open: boolean; onClose: () => void; onSaved: (r: Release) => void }) {
  const [name, setName] = useState(release.name);
  const [description, setDescription] = useState(release.description);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Start from the current values each time the dialog opens.
  useEffect(() => {
    if (!open) return;
    setName(release.name);
    setDescription(release.description);
    setErr(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const locked = release.status !== 'planning';
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      onSaved(await api.updateRelease(release.id, { name: locked ? release.name : name.trim(), description }));
      onClose();
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onClose={onClose} title="Edit release">
      <form className="form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="ern">Name</label>
          <input id="ern" className="mono" value={locked ? release.name : name} onChange={(e) => setName(e.target.value)} required maxLength={50} autoFocus={!locked} disabled={locked} aria-describedby="ern-hint" />
          <p id="ern-hint" className="muted small">
            {locked ? (
              'The name is fixed once the release is started'
            ) : (
              <>
                Letters, digits, <code>.</code>, <code>_</code>, <code>-</code>; becomes the branch <code>release/&lt;name&gt;</code>
              </>
            )}
          </p>
        </div>
        <div className="field">
          <label htmlFor="erd">
            Description <span className="muted">(optional)</span>
          </label>
          <textarea id="erd" rows={4} autoFocus={locked} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} />
        </div>
        {err && (
          <p className="form-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn--primary" disabled={busy || (!locked && !name.trim())}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
