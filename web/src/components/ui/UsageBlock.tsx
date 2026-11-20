import type { ClaudeUsage, UsageWindow } from '../../api';
import { dateTime, timeAgo } from '../../lib/format';
import { isReset, money, percent, resetsIn, sortWindows, usageLevel, useClaudeUsage, windowLabel } from '../../lib/usage';
import { useNow } from '../../lib/useNow';
import { HIGH_GLYPH, LIMIT_GLYPH } from './UsageIndicator';

/** Settings → "Models & cost": the user's own Claude usage in detail (docs/ARCHITECTURE.md §10). */
export function UsageBlock() {
  const { data, error } = useClaudeUsage();
  const now = useNow(30_000, !!data);
  // Rendered only for users with a Claude credential (the hook has no data otherwise).
  return (
    <UsageFrame>
      {data ? <UsageDetails u={data} now={now} /> : error ? <p className="form-error" role="alert">{error.message}</p> : <p className="muted">Loading…</p>}
    </UsageFrame>
  );
}

function UsageFrame({ children }: { children: React.ReactNode }) {
  return (
    <section className="usage-block" id="usage" aria-labelledby="usage-h">
      <h3 id="usage-h">Usage</h3>
      {children}
    </section>
  );
}

function SpendLine({ u }: { u: ClaudeUsage }) {
  return (
    <p className="usage-spend">
      Spent through Agern: today <strong>{money(u.spend.todayUsd)}</strong> · this month <strong>{money(u.spend.monthUsd)}</strong> <span className="muted">(UTC days)</span>
    </p>
  );
}

function UsageDetails({ u, now }: { u: ClaudeUsage; now: number }) {
  if (u.credentialKind === 'api_key') {
    return (
      <>
        <SpendLine u={u} />
        <p className="muted small">
          This is what your agents spent through Agern — not the remaining balance of your Anthropic account, which Agern cannot read with an API key.
        </p>
      </>
    );
  }
  return (
    <>
      {u.windows.length ? (
        <ul className="usage-list">
          {sortWindows(u.windows).map((w) => <WindowRow key={w.type} w={w} now={now} />)}
        </ul>
      ) : (
        <p className="usage-empty">No usage data yet. It appears here once one of your agents has run.</p>
      )}
      <p className="muted small">Claude reports these limits while one of your agents is running; they are not refreshed in between.</p>
      <SpendLine u={u} />
    </>
  );
}

function WindowRow({ w, now }: { w: UsageWindow; now: number }) {
  const reset = isReset(w, now);
  const level = reset ? 'reset' : usageLevel(w);
  const pct = w.utilization != null ? percent(w.utilization) : null;
  return (
    <li className="usage-row" data-level={level}>
      <span className="usage-row-label">{windowLabel(w.type)}</span>
      <span className="usage-row-value">
        {reset ? (
          <span className="usage-row-reset">reset</span>
        ) : (
          <>
            {pct != null ? (
              <>
                <span className="usage-bar" role="img" aria-label={`${pct}% used`}>
                  <span style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />
                </span>
                <span>
                  <strong>{pct}%</strong> used
                </span>
              </>
            ) : (
              level !== 'limit' && <span aria-label="usage unknown">—</span>
            )}
            {level === 'high' && (
              <span className="usage-badge usage-badge--high">
                <span aria-hidden>{HIGH_GLYPH} </span>high
              </span>
            )}
            {level === 'limit' && (
              <span className="usage-badge usage-badge--limit">
                <span aria-hidden>{LIMIT_GLYPH} </span>limit reached
              </span>
            )}
          </>
        )}
      </span>
      <span className="usage-row-meta muted small">
        {!reset && w.resetsAt && (
          <>
            <span title={dateTime(w.resetsAt)}>{resetsIn(w.resetsAt, now)}</span>
            {' · '}
          </>
        )}
        <span title={dateTime(w.observedAt)}>updated {timeAgo(w.observedAt, now)}</span>
      </span>
    </li>
  );
}
