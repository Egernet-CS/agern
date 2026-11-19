import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, type Release } from '../api';
import { Dialog, Empty, ErrorBox, KindBadge, Loading, Notice, ReleaseStatusPill } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { dateTime, timeAgo, toDateTimeLocal } from '../lib/format';
import { useFeed, useFeedReconnects, useLiveRelease, useProjectReleases } from '../lib/realtime';

/** Codes after which the page's copy of the release is stale: reload it and show the server's message. */
const STALE_SCHEDULE = new Set(['release_not_scheduled', 'release_not_schedulable', 'release_scheduled']);

/** A time in the browser's time zone, machine-readable in `dateTime`. */
function LocalTime({ iso }: { iso: string }) {
  return <time dateTime={iso}>{dateTime(iso)}</time>;
}

/** One release in preparation or scheduled: its issues in build order, editable (docs/ARCHITECTURE.md §10). */
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
  const [scheduling, setScheduling] = useState(false);
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
      } else {
        if (e instanceof ApiError && STALE_SCHEDULE.has(e.code)) releaseQ.reload();
        setErr((e as Error).message);
      }
      return false;
    } finally {
      setBusy(false);
    }
  };

  const move = (index: number, delta: -1 | 1) => {
    const next = [...numbers];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    void change(() => api.reorderRelease(release.id, next));
  };
  const remove = (n: number) => void change(() => api.assignIssues(repo, [n], null));
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
        if (e instanceof ApiError && STALE_SCHEDULE.has(e.code)) releaseQ.reload();
        setErr((e as Error).message);
        setBusy(false);
        setConfirmDelete(false);
        return;
      }
    }
    markReleaseDeleted(release.id);
    nav(`/p/${repo}`);
  };

  const n = release.issues.length;
  const scheduled = release.status === 'scheduled';
  const ownerName = release.owner?.username ?? 'its owner';
  const mayChangeSchedule = scheduled && (release.owner?.username === me.username || me.isAdmin);
  const cancelSchedule = () => void change(() => api.cancelReleaseSchedule(release.id));
  const failure = release.status === 'planning' ? release.startFailure : null;
  return (
    <div className="page releasepage">
      <header className="run-head release-head">
        <div className="rh-crumbs">
          <Link to="/" className="crumb">projects /</Link>
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
        </div>
        <div className="rh-title">
          <h1>{release.name}</h1>
          <ReleaseStatusPill status={release.status} />
        </div>
        <div className="rh-actions">
          {release.status === 'planning' && (
            <button type="button" className="btn btn--ghost" onClick={() => setScheduling(true)} disabled={busy}>
              Schedule start…
            </button>
          )}
          {mayChangeSchedule && (
            <>
              <button type="button" className="btn btn--ghost" onClick={() => setScheduling(true)} disabled={busy}>
                Change time
              </button>
              <button type="button" className="btn btn--ghost" onClick={cancelSchedule} disabled={busy}>
                Cancel schedule
              </button>
            </>
          )}
          <button type="button" className="btn btn--ghost" onClick={() => setEditing(true)} disabled={busy}>
            Edit
          </button>
          <button
            type="button"
            className="btn btn--ghost btn--danger"
            onClick={() => setConfirmDelete(true)}
            disabled={busy || scheduled}
            aria-describedby={scheduled ? 'release-delete-hint' : undefined}
          >
            Delete
          </button>
        </div>
        <div className="release-about">
          {release.description && <p className="release-desc">{release.description}</p>}
          <p className="muted small">
            created by {release.createdBy.username} · {timeAgo(release.createdAt)}
          </p>
          {scheduled && release.scheduledAt ? (
            <>
              <p className="release-schedule">
                Scheduled to start <LocalTime iso={release.scheduledAt} /> by {ownerName}.
              </p>
              {release.waitingFor && (
                <p className="release-waiting" role="status">
                  Its time has come. Waiting for release{' '}
                  <Link to={`/p/${repo}/releases/${release.waitingFor.id}`}>{release.waitingFor.name}</Link> to finish building.
                </p>
              )}
              {!mayChangeSchedule && <p className="muted small">Only {ownerName} or an admin can change the schedule.</p>}
              <p id="release-delete-hint" className="muted small">Cancel the scheduled start to delete this release.</p>
            </>
          ) : (
            <p className="muted small">This release is a plan. Nothing is built until it is started.</p>
          )}
        </div>
      </header>

      {err && <p className="form-error" role="alert">{err}</p>}

      {failure && (
        <Notice tone="error" title="The scheduled start did not happen">
          <p>{failure.reason}</p>
          <p className="muted small">
            Scheduled by {failure.owner.username} · tried <LocalTime iso={failure.at} />
          </p>
        </Notice>
      )}

      <section className="release-issues" aria-labelledby="release-issues-h">
        <div className="section-head">
          <h2 id="release-issues-h">
            Issues <span className="muted">· {n} in build order</span>
          </h2>
        </div>
        {n === 0 ? (
          <p className="muted">No issues in this release yet. Add open issues here or from the issues panel.</p>
        ) : (
          <ol className="release-order">
            {release.issues.map((i, idx) => (
              <li key={i.number} className="ro-item">
                <span className="ro-pos mono" aria-hidden>{idx + 1}</span>
                <KindBadge kind={i.kind} />
                <a href={issueUrl(i.number)} target="_blank" rel="noreferrer" className="ro-title">
                  <span className="issue-num">#{i.number}</span> {i.title}
                </a>
                <span className="ro-actions">
                  <button type="button" className="btn btn--ghost btn--sm" aria-label={`Move #${i.number} up`} title="Move up" disabled={busy || idx === 0} onClick={() => move(idx, -1)}>
                    ↑
                  </button>
                  <button type="button" className="btn btn--ghost btn--sm" aria-label={`Move #${i.number} down`} title="Move down" disabled={busy || idx === n - 1} onClick={() => move(idx, 1)}>
                    ↓
                  </button>
                  <button type="button" className="btn btn--ghost btn--sm btn--danger" aria-label={`Remove #${i.number} from release`} title="Remove from release" disabled={busy} onClick={() => remove(i.number)}>
                    Remove
                  </button>
                </span>
              </li>
            ))}
          </ol>
        )}

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
      </section>

      <EditReleaseDialog release={release} open={editing} onClose={() => setEditing(false)} onSaved={(r) => noteReleases([r])} />
      <ScheduleDialog
        release={release}
        open={scheduling}
        onClose={() => setScheduling(false)}
        onSaved={(r) => noteReleases([r])}
        onStale={() => releaseQ.reload()}
      />
      <Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)} title="Delete release?">
        <div className="form">
          <p>
            Delete release “{release.name}”? This only removes the plan. Its {n} issue{n === 1 ? '' : 's'} stay open and unassigned.
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
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      onSaved(await api.updateRelease(release.id, { name: name.trim(), description }));
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
          <input id="ern" className="mono" value={name} onChange={(e) => setName(e.target.value)} required maxLength={50} autoFocus aria-describedby="ern-hint" />
          <p id="ern-hint" className="muted small">
            Letters, digits, <code>.</code>, <code>_</code>, <code>-</code>; becomes the branch <code>release/&lt;name&gt;</code>
          </p>
        </div>
        <div className="field">
          <label htmlFor="erd">
            Description <span className="muted">(optional)</span>
          </label>
          <textarea id="erd" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} />
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
          <button className="btn btn--primary" disabled={busy || !name.trim()}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** Today 22:00 local time if that is still ahead, otherwise tomorrow 22:00. */
