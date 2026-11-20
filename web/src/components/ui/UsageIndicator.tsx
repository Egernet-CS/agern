import { NavLink } from 'react-router-dom';
import type { ClaudeUsage, UsageWindow } from '../../api';
import { timeAgo } from '../../lib/format';
import { isReset, lastObserved, money, mostConstrained, percent, resetsIn, usageLevel, useClaudeUsage, windowLabel, windowShortLabel } from '../../lib/usage';
import { useNow } from '../../lib/useNow';

/** Glyphs next to the words "high" / "limit reached", so the state never depends on colour alone. */
export const HIGH_GLYPH = '⚠︎';
export const LIMIT_GLYPH = '⊘';

type View = { level: 'none' | 'ok' | 'high' | 'limit' | 'spend'; label: string; body: React.ReactNode };

/** Sentence for the title / aria-label of a window: "5-hour session 42% used, resets in 2h 05m, updated 4m ago". */
function describe(w: UsageWindow, now: number): string {
  const level = usageLevel(w);
  const parts = [
    `${windowLabel(w.type)} ${level === 'limit' ? 'limit reached' : w.utilization != null ? `${percent(w.utilization)}% used` : 'usage unknown'}${level === 'high' ? ' (high)' : ''}`,
  ];
  if (w.resetsAt) parts.push(resetsIn(w.resetsAt, now));
  parts.push(`updated ${timeAgo(w.observedAt, now)}`);
  return parts.join(', ');
}

function view(u: ClaudeUsage, now: number): View | null {
  if (u.credentialKind === 'api_key') {
    const amount = money(u.spend.monthUsd);
    return {
      level: 'spend',
      label: `Claude usage: ${amount} spent through Agern this month (UTC) — not the remaining balance of your Anthropic account`,
      body: (
        <>
          <span className="usage-ind-main">{amount}</span>
          <span className="usage-ind-sub">{' this month'}</span>
        </>
      ),
    };
  }
  if (u.credentialKind !== 'oauth_token') return null;
  if (!u.windows.length) {
    return {
      level: 'none',
      label: 'Claude usage: no data yet — it appears once one of your agents has run',
      body: <span className="usage-ind-main">no usage data</span>,
    };
  }
  if (u.windows.every((w) => isReset(w, now))) {
    return {
      level: 'none',
      label: `Claude usage: all limits have reset since the last update (updated ${timeAgo(lastObserved(u.windows), now)})`,
      body: <span className="usage-ind-main">usage reset</span>,
    };
  }
  const w = mostConstrained(u.windows, now);
  if (!w) {
    return {
      level: 'none',
      label: `Claude usage: no current percentage reported (updated ${timeAgo(lastObserved(u.windows), now)})`,
      body: <span className="usage-ind-main">no usage data</span>,
    };
  }
  const level = usageLevel(w);
  const label = `Claude usage: ${describe(w, now)}`;
  const short = windowShortLabel(w.type);
  if (level === 'limit') {
    return {
      level,
      label,
      body: (
        <>
          <span className="usage-ind-glyph" aria-hidden>{LIMIT_GLYPH}</span>
          <span className="usage-ind-main">{short} limit reached</span>
          {w.resetsAt && <span className="usage-ind-sub">{` · ${resetsIn(w.resetsAt, now)}`}</span>}
        </>
      ),
    };
  }
  return {
    level,
    label,
    body: (
      <>
        {level === 'high' && <span className="usage-ind-glyph" aria-hidden>{HIGH_GLYPH}</span>}
        <span className="usage-ind-main">
          {short} {percent(w.utilization!)}%
        </span>
        {level === 'high' && <span className="usage-ind-word">{' high'}</span>}
      </>
    ),
  };
}

/** The top bar's compact Claude usage (docs/ARCHITECTURE.md §10); links to the details in Settings. */
export function UsageIndicator() {
  const { data } = useClaudeUsage();
  const now = useNow(30_000, !!data);
  if (!data) return null;
  const v = view(data, now);
  if (!v) return null;
  return (
    <NavLink to="/settings#models" className="usage-ind" data-level={v.level} title={v.label} aria-label={v.label}>
      {v.body}
    </NavLink>
  );
}
