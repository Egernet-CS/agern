import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, isRunActive, isSessionActive, type AgentSession } from '../api';
import { Empty, ErrorBox, Loading, RoleBadge, RunStatusPill, SessionStatusPill } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { between, cost, modelLabel, timeAgo } from '../lib/format';
import { useLiveRuns, useLiveSessions } from '../lib/realtime';
import { useNow } from '../lib/useNow';
import { runStageLine } from './Project';

export const transcriptLink = (s: AgentSession) => (s.kind === 'chat' ? `/p/${s.repo}?session=${s.id}` : `/p/${s.repo}/runs/${s.runId}?stage=${s.kind}`);

export function AgentsPage() {
  const me = useMe();
  const [who, setWho] = useState<'all' | 'mine'>('all');
  const [showEnded, setShowEnded] = useState(false);
  const base = useAsync(() => api.agents(!showEnded), [showEnded]);
  const runsBase = useAsync(() => api.runs({ active: true }), []);
  const sessions = useLiveSessions(base.data, () => true).filter((s) => (showEnded || isSessionActive(s)) && (who === 'all' || s.owner.username === me.username));
  const runs = useLiveRuns(runsBase.data, () => true).filter((r) => isRunActive(r) && (who === 'all' || r.owner.username === me.username));
  const now = useNow(1000);

  useEffect(() => {
    document.title = 'Agents · Agern';
  }, []);

  return (
    <div className="page page--wide">
      <div className="page-head">
        <div>
          <h1 className="page-title">On the floor</h1>
          <p className="muted">Every agent you can see, across projects. Click through to watch its transcript.</p>
        </div>
        <div className="filters">
          <div className="seg" role="radiogroup" aria-label="Owner">
            {(['all', 'mine'] as const).map((w) => (
              <button key={w} role="radio" aria-checked={who === w} className={who === w ? 'is-on' : ''} onClick={() => setWho(w)}>{w === 'all' ? 'everyone' : 'mine'}</button>
            ))}
          </div>
          <label className="check check--inline">
            <input type="checkbox" checked={showEnded} onChange={(e) => setShowEnded(e.target.checked)} /> include ended
          </label>
        </div>
      </div>

      {runs.length > 0 && (
        <section className="runs-band" aria-label="Runs in flight">
          {runs.map((r) => (
            <Link key={r.id} to={`/p/${r.repo}/runs/${r.id}`} className={`band-run band-run--${r.status}`}>
              <div className="br-top">
                <span className="mono br-repo">{r.repo}#{r.issueNumber}</span>
                <RunStatusPill status={r.status} />
              </div>
              <div className="br-title">{r.issueTitle}</div>
              <div className="br-line">
                <ConveyorMini run={r} />
                <span className="muted small">{runStageLine(r)}</span>
              </div>
              <div className="br-meta muted small">{r.owner.username} · {between(r.createdAt, null, now)}</div>
            </Link>
          ))}
        </section>
      )}

      {base.loading && !base.data && <Loading />}
      {base.error && <ErrorBox error={base.error} retry={base.reload} />}
      {base.data && sessions.length === 0 && (
        <Empty title="Nothing running">No agents are working right now{who === 'mine' ? ' for you' : ''}. Start a chat or a pipeline run from a project.</Empty>
      )}
      {sessions.length > 0 && (
        <div className="table-wrap">
          <table className="table agents-table">
            <thead>
              <tr>
                <th scope="col">agent</th>
                <th scope="col">project</th>
                <th scope="col">owner</th>
                <th scope="col" className="col-doing">doing right now</th>
                <th scope="col">model</th>
                <th scope="col">cost</th>
                <th scope="col">up</th>
                <th scope="col"><span className="sr-only">transcript</span></th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((s) => (
                <tr key={s.id} className={s.status === 'running' ? 'is-running' : ''}>
                  <td data-label="agent">
                    <div className="cell-stack">
                      <RoleBadge kind={s.kind} />
                      <SessionStatusPill status={s.status} />
                    </div>
                  </td>
                  <td data-label="project"><Link to={`/p/${s.repo}`}>{s.repo}</Link>{s.issueNumber && <span className="muted"> #{s.issueNumber}</span>}</td>
                  <td data-label="owner">{s.owner.username === me.username ? <strong>you</strong> : s.owner.displayName || s.owner.username}</td>
                  <td data-label="doing" className="col-doing mono">
                    <span className="doing" title={s.lastActivity ?? ''}>{s.lastActivity ?? (s.kind === 'chat' ? 'waiting for input' : 'starting…')}</span>
                    <span className="muted small"> · {timeAgo(s.updatedAt, now)}</span>
                  </td>
                  <td data-label="model">{modelLabel(s.model)}</td>
                  <td data-label="cost" className="num">{cost(s.costUsd)}</td>
                  <td data-label="up" className="num">{between(s.createdAt, s.endedAt, now)}</td>
                  <td><Link to={transcriptLink(s)} className="btn btn--sm btn--ghost">transcript →</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** Seven-cell strip showing the pipeline position of a run. */
function ConveyorMini({ run }: { run: import('../api').Run }) {
  const order = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver'] as const;
  return (
    <span className="conveyor-mini" aria-hidden>
      {order.map((role) => {
        const st = [...run.stages].reverse().find((s) => s.role === role && (role === 'spec-writer' || role === 'approver' || s.round === run.round));
        const cls = !st ? 'off' : st.status === 'running' ? 'run' : st.verdict === 'CHANGES' ? 'chg' : st.verdict === 'ESCALATE' ? 'esc' : st.status === 'done' ? 'ok' : st.status;
        return <span key={role} className={`cm cm--${cls}`} />;
      })}
    </span>
  );
}
