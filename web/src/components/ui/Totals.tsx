// Time and cost of a release and its issues (docs/ARCHITECTURE.md §6 "Time and cost", §10 "Release page").
import type { IssueTotals, ReleaseTotals, TotalsRow, TotalsTime } from '../../api';
import { duration } from '../../lib/format';
import { amountText, barFraction, credentialNote, issueLabel, roleLabel, runningNote, runsText, shortAmount, tick, turnsText, workLabel } from '../../lib/totals';
import type { Loaded } from '../../lib/useTotals';
import { useNow } from '../../lib/useNow';

/** The "Time and cost" card of a started release; [loaded] comes from `useReleaseTotals`. */
export function ReleaseTotalsCard({ loaded }: { loaded: Loaded<ReleaseTotals> }) {
  const { data, error, receivedAt, reload } = loaded;
  const running = !!data?.time.running;
  const now = useNow(1000, running);
  return (
    <section className="release-totals" aria-labelledby="release-totals-h">
      <h2 id="release-totals-h">Time and cost</h2>
      {!data ? (
        error ? (
          <p className="totals-error">
            Time and cost could not be loaded.{' '}
            <button type="button" className="btn btn--sm" onClick={reload}>Try again</button>
          </p>
        ) : (
          <p className="muted small">Calculating time and cost…</p>
        )
      ) : (
        <TotalsBody totals={data} time={tick(data.time, receivedAt, running ? Math.max(now, receivedAt) : receivedAt)} />
      )}
    </section>
  );
}

function TotalsBody({ totals, time }: { totals: ReleaseTotals; time: TotalsTime }) {
  const c = totals.cost;
  const running = runningNote(c);
  return (
    <>
      <dl className="totals-lines">
        <div>
          <dt>Time</dt>
          <dd>
            <span className="totals-figures">
              <span>{duration(time.elapsedMs)}{time.running && ' so far'}</span>
              <span>agents working {duration(time.workingMs)}</span>
              <span>waiting {duration(time.waitingMs)}</span>
            </span>
            <span className="totals-note muted small">
              Waiting is all time no agent was running: for a person, checks, the network or a free slot.
            </span>
          </dd>
        </div>
        <div>
          <dt>Cost</dt>
          <dd>
            <span className="totals-figures">
              <span>{amountText(c)}</span>
              <span>{runsText(totals.runs)}</span>
              {totals.runs > 0 && <span>{turnsText(c.turns)}</span>}
            </span>
            <span className="totals-note small">{credentialNote(c)}</span>
            {running && <span className="totals-note muted small">{running}</span>}
          </dd>
        </div>
      </dl>
      {(totals.byRole.length > 0 || totals.other) && (
        <details className="totals-breakdown">
          <summary>Breakdown by role and kind of work</summary>
          <div className="totals-tables">
            <BreakdownTable caption="By role" rows={totals.byRole} label={roleLabel} />
            <BreakdownTable caption="By kind of work" rows={totals.byWork} label={workLabel} />
          </div>
          <p className="muted small">
            Retries: every later run of an issue and every repeated attempt of a stage. Agent time adds up each agent's own
            time, so agents that ran in parallel count each.
          </p>
          {totals.other && (
            <p className="small">
              Not in the issue list: {amountText(totals.other.cost)} in {runsText(totals.other.runs)} (bringing the release up to
              date, issues taken out of the release).
            </p>
          )}
        </details>
      )}
    </>
  );
}

function BreakdownTable({ caption, rows, label }: { caption: string; rows: TotalsRow[]; label: (key: string) => string }) {
  return (
    <div className="table-wrap totals-table-wrap">
      <table className="table totals-table">
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">name</th>
            <th scope="col" className="num">sessions</th>
            <th scope="col" className="num">turns</th>
            <th scope="col" className="num">agent time</th>
            <th scope="col" className="num">cost</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <th scope="row">{label(r.key)}</th>
              <td className="num">{r.cost.sessions}</td>
              <td className="num">{r.cost.turns}</td>
              <td className="num">{r.key === 'advisor' ? '—' : duration(r.agentMs)}</td>
              <td className="num">{amountText(r.cost)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** An issue's elapsed time and cost in its row, with a bar relative to the most expensive issue ([maxUsd]). */
export function IssueTotalsFigure({ totals, maxUsd }: { totals: IssueTotals | undefined; maxUsd: number }) {
  if (!totals || totals.runs === 0) return null;
  const pct = Math.round(barFraction(totals.cost.usd, maxUsd) * 100);
  return (
    <span className="ro-totals small">
      <span className="sr-only">{issueLabel(totals.number, totals.time, totals.cost, totals.runs)}</span>
      <span className="ro-totals-text mono" aria-hidden="true">
        {duration(totals.time.elapsedMs)} · {shortAmount(totals.cost)}
      </span>
      <span className="ro-bar" aria-hidden="true">
        <span className="ro-bar-fill" style={{ width: `${pct}%` }} />
      </span>
    </span>
  );
}
