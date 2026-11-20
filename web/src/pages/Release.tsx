import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, isReleaseActive, releaseReadiness, type Release, type ReleaseChecks, type ReleaseIssue, type ReleaseMode } from '../api';
import { Dialog, Empty, ErrorBox, IssueStateBadge, KindBadge, Loading, Notice, ReleaseStatusPill } from '../components/ui/ui';
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

/**
 * One release: its issues in build order, editable while planned (in preparation or scheduled), built one at a time
 * once started (docs/ARCHITECTURE.md §10).
 */
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
  const [confirmStart, setConfirmStart] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [confirmRelease, setConfirmRelease] = useState(false);
  const [checking, setChecking] = useState(false);
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

  const status = release.status;
  const planned = status === 'planning' || status === 'scheduled';
  const started = !planned;
  const released = status === 'released';
  const ready = status === 'ready';
  // Building, paused or ready: the owner drives it (a ready release builds again for added or retried issues).
  const open = isReleaseActive(release) || ready;
  const ownerName = release.owner?.username ?? 'its owner';
  const isOwner = !!release.owner && release.owner.username === me.username;
  const ownerOrAdmin = isOwner || me.isAdmin;
  // What this user may do (the server checks the same rules; see docs/ARCHITECTURE.md §6 "Releases"). A released
  // release is read-only for everyone.
  const may = {
    start: status === 'planning',
    edit: !released && (planned || ownerOrAdmin),
    delete: status === 'planning' || (status === 'cancelled' && ownerOrAdmin),
    pause: status === 'building' && ownerOrAdmin,
    resume: status === 'paused' && isOwner,
    cancel: open && ownerOrAdmin,
    reorder: planned || (open && isOwner),
    add: planned || (open && isOwner),
    remove: (i: ReleaseIssue) =>
      !released && (planned || (ownerOrAdmin && (status === 'cancelled' || i.state === 'waiting' || i.state === 'held' || i.state === 'skipped'))),
    retry: (i: ReleaseIssue) => open && isOwner && (i.state === 'held' || i.state === 'skipped'),
  };
  const mayNothing = started && !released && !ownerOrAdmin;

  const move = (index: number, delta: -1 | 1) => {
    const next = [...numbers];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    void change(() => api.reorderRelease(release.id, next));
  };
  /** In a started release only waiting issues move, and only past another waiting one. */
  const canMove = (index: number, delta: -1 | 1) => {
    const other = release.issues[index + delta];
    if (!other) return false;
    return planned || (release.issues[index].state === 'waiting' && other.state === 'waiting');
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
  const doCancel = async () => {
    await change(() => api.cancelRelease(release.id));
    setConfirmCancel(false);
  };
  const doCheck = async () => {
    setChecking(true);
    await change(() => api.checkRelease(release.id));
    setChecking(false);
  };
  const doRelease = async () => {
    await change(() => api.approveRelease(release.id));
    setConfirmRelease(false);
  };
  const setMode = (mode: ReleaseMode) => void change(() => api.setReleaseMode(release.id, mode));

  const n = release.issues.length;
  const scheduled = status === 'scheduled';
  const mayChangeSchedule = scheduled && ownerOrAdmin;
  const cancelSchedule = () => void change(() => api.cancelReleaseSchedule(release.id));
  const failure = status === 'planning' ? release.startFailure : null;
  const count = (state: ReleaseIssue['state']) => release.issues.filter((i) => i.state === state).length;
  const merged = count('merged');
  const held = count('held');
  const skipped = count('skipped');
  const running = release.issues.find((i) => i.state === 'running');
  // A released release's branch is deleted on GitHub: shown as text, not linked.
  const branchUrl = project.data && release.branch && status !== 'released' ? `${project.data.htmlUrl}/tree/${release.branch}` : undefined;
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
          {may.edit && status !== 'cancelled' && (
            <button type="button" className="btn btn--ghost" onClick={() => setEditing(true)} disabled={busy}>
              Edit
            </button>
          )}
          {(may.delete || scheduled) && (
            <button
              type="button"
              className="btn btn--ghost btn--danger"
              onClick={() => setConfirmDelete(true)}
              disabled={busy || scheduled}
              aria-describedby={scheduled ? 'release-delete-hint' : undefined}
            >
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
            status === 'planning' && <p className="muted small">This release is a plan. Nothing is built until it is started.</p>
          )}
          {!released && status !== 'cancelled' && (
            <ReleaseModeField release={release} isOwner={isOwner} isAdmin={me.isAdmin} busy={busy} onChange={setMode} />
          )}
          {released && <ReleasedFacts release={release} repo={repo} />}
          {mayNothing && <p className="muted small">Only {ownerName}, who started this release, and admins can change it.</p>}
        </div>
      </header>

      {statusLine && (
        <p className={`release-status release-status--${status}`} role="status">
          {statusLine}
        </p>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}
      {(ready || released) && release.notice && (
        <p className="form-error release-notice" role="alert">
          {release.notice}
        </p>
      )}

      {ready && (
        <ReleasePullSection
          release={release}
          isOwner={isOwner}
          ownerName={ownerName}
          busy={busy}
          checking={checking}
          onCheck={() => void doCheck()}
          onApprove={() => setConfirmRelease(true)}
        />
      )}

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
          <p className="muted">
            No issues in this release yet.{may.add ? ' Add open issues here or from the issues panel.' : ''}
          </p>
        ) : (
          <ol className="release-order">
            {release.issues.map((i, idx) => {
              const movable = may.reorder && (planned || i.state === 'waiting');
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
                  {i.prUrl && (
                    <a href={i.prUrl} target="_blank" rel="noreferrer" className="ro-run small" aria-label={`pull request #${i.prNumber} of #${i.number}`}>
                      PR #{i.prNumber}
                    </a>
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
      <ScheduleDialog
        release={release}
        open={scheduling}
        onClose={() => setScheduling(false)}
        onSaved={(r) => noteReleases([r])}
        onStale={() => releaseQ.reload()}
      />
      <StartReleaseDialog
        release={release}
        defaultBranch={project.data?.defaultBranch}
        open={confirmStart}
        onClose={() => setConfirmStart(false)}
        onStarted={(r) => noteReleases([r])}
      />
      <ApproveReleaseDialog release={release} open={confirmRelease} busy={busy} onClose={() => setConfirmRelease(false)} onConfirm={() => void doRelease()} />
      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} title="Cancel release?">
        <div className="form">
          <p>
            {ready ? (
              <>
                Cancel release “{release.name}”? Agern will not merge it.{release.prNumber ? ` Pull request #${release.prNumber} and the` : ' The'} branch{' '}
                <code>{release.branch ?? `release/${release.name}`}</code> stay on GitHub as they are.
              </>
            ) : (
              <>
                Cancel release “{release.name}”? The running issue is stopped and no more are built. What is already merged stays on{' '}
                <code>{release.branch ?? `release/${release.name}`}</code>.
              </>
            )}
          </p>
          <div className="dialog-actions">
            <button type="button" className="btn btn--ghost" onClick={() => setConfirmCancel(false)} data-autofocus>
              {ready ? 'Keep it' : 'Keep building'}
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
    case 'scheduled':
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
    default:
      // `ready` and `released` have their own sections.
      return null;
  }
}

const shortSha = (sha: string) => sha.slice(0, 7);

/** How a release is (or was) merged into the default branch, in words. */
function methodWords(release: Release, target: string, past: boolean): string {
  switch (release.mergeMethod) {
    case 'rebase':
      return past ? `Rebased onto ${target}, one commit per issue` : `Approval merges it by rebasing: one commit per issue on ${target}.`;
    case 'merge':
      return past
        ? `Merged into ${target} with a merge commit, one commit per issue`
        : `Approval merges it with a merge commit: one commit per issue on ${target}, plus the merge commit.`;
    default:
      return past ? `Merged into ${target}` : '';
  }
}

/** The status line of a `ready` release: why it can or cannot be released now. */
function readyLine(release: Release, target: string) {
  const readiness = releaseReadiness(release);
  const branch = release.branch ?? `release/${release.name}`;
  if (readiness === 'incomplete') {
    const held = release.issues.filter((i) => i.state === 'held');
    return `Incomplete: ${held.length} issue${held.length === 1 ? ' is' : 's are'} held (${held.map((i) => `#${i.number}`).join(', ')}). Approve or retry their runs, or move them out, before releasing.`;
  }
  if (readiness === 'empty') return 'Nothing was merged into the release branch, so there is nothing to release.';
  if (release.mergeState === 'conflict')
    return (
      <>
        <code>{branch}</code> no longer merges cleanly into {target}. Agern will not merge it. Resolve the conflicts on the release branch, then check
        again.
      </>
    );
  if (release.mergeMethod === 'unavailable')
    return 'This repository only allows squash merges. Allow rebase merging or merge commits in its GitHub settings to release.';
  if (release.mergeState === 'clean') return `Ready to release. Merges cleanly into ${target}.`;
  return 'Ready to release. Mergeability has not been confirmed yet.';
}

/** `ready`: the release pull request, whether it can be merged, and (owner only) "Check again" / "Approve and release". */
function ReleasePullSection({
  release,
  isOwner,
  ownerName,
  busy,
  checking,
  onCheck,
  onApprove,
}: {
  release: Release;
  isOwner: boolean;
  ownerName: string;
  busy: boolean;
  checking: boolean;
  onCheck: () => void;
  onApprove: () => void;
}) {
  const target = release.targetBranch ?? 'the default branch';
  const readiness = releaseReadiness(release);
  const blocked = readiness !== 'complete' || release.mergeState === 'conflict' || release.mergeMethod === 'unavailable';
  const approvable = !blocked && release.prNumber !== null;
  const method = approvable ? methodWords(release, target, false) : '';
  const auto = release.mode === 'automatic' && approvable ? automaticLine(release.checks) : null;
  return (
    <section className="release-pr" aria-labelledby="release-pr-h">
      <div className="section-head">
        <h2 id="release-pr-h">Release pull request</h2>
      </div>
      <p className="release-pr-link">
        {release.prNumber !== null && release.prUrl ? (
          <>
            <a href={release.prUrl} target="_blank" rel="noreferrer" className="mono">
              #{release.prNumber}
            </a>
            <span className="mono small muted">
              {release.branch ?? `release/${release.name}`} → {target}
            </span>
          </>
        ) : (
          <span className="muted">No pull request yet.</span>
        )}
      </p>
      <p id="release-ready-status" className={`release-status release-status--${blocked ? 'blocked' : 'ready'}`} role="status">
        {readyLine(release, target)}
      </p>
      {release.checks && <ChecksLine checks={release.checks} />}
      {method && <p className="muted small">{method}</p>}
      {auto && <p className="muted small release-auto">{auto}</p>}
      {isOwner ? (
        <div className="release-pr-actions">
          <button type="button" className="btn" onClick={onCheck} disabled={busy}>
            {checking ? 'Checking…' : 'Check again'}
          </button>
          <button type="button" className="btn btn--primary" onClick={onApprove} disabled={busy || !approvable} aria-describedby="release-ready-status">
            Approve and release
          </button>
        </div>
      ) : (
        <p className="muted small">Only {ownerName} can approve this release.</p>
      )}
    </section>
  );
}

const MODE_LABEL: Record<ReleaseMode, string> = { manual: 'Manual approval', automatic: 'Automatic' };

/**
 * The release mode, and (for whoever may change it) a select. Planned: anyone; scheduled or started: automatic is the
 * owner's alone (it merges with the owner's token), manual also an admin's (it only stops something).
 */
function ReleaseModeField({
  release,
  isOwner,
  isAdmin,
  busy,
  onChange,
}: {
  release: Release;
  isOwner: boolean;
  isAdmin: boolean;
  busy: boolean;
  onChange: (mode: ReleaseMode) => void;
}) {
  const planning = release.status === 'planning';
  const mayAutomatic = planning || isOwner;
  const mayManual = planning || isOwner || isAdmin;
  const mayChange = release.mode === 'automatic' ? mayManual : mayAutomatic;
  const who = release.owner ? release.owner.username : 'whoever starts it';
  const hint =
    release.mode === 'automatic'
      ? `Once everything is built, merges cleanly and all checks on the release pull request have passed, Agern merges it with ${who}'s GitHub token. Held issues, conflicts and failed checks stop it.`
      : `It waits as “ready to release” until ${who} approves it.`;
  return (
    <div className="release-mode">
      {mayChange ? (
        <label className="release-mode-pick small">
          Release mode
          <select value={release.mode} onChange={(e) => onChange(e.target.value as ReleaseMode)} disabled={busy} aria-describedby="release-mode-hint">
            <option value="manual">{MODE_LABEL.manual}</option>
            <option value="automatic" disabled={!mayAutomatic}>
              {MODE_LABEL.automatic}
            </option>
          </select>
        </label>
      ) : (
        <span className="small">
          Release mode: <strong>{MODE_LABEL[release.mode]}</strong>
        </span>
      )}
      <span id="release-mode-hint" className="muted small">
        {hint}
      </span>
    </div>
  );
}

const CHECKS_LABEL: Record<ReleaseChecks['state'], string> = {
  unknown: 'checks unknown',
  none: 'no checks',
  pending: 'checks pending',
  passed: 'checks passed',
  failed: 'checks failed',
};
const CHECKS_TONE: Record<ReleaseChecks['state'], string> = { unknown: 'idle', none: 'idle', pending: 'active', passed: 'pass', failed: 'fail' };

/** The checks on the release pull request's head commit (GitHub Actions and other status checks). */
function ChecksLine({ checks }: { checks: ReleaseChecks }) {
  const detail = checks.state === 'none' ? 'This pull request has no checks; that counts as green.' : checks.detail;
  return (
    <p className={`release-checks release-checks--${checks.state}`}>
      <span className={`pill pill--${CHECKS_TONE[checks.state]}`}>
        <span className="pill-dot" aria-hidden />
        {CHECKS_LABEL[checks.state]}
      </span>
      {detail && <span className="muted small">{detail}</span>}
    </p>
  );
}

/** What an automatic, approvable release is waiting for (failed or unreadable checks also show as the notice). */
function automaticLine(checks: ReleaseChecks | null): string {
  switch (checks?.state) {
    case 'failed':
      return 'Set to release automatically, but a check failed: Agern will not merge it until the checks pass.';
    case 'none':
    case 'passed':
      return 'Set to release automatically: Agern is merging it.';
    default:
      return 'Set to release automatically: Agern merges it once all checks have passed.';
  }
}

/** `released`: who released it and when, the pull request and the commit. */
function ReleasedFacts({ release, repo }: { release: Release; repo: string }) {
  const target = release.targetBranch ?? 'the default branch';
  return (
    <div className="release-released">
      <p>
        {!release.releasedBy
          ? `Merged on GitHub ${timeAgo(release.releasedAt)}`
          : release.releasedAutomatically
            ? `Released automatically ${timeAgo(release.releasedAt)} for ${release.releasedBy.username}`
            : `Released ${timeAgo(release.releasedAt)} by ${release.releasedBy.username}`}
        {release.prNumber !== null && (
          <>
            {' '}· pull request{' '}
            {release.prUrl ? (
              <a href={release.prUrl} target="_blank" rel="noreferrer" className="mono">
                #{release.prNumber}
              </a>
            ) : (
              <span className="mono">#{release.prNumber}</span>
            )}
          </>
        )}
      </p>
      <p className="muted small">
        {methodWords(release, target, true)}
        {release.mergeCommit && (
          <>
            {' '}· {target} at <code title={release.mergeCommit}>{shortSha(release.mergeCommit)}</code>
          </>
        )}
        . Its issues were closed and the branch {release.branch ?? `release/${release.name}`} was deleted. <Link to={`/p/${repo}`}>Back to {repo}</Link>
      </p>
    </div>
  );
}

/** Confirms the release merge; refusals show on the page (the dialog closes either way). */
function ApproveReleaseDialog({
  release,
  open,
  busy,
  onClose,
  onConfirm,
}: {
  release: Release;
  open: boolean;
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const target = release.targetBranch ?? 'the default branch';
  const n = release.issues.filter((i) => i.state === 'merged').length;
  const issues = `${n} issue${n === 1 ? '' : 's'}`;
  const how =
    release.mergeMethod === 'merge' ? `with a merge commit: ${issues}, one commit each, plus the merge commit` : `by rebasing: ${issues}, one commit each`;
  // Approval is never blocked by checks (the owner decides), but it says what it would override.
  const checks = release.checks;
  const warning =
    checks?.state === 'failed'
      ? `Checks failed on this pull request (${checks.detail ?? 'a check failed'}). Approving merges it anyway.`
      : checks?.state === 'pending'
        ? `Some checks have not finished yet (${checks.detail ?? 'pending'}). Approving merges it without waiting for them.`
        : checks?.state === 'unknown'
          ? 'The checks on this pull request could not be read. Approving merges it without them.'
          : null;
  return (
    <Dialog open={open} onClose={onClose} title={`Release ${release.name}?`}>
      <div className="form">
        <p>
          This merges pull request #{release.prNumber} into {target} {how}. The issues are closed and the branch release/{release.name} is deleted. This
          cannot be undone.
        </p>
        {warning && <p className="release-warning">{warning}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={busy} data-autofocus>
            Cancel
          </button>
          <button type="button" className="btn btn--primary" onClick={onConfirm} disabled={busy}>
            {busy ? 'Releasing…' : 'Approve and release'}
          </button>
        </div>
      </div>
    </Dialog>
  );
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
        <p>
          {release.mode === 'automatic'
            ? 'It is set to release automatically: once everything is built, merges cleanly and all checks have passed, Agern merges it into the default branch with your GitHub token, without asking you.'
            : 'When everything is built, it waits for your approval before anything reaches the default branch.'}
        </p>
        {err && (
          <p className="form-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn--ghost" onClick={onClose} data-autofocus>
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
  const locked = release.status !== 'planning' && release.status !== 'scheduled';
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
            {release.mode === 'automatic' && ' It is set to release automatically: once built, clean and green, Agern merges it with your GitHub token.'}
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
