import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError, isRunActive, isRunWorking, type AgentSession, type Run, type Screenshot, type StageRole } from '../api';
import { ChatView } from '../components/chat/ChatView';
import { RunSandboxLine } from '../components/sandbox/SandboxBits';
import { Markdown } from '../components/chat/Markdown';
import { buildNodes, latestStage, REVIEWERS, ROLE_BLURB, roundName, rounds, stagesFor, type NodeId } from '../components/pipeline/model';
import { PipelineDiagram } from '../components/pipeline/PipelineDiagram';
import { Dialog, Empty, ErrorBox, KindBadge, Loading, RunStatusPill, Verdict } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { between, bytes, cost, modelLabel, shortId, timeAgo } from '../lib/format';
import { useLiveRun, useLiveSessions } from '../lib/realtime';
import { useNow } from '../lib/useNow';

const ROLE_ORDER: StageRole[] = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver'];

function defaultNode(run: Run): NodeId {
  const running = run.stages.find((s) => s.status === 'running');
  if (running) return running.role;
  if (run.status === 'needs_human') return 'approver';
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
  const [busy, setBusy] = useState<'approve' | 'cancel' | null>(null);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const active = run ? isRunActive(run) : false;
  const now = useNow(1000, run ? isRunWorking(run) : false);

  useEffect(() => {
    if (run) document.title = `#${run.issueNumber} ${run.issueTitle} · ${repo} · Agern`;
  }, [run, repo]);

  // Sessions created after first load: refetch when the run references one we don't know.
  useEffect(() => {
    if (run?.stages.some((s) => s.sessionId && !sessionMap.has(s.sessionId)) && !sessQ.loading) sessQ.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.stages.length]);

  if (runQ.error)
    return (
      <div className="page">
        {runQ.error instanceof ApiError && runQ.error.status === 404 ? <Empty title="Run not found">It may belong to a repository you can’t read. <Link to={`/p/${repo}`}>Back to {repo}</Link></Empty> : <ErrorBox error={runQ.error} retry={runQ.reload} />}
      </div>
    );
  if (!run) return <div className="page"><Loading label="Loading run" /></div>;

  const nodes = buildNodes(run, sessionMap);
  const selParam = params.get('stage') as NodeId | null;
  const selected: NodeId = selParam && (selParam === 'issue' || ROLE_ORDER.includes(selParam as StageRole)) ? selParam : defaultNode(run);
  const select = (id: NodeId) => {
    params.set('stage', id);
    params.delete('round');
    setParams(params, { replace: true });
  };
  const isOwner = run.owner.username === me.username;

  const act = async (kind: 'approve' | 'cancel') => {
    setBusy(kind);
    setErr(null);
    try {
      const r = kind === 'approve' ? await api.approveRun(run.id) : await api.cancelRun(run.id);
      runQ.setData(() => r);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(null);
      setConfirmCancel(false);
    }
  };

  return (
    <div className="page page--wide runpage">
      <header className="run-head">
        <div className="rh-crumbs">
          <Link to={`/p/${repo}`} className="crumb">{repo} /</Link>
          <span className="crumb">run {shortId(run.id)}</span>
        </div>
        <div className="rh-title">
          <h1>
            <span className="rh-num">#{run.issueNumber}</span> {run.issueTitle}
          </h1>
          <KindBadge kind={run.issueKind} />
          <RunStatusPill status={run.status} />
        </div>
        <dl className="rh-meta">
          <div><dt>owner</dt><dd>{run.owner.displayName || run.owner.username}{isOwner ? ' (you)' : ''}</dd></div>
          <div><dt>round</dt><dd>{run.round === 0 ? 'build' : <>fix round {run.round} <span className="muted">of {run.maxRounds}</span></>}{run.round === 0 && <span className="muted"> · up to {run.maxRounds} fix rounds</span>}</dd></div>
          <div><dt>branch</dt><dd className="mono">{run.branch ?? '—'}</dd></div>
          {run.baseBranch && (
            <div>
              <dt>base</dt>
              <dd className="mono">
                {run.releaseId ? <Link to={`/p/${repo}/releases/${run.releaseId}`}>{run.baseBranch}</Link> : run.baseBranch}
              </dd>
            </div>
          )}
          <div><dt>pull request</dt><dd>{run.prUrl ? <a href={run.prUrl} target="_blank" rel="noreferrer">#{run.prNumber} ↗</a> : <span className="muted">after first build</span>}</dd></div>
          <div><dt>{run.endedAt ? 'took' : 'running for'}</dt><dd>{between(run.createdAt, run.endedAt, now)}</dd></div>
          <div><dt>started</dt><dd>{timeAgo(run.createdAt, now)}</dd></div>
        </dl>
        <RunSandboxLine run={run} />
        <div className="rh-actions">
          {run.status === 'needs_human' && isOwner && (
            <button className="btn btn--approve" onClick={() => void act('approve')} disabled={busy !== null}>
              {busy === 'approve' ? 'Approving…' : '✓ Approve & merge'}
            </button>
          )}
          {run.status === 'needs_human' && !isOwner && <span className="muted small">Waiting for {run.owner.username} to approve.</span>}
          {active && (isOwner || me.isAdmin) && (
            <button className="btn btn--ghost btn--danger" onClick={() => setConfirmCancel(true)} disabled={busy !== null}>Cancel run</button>
          )}
        </div>
      </header>
      {run.error && (
        <div className="run-error" role="alert">
          <Markdown text={`**${run.status === 'needs_human' ? 'Needs a human:' : 'Error:'}** ${run.error}`} />
        </div>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}

      <PipelineDiagram run={run} nodes={nodes} selected={selected} onSelect={select} />

      <RoundsStrip run={run} onPick={(role, round) => { params.set('stage', role); params.set('round', String(round)); setParams(params, { replace: true }); }} />

      <StageDetail run={run} node={selected} round={params.get('round')} sessions={sessionMap} onRound={(r) => { params.set('round', String(r)); setParams(params, { replace: true }); }} />

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

function RoundsStrip({ run, onPick }: { run: Run; onPick: (role: StageRole, round: number) => void }) {
  const rs = rounds(run);
  if (rs.length === 0) return null;
  return (
    <section className="rounds" aria-label="Rounds">
      {rs.map(({ round, stages }) => (
        <div key={round} className="round">
          <span className="round-k">{roundName(round)}</span>
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
  if (node === 'issue') return <IssueDetail run={run} />;
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
                {roundName(s.round)}
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
              <p className="muted small">Continues the builder conversation of {roundName(stage.round - 1)} (a new agent session resumed with <code>--resume</code>), given all reviewer findings.</p>
            )}
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