function defaultStart(now = new Date()): Date {
  const d = new Date(now);
  d.setHours(22, 0, 0, 0);
  if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1);
  return d;
}

/** Sets the start of a release in preparation, or changes the time of a scheduled one. */
function ScheduleDialog({
  release,
  open,
  onClose,
  onSaved,
  onStale,
}: {
  release: Release;
  open: boolean;
  onClose: () => void;
  onSaved: (r: Release) => void;
  onStale: () => void;
}) {
  const changing = release.status === 'scheduled';
  const [value, setValue] = useState('');
  const [min, setMin] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  // Start from the current schedule (or the default) each time the dialog opens.
  useEffect(() => {
    if (!open) return;
    const now = new Date();
    setMin(toDateTimeLocal(now));
    setValue(toDateTimeLocal(changing && release.scheduledAt ? new Date(release.scheduledAt) : defaultStart(now)));
    setErr(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const at = new Date(value);
    if (Number.isNaN(at.getTime())) {
      setErr('Enter a date and time.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      onSaved(await api.scheduleRelease(release.id, at.toISOString()));
      onClose();
    } catch (e2) {
      if (e2 instanceof ApiError && STALE_SCHEDULE.has(e2.code)) onStale();
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onClose={onClose} title={changing ? 'Change scheduled start' : `Schedule release ${release.name}`}>
      <form className="form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="rs-at">Start at</label>
          <input
            id="rs-at"
            type="datetime-local"
            required
            min={min}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            autoFocus
            aria-describedby="rs-at-hint"
          />
          <p id="rs-at-hint" className="muted small">
            Your local time ({zone}). The release is built with your Claude and GitHub credentials. If another release of this
            project is still building at that time, this one waits for it.
          </p>
        </div>
        {err && (
          <p className="form-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="btn btn--primary" disabled={busy || !value}>
            {busy ? 'Saving…' : changing ? 'Save' : 'Schedule'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
