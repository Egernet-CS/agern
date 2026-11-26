import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError, BLOCKING_CHECK_STATES, isRunActive, isRunWorking, isSyncRun, runRef, type AgentSession, type GeneratedImage, type Run, type RunCheckItem, type Screenshot, type StageRole } from '../api';
import { ChatView } from '../components/chat/ChatView';
import { RunSandboxLine } from '../components/sandbox/SandboxBits';
import { RunSandboxVmLine } from '../components/sandboxvm/SandboxVmBits';
import { SandboxVmPreviewPanel } from '../components/sandboxvm/SandboxVmPreview';
import { Markdown } from '../components/chat/Markdown';
import { approverEscalation, buildNodes, humanSelectable, isFollowUp, latestStage, REVIEWERS, ROLE_BLURB, roundName, rounds, stagesFor, type NodeId } from '../components/pipeline/model';
import { Prompt } from '../components/chat/Prompt';
import { PipelineDiagram } from '../components/pipeline/PipelineDiagram';
import { RetryDialog } from '../components/ui/RetryDialog';
import { MERGE_POLICY_LABEL } from '../components/ui/mergePolicy';
import { GIVE_UP_RUN_TEXT, GiveUpDialog, NetworkWaitBanner } from '../components/ui/NetworkWait';
import { Dialog, Empty, ErrorBox, KindBadge, Loading, RunStatusPill, Verdict } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { between, bytes, cost, dateTime, modelLabel, shortId, timeAgo } from '../lib/format';
import { useLiveRun, useLiveSessions } from '../lib/realtime';
import { useNow } from '../lib/useNow';

const ROLE_ORDER: StageRole[] = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver'];

function defaultNode(run: Run): NodeId {
  const running = run.stages.find((s) => s.status === 'running');
  if (running) return running.role;
  if (run.status === 'needs_human') return run.conversation.sessionId ? 'human' : 'approver';
  const last = [...run.stages].sort((a, b) => (a.startedAt ?? '').localeCompare(b.startedAt ?? '')).pop();
  return last?.role ?? 'issue';
}

export function RunPage() {
  const { repo = '', runId = '' } = useParams();
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const runQ = useAsync(() => api.run(runId), [runId]);
  const sessQ = useAsync(() => api.projectSessions(repo), [repo]);
  const run = useLiveRun(runQ.data);
  const sessions = useLiveSessions(sessQ.data, (s) => s.runId === runId);
  const sessionMap = useMemo(() => new Map(sessions.map((s) => [s.id, s])), [sessions]);
  const [busy, setBusy] = useState<'approve' | 'cancel' | 'retry-now' | 'give-up' | 'merge-anyway' | null>(null);
  const [confirmGiveUp, setConfirmGiveUp] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [confirmApprove, setConfirmApprove] = useState(false);
  const [confirmMergeAnyway, setConfirmMergeAnyway] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const navigate = useNavigate();
  const [err, setErr] = useState<string | null>(null);
  const active = run ? isRunActive(run) : false;
  const now = useNow(1000, run ? isRunWorking(run) : false);

  useEffect(() => {
    if (run) document.title = `${isSyncRun(run) ? '' : `#${run.issueNumber} `}${run.issueTitle} · ${repo} · Agern`;
  }, [run, repo]);

  // Sessions created after first load: refetch when the run references one we don't know.
  useEffect(() => {
    if (run?.stages.some((s) => s.sessionId && !sessionMap.has(s.sessionId)) && !sessQ.loading) sessQ.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.stages.length]);

  // The advisor session appears once the owner has sent a first message: fetch it when the run names one we don't know.
  useEffect(() => {
    const sid = run?.conversation.sessionId;
    if (sid && !sessionMap.has(sid) && !sessQ.loading) sessQ.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.conversation.sessionId]);

  const focusComposer = useCallback(() => {
    // The composer mounts focused; when it is already there, focus it again.
    setTimeout(() => document.querySelector<HTMLTextAreaElement>('.conv-panel textarea')?.focus(), 0);
  }, []);

  if (runQ.error)
    return (
      <div className="page">
        {runQ.error instanceof ApiError && runQ.error.status === 404 ? <Empty title="Run not found">It may belong to a repository you can’t read. <Link to={`/p/${repo}`}>Back to {repo}</Link></Empty> : <ErrorBox error={runQ.error} retry={runQ.reload} />}
      </div>
    );
  if (!run) return <div className="page"><Loading label="Loading run" /></div>;

  const nodes = buildNodes(run, sessionMap);
  const selParam = params.get('stage') as NodeId | null;
  const selected: NodeId =
    selParam && (selParam === 'issue' || ROLE_ORDER.includes(selParam as StageRole) || (selParam === 'human' && humanSelectable(run))) ? selParam : defaultNode(run);
  const select = (id: NodeId) => {
    params.set('stage', id);
    params.delete('round');
    setParams(params, { replace: true });
  };
  const isOwner = run.owner.username === me.username;
  // The merge policy stored on the run when it started (ARCHITECTURE §6 "Merge policy"); `github` runs are never merged by Agern.
  const policy = run.mergePolicy ?? 'automatic';
  const onGitHub = policy === 'github';
  const prLink = run.prUrl ? (
    <a href={run.prUrl} target="_blank" rel="noreferrer">{run.targetRepo ? `${run.targetRepo}#${run.prNumber}` : `pull request #${run.prNumber}`} ↗</a>
  ) : (
    'the pull request'
  );
  // The approver escalated: merging overrides it, so the owner confirms with its reason in front of them.
  const escalation = approverEscalation(run);
  const advisor = run.conversation.sessionId ? sessionMap.get(run.conversation.sessionId) : undefined;
  const advisorWorking = advisor?.status === 'starting' || advisor?.status === 'running';
  const approveHint =
    run.conversation.review === 'running'
      ? 'Changes from the conversation are being reviewed.'
      : run.conversation.review === 'required'
        ? 'Changes from the conversation have not been reviewed yet. Review them again first.'
        : advisorWorking
          ? 'The agent is still working.'
          : null;

  // Checks before the merge (docs/ARCHITECTURE.md §6 "Checks before a merge"): the owner may merge anyway while they block it.
  const checks = run.checks ?? null;
  const checksBlock = checks !== null && BLOCKING_CHECK_STATES.includes(checks.state);
  const canMergeAnyway =
    isOwner && run.prNumber !== null && checksBlock && (run.status === 'waiting_for_checks' || run.status === 'needs_human');

  const act = async (kind: 'approve' | 'cancel' | 'retry-now' | 'give-up' | 'merge-anyway') => {
    setBusy(kind);
    setErr(null);
    try {
      const r =
        kind === 'merge-anyway'
          ? await api.mergeAnywayRun(run.id, checks?.sha ?? '')
          : await { approve: api.approveRun, cancel: api.cancelRun, 'retry-now': api.retryRunNow, 'give-up': api.giveUpRun }[kind](run.id);
      runQ.setData(() => r);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
      setConfirmCancel(false);
      setConfirmApprove(false);
      setConfirmMergeAnyway(false);
      setConfirmGiveUp(false);
    }
  };
  const mergeAnywayButton = canMergeAnyway && (
    <button className="btn btn--ghost" onClick={() => setConfirmMergeAnyway(true)} disabled={busy !== null} aria-haspopup="dialog">
      Merge anyway…
    </button>
  );
  const waiting = active && run.status !== 'needs_human' ? run.waiting ?? null : null;

  return (
    <div className="page page--wide runpage">
      <header className="run-head">
        <div className="rh-crumbs">
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
          <span className="crumb">run {shortId(run.id)}</span>
        </div>
        <div className="rh-title">
          <h1>
            <span className="rh-num">{runRef(run)}</span> {run.issueTitle}
          </h1>
          {!isSyncRun(run) && <KindBadge kind={run.issueKind} />}
          <RunStatusPill status={run.status} kind={run.kind} />
          {waiting && <span className="rh-waiting small">waiting for network</span>}
        </div>
        <dl className="rh-meta">
          <div><dt>owner</dt><dd>{run.owner.displayName || run.owner.username}{isOwner ? ' (you)' : ''}</dd></div>
          <div><dt>round</dt><dd>{run.round === 0 ? 'build' : isFollowUp(run.round, run) ? roundName(run.round, run) : <>fix round {run.round} <span className="muted">of {run.maxRounds}</span></>}{run.round === 0 && <span className="muted"> · up to {run.maxRounds} fix rounds</span>}</dd></div>
          {run.targetRepo && (
            <>
              <div><dt>repository</dt><dd><Link to={`/p/${run.targetRepo}`}>{run.targetRepo}</Link></dd></div>
              {run.targetDirectory && <div><dt>directory</dt><dd className="mono">{run.targetDirectory}</dd></div>}
            </>
          )}
          <div><dt>branch</dt><dd className="mono">{run.branch ?? '—'}</dd></div>
          {!run.releaseId && policy !== 'automatic' && (
            <div><dt>merge policy</dt><dd>{MERGE_POLICY_LABEL[policy]}</dd></div>
          )}
          {run.baseBranch && (
            <div>
              <dt>base</dt>
              <dd className="mono">
                {run.releaseId ? <Link to={`/p/${repo}/releases/${run.releaseId}`}>{run.baseBranch}</Link> : run.baseBranch}
              </dd>
            </div>
          )}
          {run.continuesRunId && (
            <div><dt>continues</dt><dd><Link to={`/p/${repo}/runs/${run.continuesRunId}`}>run {shortId(run.continuesRunId)}</Link></dd></div>
          )}
          {run.continuedByRunId && (
            <div><dt>continued by</dt><dd><Link to={`/p/${repo}/runs/${run.continuedByRunId}`}>run {shortId(run.continuedByRunId)}</Link></dd></div>
          )}
          <div><dt>pull request</dt><dd>{run.prUrl ? <a href={run.prUrl} target="_blank" rel="noreferrer">{run.targetRepo ? `${run.targetRepo}#${run.prNumber}` : `#${run.prNumber}`} ↗</a> : <span className="muted">after first build</span>}</dd></div>
          <div><dt>{run.endedAt ? 'took' : 'running for'}</dt><dd>{between(run.createdAt, run.endedAt, now)}</dd></div>
          <div><dt>started</dt><dd>{timeAgo(run.createdAt, now)}</dd></div>
        </dl>
        <RunSandboxLine run={run} />
        <RunSandboxVmLine run={run} />
        {run.status === 'merged' && checks?.overriddenBy && (
          <p className="muted small run-override">Merged by {checks.overriddenBy} despite checks: {checks.detail}</p>
        )}
        <div className="rh-actions">
          {run.status === 'needs_human' && isOwner && (
            <>
              {!onGitHub && (
                <button
                  className="btn btn--approve"
                  onClick={() => (escalation !== null ? setConfirmApprove(true) : void act('approve'))}
                  disabled={busy !== null || approveHint !== null}
                  aria-describedby={approveHint ? 'approve-hint' : undefined}
                  aria-haspopup={escalation !== null ? 'dialog' : undefined}
                >
                  {busy === 'approve' ? 'Approving…' : '✓ Approve & merge'}
                </button>
              )}
              <button
                className="btn btn--ghost"
                onClick={() => {
                  select('human');
                  focusComposer();
                }}
              >
                Talk to the agent
              </button>
              {!onGitHub && mergeAnywayButton}
              {approveHint && !onGitHub && <span id="approve-hint" className="muted small">{approveHint}</span>}
            </>
          )}
          {run.status === 'needs_human' && !isOwner && !onGitHub && <span className="muted small">Waiting for {run.owner.username} to approve.</span>}
          {run.status === 'waiting_for_checks' && (isOwner ? mergeAnywayButton : <span className="muted small">Waiting for checks; it merges by itself.</span>)}
          {(run.status === 'failed' || run.status === 'cancelled') && isOwner && !isSyncRun(run) && (
            <button className="btn btn--primary" onClick={() => setRetrying(true)} disabled={busy !== null}>Retry…</button>
          )}
          {active && (isOwner || me.isAdmin) && (
            <button className="btn btn--ghost btn--danger" onClick={() => setConfirmCancel(true)} disabled={busy !== null}>Cancel run</button>
          )}
        </div>
      </header>
      {waiting && (
        <NetworkWaitBanner
          wait={waiting}
          now={now}
          retrying={busy === 'retry-now'}
          disabled={busy !== null}
          onRetry={isOwner || me.isAdmin ? () => void act('retry-now') : undefined}
          onGiveUp={isOwner || me.isAdmin ? () => setConfirmGiveUp(true) : undefined}
        />
      )}
      {run.error && (
        <div className="run-error" role="alert">
          <Markdown text={`**${run.status === 'needs_human' ? 'Needs a human:' : 'Error:'}** ${run.error}`} />
        </div>
      )}
      {run.status === 'needs_human' && policy === 'owner_approves' && (
        <div className="notice run-policy" role="note">
          <div className="notice-text">This project requires the run owner’s approval before every merge.</div>
        </div>
      )}
      {run.status === 'needs_human' && onGitHub && (
        <div className="notice run-policy" role="note">
          <div className="notice-text">
            This project merges on GitHub: Agern does not merge this run. The pull request can be reviewed and merged there.
          </div>
        </div>
      )}
      {run.status === 'ready' && (
        <div className="notice run-policy" role="status">
          <div className="notice-text">
            <strong>Ready for review on GitHub.</strong>
            This project merges on GitHub: Agern does not merge. Review and merge {prLink}; this page updates when it is merged or closed.
          </div>
        </div>
      )}
      {run.status === 'merged' && run.mergedOnGitHub && (
        <div className="notice run-policy" role="note">
          <div className="notice-text">{run.mergedBy ? `Merged on GitHub by @${run.mergedBy}` : 'Merged on GitHub'}</div>
        </div>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}
      <SandboxVmPreviewPanel run={run} canAct={isOwner || me.isAdmin} onRun={(r) => runQ.setData(() => r)} />
      {run.status === 'waiting_for_checks' && checks && <WaitingForChecks checks={checks} />}
      {(run.warnings ?? []).map((w) => {
        // "<title>: <detail>" — the title in bold, the detail as a sentence.
        const cut = w.text.indexOf(':');
        const title = cut > 0 ? w.text.slice(0, cut) : w.text;
        const detail = cut > 0 ? w.text.slice(cut + 1).trim() : '';
        return (
          <div key={w.round} className="notice notice--warn run-warning" role="note" aria-label={`Warning, ${roundName(w.round, run)}`}>
            <div className="notice-text">
              <strong>⚠ {title} <span className="muted">· {roundName(w.round, run)}</span></strong>
              {detail && detail[0].toUpperCase() + detail.slice(1)}
            </div>
            {w.roles[0] && (
              <button
                type="button"
                className="btn btn--sm btn--ghost notice-action"
                onClick={() => { params.set('stage', w.roles[0]); params.set('round', String(w.round)); setParams(params, { replace: true }); }}
              >
                See the {w.roles[0]} review
              </button>
            )}
          </div>
        );
      })}

      <PipelineDiagram run={run} nodes={nodes} selected={selected} onSelect={select} />

      <RoundsStrip run={run} onPick={(role, round) => { params.set('stage', role); params.set('round', String(round)); setParams(params, { replace: true }); }} />

      {selected === 'human' ? (
        <HumanDetail run={run} session={advisor} isOwner={isOwner} onRun={(r) => runQ.setData(() => r)} />
      ) : (
      <StageDetail run={run} node={selected} round={params.get('round')} sessions={sessionMap} onRound={(r) => { params.set('round', String(r)); setParams(params, { replace: true }); }} />
      )}

      <RetryDialog
        open={retrying}
        title={`Retry run ${shortId(run.id)}?`}
        load={() => (run.releaseId ? api.releaseIssueRetryOptions(run.releaseId, run.issueNumber) : api.runRetryOptions(run.id))}
        onClose={() => setRetrying(false)}
        onRetry={async (mode) => {
          if (run.releaseId) {
            await api.retryReleaseIssue(run.releaseId, run.issueNumber, mode);
            navigate(`/p/${repo}/releases/${run.releaseId}`);
            return;
          }
          const next = await api.retryRun(run.id, mode);
          if (next.id === run.id) runQ.setData(() => next);
          else navigate(`/p/${repo}/runs/${next.id}`);
        }}
      />

      <GiveUpDialog
        open={confirmGiveUp && waiting !== null}
        text={GIVE_UP_RUN_TEXT}
        busy={busy === 'give-up'}
        onClose={() => setConfirmGiveUp(false)}
        onConfirm={() => void act('give-up')}
      />

      <Dialog open={confirmApprove && escalation !== null} onClose={() => setConfirmApprove(false)} title="Merge against the approver's advice?">
        <p>The approver did not approve this change and asked for a human:</p>
        <div className="notice notice--warn escalation-reason" role="note" aria-label="The approver's reason">
          <div className="notice-text"><Markdown text={escalation ?? ''} /></div>
        </div>
        <p>
          Merging squash-merges {run.prNumber ? <>pull request #{run.prNumber}</> : 'the pull request'} into{' '}
          <code>{run.baseBranch ?? 'the default branch'}</code> now. Merge only if you have checked the approver’s concern.
        </p>
        <div className="dialog-actions">
          <button className="btn btn--ghost" onClick={() => setConfirmApprove(false)} data-autofocus>Don’t merge</button>
          <button className="btn btn--approve" onClick={() => void act('approve')} disabled={busy !== null}>{busy === 'approve' ? 'Merging…' : 'Merge anyway'}</button>
        </div>
      </Dialog>

      <Dialog open={confirmMergeAnyway && canMergeAnyway} onClose={() => setConfirmMergeAnyway(false)} title="Merge without passing checks?">
        <div className="notice notice--warn escalation-reason" role="note" aria-label="Checks that have not passed">
          <div className="notice-text">
            {checks && checks.items.length > 0 ? (
              <ul className="checks-list">
                {checks.items.map((c, i) => (
                  <li key={i}>
                    <CheckName item={c} /> — {c.state === 'failed' ? `failed (${c.conclusion ?? 'unknown'})` : 'pending'}
                  </li>
                ))}
              </ul>
            ) : (
              checks?.detail
            )}
          </div>
        </div>
        {escalation !== null && (
          <>
            <p>The approver did not approve this change and asked for a human:</p>
            <div className="notice notice--warn escalation-reason" role="note" aria-label="The approver's reason">
              <div className="notice-text"><Markdown text={escalation} /></div>
            </div>
          </>
        )}
        <p>
          Merging squash-merges {run.prNumber ? <>pull request #{run.prNumber}</> : 'the pull request'} into{' '}
          <code>{run.baseBranch ?? 'the default branch'}</code> now, without waiting for these checks. Your decision is recorded on the run and on the
          pull request.
        </p>
        <div className="dialog-actions">
          <button className="btn btn--ghost" onClick={() => setConfirmMergeAnyway(false)} data-autofocus>Don’t merge</button>
          <button className="btn btn--approve" onClick={() => void act('merge-anyway')} disabled={busy !== null}>{busy === 'merge-anyway' ? 'Merging…' : 'Merge anyway'}</button>
        </div>
      </Dialog>

      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} title="Cancel this run?">
        <p>Running agents are stopped. The branch {run.branch && <code>{run.branch}</code>} and any pull request stay on GitHub.</p>
        <div className="dialog-actions">
          <button className="btn btn--ghost" onClick={() => setConfirmCancel(false)}>Keep running</button>
          <button className="btn btn--danger" onClick={() => void act('cancel')} disabled={busy !== null}>{busy === 'cancel' ? 'Cancelling…' : 'Cancel run'}</button>
        </div>
      </Dialog>
    </div>
  );
}

/** A check's name: a link (new tab) when GitHub gave an https URL. Name and URL are untrusted and rendered as text. */
function CheckName({ item }: { item: RunCheckItem }) {
  return item.url?.startsWith('https://') ? (
    <a href={item.url} target="_blank" rel="noopener noreferrer">{item.name}</a>
  ) : (
    <span>{item.name}</span>
  );
}

/** While the run waits for the checks on its pull request's head commit: what it waits for and until when. */
function WaitingForChecks({ checks }: { checks: NonNullable<Run['checks']> }) {
  const pending = checks.items.filter((c) => c.state === 'pending');
  const detail = checks.detail.replace(/[.\s]+$/, '');
  return (
    <div className="notice run-checks" role="status">
      <div className="notice-text">
        Waiting for checks on <code>{checks.sha.slice(0, 7)}</code>. {detail}. The pull request is merged automatically when they pass
        {checks.deadline ? <>; Agern stops waiting at {dateTime(checks.deadline)}.</> : '.'}
        {pending.length > 0 && (
          <ul className="checks-list">
            {pending.map((c, i) => (
              <li key={i}><CheckName item={c} /></li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function RoundsStrip({ run, onPick }: { run: Run; onPick: (role: StageRole, round: number) => void }) {
  const rs = rounds(run);
  if (rs.length === 0) return null;
  return (
    <section className="rounds" aria-label="Rounds">
      {rs.map(({ round, stages }) => (
        <div key={round} className="round">
          <span className="round-k">{roundName(round, run)}</span>
          {[...stages].sort((a, b) => ROLE_ORDER.indexOf(a.role) - ROLE_ORDER.indexOf(b.role)).map((s) => (
            <button key={s.id} type="button" className={`round-chip round-chip--${s.status} ${s.verdict ? `round-chip--${s.verdict.toLowerCase()}` : ''}`} onClick={() => onPick(s.role, s.round)}>
              {s.role}
              <span className="round-chip-v">{s.verdict ?? (s.status === 'running' ? '…' : s.status === 'done' ? '✓' : s.status)}</span>
            </button>
          ))}
        </div>
      ))}
    </section>
  );
}

/** The conversation with the run's advisor, under the needs-human node (docs/ARCHITECTURE.md §6 "Conversation"). */
function HumanDetail({ run, session, isOwner, onRun }: { run: Run; session: AgentSession | undefined; isOwner: boolean; onRun: (r: Run) => void }) {
  const [starting, setStarting] = useState(false);
  const [reviewErr, setReviewErr] = useState<string | null>(null);
  const conv = run.conversation;
  const waiting = run.status === 'needs_human';
  const followUp = conv.followUpFrom != null ? run.round - conv.followUpFrom + 1 : 1;
  const reviewLine =
    conv.review === 'running'
      ? `Reviewing the changes (follow-up ${followUp})…`
      : conv.review === 'required'
        ? 'Changes have not been reviewed.'
        : 'No changes since the last review.';
  const reviewAgain = async () => {
    setStarting(true);
    setReviewErr(null);
    try {
      onRun(await api.reviewRun(run.id));
    } catch (e) {
      setReviewErr((e as Error).message);
    } finally {
      setStarting(false);
    }
  };
  return (
    <div className="stage-detail conv-panel">
      <div className="sd-info">
        <p className="eyebrow">needs human</p>
        <h2>Conversation</h2>
        <p className="muted">
          Ask the agent about this run, or ask it for a change. A change is committed, pushed and reviewed again before the run can be
          approved. Talking never approves anything. Messages are not posted on GitHub.
        </p>
        <dl className="sd-facts">
          <div><dt>model</dt><dd>{modelLabel(session?.model)}</dd></div>
          <div><dt>cost</dt><dd>{cost(session?.costUsd)}</dd></div>
          <div><dt>turns</dt><dd>{session?.numTurns ?? '—'}</dd></div>
        </dl>
        {(waiting || conv.sessionId) && <p className="muted small" role="status">{reviewLine}</p>}
        {isOwner && waiting && conv.review === 'required' && (
          <button type="button" className="btn btn--sm" onClick={() => void reviewAgain()} disabled={starting}>
            {starting ? 'Starting…' : 'Review again'}
          </button>
        )}
        {reviewErr && <p className="form-error" role="alert">{reviewErr}</p>}
      </div>
      <div className="sd-transcript">
        {conv.sessionId ? (
          <ChatView key={conv.sessionId} sessionId={conv.sessionId} session={session} embedded advisorRun={run} />
        ) : !waiting ? (
          <div className="sd-empty"><p className="muted">No conversation took place.</p></div>
        ) : isOwner ? (
          <FirstMessage run={run} />
        ) : (
          <div className="sd-empty"><p className="muted">No conversation yet. Only {run.owner.username} can talk to the agent.</p></div>
        )}
      </div>
    </div>
  );
}

/** The owner's composer before the conversation exists; the transcript replaces it once the server has the session. */
function FirstMessage({ run }: { run: Run }) {
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  const send = async (text: string) => {
    setError(null);
    try {
      await api.talkToRun(run.id, text);
      setSent(text);
      return true;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not send.');
      return false;
    }
  };
  return (
    <section className="chat chat--embedded" aria-label="Agent transcript">
      <div className="chat-scroll-wrap">
        <div className="chat-scroll">
          <div className="chat-log">
            {sent ? (
              <div className="t-user t-user--pending">
                <span className="t-prompt" aria-hidden>&gt;</span>
                <div className="t-user-body"><div className="t-user-text">{sent}</div></div>
              </div>
            ) : (
              <div className="t-meta">No conversation yet. Ask why the run needs a human, or ask for a change.</div>
            )}
          </div>
        </div>
      </div>
      {error && (
        <div className="chat-error" role="alert">
          {error}
          <button type="button" className="link" onClick={() => setError(null)}>dismiss</button>
        </div>
      )}
      {run.conversation.review === 'running' ? (
        <div className="chat-disabled" role="status">The changes are being reviewed; you can continue when the reviews are in.</div>
      ) : (
        <Prompt
          onSubmit={send}
          working={sent !== null}
          commands={false}
          label="Message the agent"
          placeholder="Ask about this run, or ask for a change"
          history={[]}
        />
      )}
    </section>
  );
}

/** A sync run has no issue: its first node shows the release it brings up to date. */
function SyncReleaseDetail({ run }: { run: Run }) {
  const release = useAsync(() => (run.releaseId ? api.release(run.releaseId) : Promise.resolve(null)), [run.releaseId]);
  const r = release.data;
  return (
    <div className="stage-detail stage-detail--issue">
      <div className="sd-info">
        <p className="eyebrow">release</p>
        <h2>{run.issueTitle}</h2>
        <p className="muted small">
          {r ? <>Release {r.name} · </> : null}
          <span className="mono">
            {run.baseBranch ?? 'the release branch'} → {r?.targetBranch ?? 'the default branch'}
          </span>
          {run.releaseId && (
            <>
              {' '}· <Link to={`/p/${run.repo}/releases/${run.releaseId}`}>Open the release</Link>
            </>
          )}
        </p>
        {run.syncPushedSha && (
          <p className="muted small">
            pushed <code title={run.syncPushedSha}>{run.syncPushedSha.slice(0, 7)}</code> to {run.baseBranch}
          </p>
        )}
      </div>
      <div className="sd-body card">
        <p>
          The default branch is merged into the release branch with a merge commit; nothing is merged into the default branch. The builder resolves
          the conflicts and renumbers the release’s own migrations; the reviewers then look at the conflict resolution.
        </p>
      </div>
    </div>
  );
}

function IssueDetail({ run }: { run: Run }) {
  const issue = useAsync(() => api.issue(run.repo, run.issueNumber), [run.repo, run.issueNumber]);
  return (
    <div className="stage-detail stage-detail--issue">
      <div className="sd-info">
        <p className="eyebrow">issue</p>
        <h2>#{run.issueNumber} {run.issueTitle}</h2>
        {issue.data && (
          <p className="muted small">
            opened by {issue.data.author} · {timeAgo(issue.data.createdAt)} · <a href={issue.data.htmlUrl} target="_blank" rel="noreferrer">GitHub ↗</a>
          </p>
        )}
      </div>
      <div className="sd-body card">
        {issue.loading && <Loading />}
        {issue.data?.body ? <Markdown text={issue.data.body} /> : issue.data && <p className="muted">No description.</p>}
      </div>
    </div>
  );
}

function StageDetail({ run, node, round, sessions, onRound }: { run: Run; node: NodeId; round: string | null; sessions: Map<string, AgentSession>; onRound: (r: number) => void }) {
  const now = useNow(1000);
  if (node === 'issue') return isSyncRun(run) ? <SyncReleaseDetail run={run} /> : <IssueDetail run={run} />;
  if (!ROLE_ORDER.includes(node as StageRole)) return null;
  const role = node as StageRole;
  const all = stagesFor(run, role);
  const stage = (round && all.find((s) => s.round === Number(round))) || latestStage(run, role);
  const session = stage?.sessionId ? sessions.get(stage.sessionId) : undefined;
  return (
    <div className="stage-detail">
      <div className="sd-info">
        <p className="eyebrow">{REVIEWERS.includes(role) ? 'reviewer' : 'stage'}</p>
        <h2 className="sd-role">{role}</h2>
        <p className="muted">{ROLE_BLURB[role]}</p>
        {all.length > 1 && (
          <div className="seg seg--small" role="tablist" aria-label="Round">
            {all.map((s) => (
              <button key={s.id} role="tab" aria-selected={s.id === stage?.id} className={s.id === stage?.id ? 'is-on' : ''} onClick={() => onRound(s.round)}>
                {roundName(s.round, run)}
              </button>
            ))}
          </div>
        )}
        {!stage ? (
          <p className="muted pad-top">Not started yet.</p>
        ) : (
          <>
            <dl className="sd-facts">
              <div><dt>status</dt><dd className={`st st--${stage.status}`}>{stage.status}</dd></div>
              <div><dt>verdict</dt><dd>{stage.verdict ? <Verdict v={stage.verdict} /> : '—'}</dd></div>
              <div><dt>model</dt><dd>{modelLabel(stage.model || session?.model)}</dd></div>
              <div><dt>time</dt><dd>{between(stage.startedAt, stage.endedAt, now)}</dd></div>
              <div><dt>cost</dt><dd>{cost(session?.costUsd)}</dd></div>
              <div><dt>turns</dt><dd>{session?.numTurns ?? '—'}</dd></div>
            </dl>
            {role === 'builder' && stage.round > 0 && (
              <p className="muted small">Continues the builder conversation of {roundName(stage.round - 1, run)} (a new agent session resumed with <code>--resume</code>), given all reviewer findings.</p>
            )}
            {stage.warning && <p className="sd-warning" role="note">⚠ {stage.warning}</p>}
            {stage.summary && <div className="sd-summary"><Markdown text={stage.summary} /></div>}
            {stage.findings.length > 0 && (
              <>
                <h3 className="sd-h">Findings <span className="muted">{stage.findings.length}</span></h3>
                <ul className="findings">
                  {stage.findings.map((f, i) => (
                    <li key={i} className={`finding finding--${f.severity.toLowerCase()}`}>
                      <span className="sev">{f.severity}</span>
                      <div>
                        {f.file && <code className="finding-file">{f.file}</code>}
                        <Markdown text={f.detail} />
                      </div>
                    </li>
                  ))}
                </ul>
              </>
            )}
            <StageScreenshots runId={run.id} screenshots={stage.screenshots} />
            <StageImages images={stage.images ?? []} />
          </>
        )}
      </div>
      <div className="sd-transcript">
        {stage?.sessionId ? (
          <ChatView key={stage.sessionId} sessionId={stage.sessionId} session={session} embedded />
        ) : (
          <div className="sd-empty">
            <pre aria-hidden>{'  ○  waiting for upstream stages'}</pre>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The screenshots a UX or UI design reviewer attached to the selected stage (one round's stage row). Renders nothing
 * when there are none. Captions are untrusted text and are only ever rendered as text.
 */
function StageScreenshots({ runId, screenshots }: { runId: string; screenshots: Screenshot[] }) {
  const [openId, setOpenId] = useState<string | null>(null);
  if (screenshots.length === 0) return null;
  const open = screenshots.find((s) => s.id === openId) ?? null;
  return (
    <>
      <h3 className="sd-h">Screenshots <span className="muted">{screenshots.length}</span></h3>
      <ul className="shots">
        {screenshots.map((s) => (
          <li key={s.id}>
            <figure className="shot">
              <button type="button" className="shot-thumb" onClick={() => setOpenId(s.id)} aria-label={`Open screenshot: ${s.caption}`}>
                <img src={api.screenshotUrl(runId, s.id)} alt={s.caption} loading="lazy" width={s.width} height={s.height} />
              </button>
              <figcaption>{s.caption}</figcaption>
            </figure>
          </li>
        ))}
      </ul>
      <Dialog open={open !== null} onClose={() => setOpenId(null)} title="Screenshot" wide>
        {open && (
          <div className="att-lightbox shot-lightbox">
            <img src={api.screenshotUrl(runId, open.id)} alt={open.caption} />
            <p className="shot-caption">{open.caption}</p>
            <p className="muted small">
              {open.mediaType} · {bytes(open.size)} · {open.width}×{open.height} ·{' '}
              <a href={api.screenshotUrl(runId, open.id)} target="_blank" rel="noreferrer">Open full size ↗</a>
            </p>
          </div>
        )}
      </Dialog>
    </>
  );
}

/**
 * The images the selected stage's agent generated (spec-writer, builder), with the prompt used, so they can be seen
 * before the run is approved. Renders nothing when there are none. Purpose and prompt are untrusted text.
 */
function StageImages({ images }: { images: GeneratedImage[] }) {
  const [openId, setOpenId] = useState<string | null>(null);
  if (images.length === 0) return null;
  const open = images.find((s) => s.id === openId) ?? null;
  return (
    <>
      <h3 className="sd-h">Generated images <span className="muted">{images.length}</span></h3>
      <ul className="shots">
        {images.map((s) => (
          <li key={s.id}>
            <figure className="shot">
              <button type="button" className="shot-thumb" onClick={() => setOpenId(s.id)} aria-label={`Open image: ${s.purpose}`}>
                <img src={api.imageUrl(s.id)} alt={s.purpose} loading="lazy" width={s.width} height={s.height} />
              </button>
              <figcaption>{s.purpose}</figcaption>
              <details className="shot-prompt">
                <summary>Prompt</summary>
                <p>{s.prompt}</p>
              </details>
            </figure>
          </li>
        ))}
      </ul>
      <Dialog open={open !== null} onClose={() => setOpenId(null)} title="Generated image" wide>
        {open && (
          <div className="att-lightbox shot-lightbox">
            <img src={api.imageUrl(open.id)} alt={open.purpose} />
            <p className="shot-caption">{open.purpose}</p>
            <p className="muted small">
              {open.mediaType} · {bytes(open.size)} · {open.width}×{open.height} · {open.model} ·{' '}
              <a href={api.imageUrl(open.id)} target="_blank" rel="noopener noreferrer">Open full size ↗</a>
            </p>
          </div>
        )}
      </Dialog>
    </>
  );
}
