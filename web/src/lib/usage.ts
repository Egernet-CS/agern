import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type ClaudeUsage, type UsageWindow } from '../api';
import { useAuth } from './auth';
import { useFeed } from './realtime';
import { useNow } from './useNow';

/**
 * The signed-in user's own Claude usage (`GET /api/me/usage`, docs/ARCHITECTURE.md §10). Reloads on a `usage` feed frame
 * (debounced), after a feed reconnect, when the credential changes and when the UTC day rolls over. A failed reload
 * keeps the last value. `data` is undefined without a Claude credential or before the first successful load.
 */
export function useClaudeUsage(): { data: ClaudeUsage | undefined; error: Error | undefined; loading: boolean } {
  const { me } = useAuth();
  const { subscribeUsage, reconnects } = useFeed();
  const has = !!me?.hasClaudeCredential;
  const kind = me?.claudeCredentialKind ?? null;
  const [data, setData] = useState<ClaudeUsage | undefined>(undefined);
  const [error, setError] = useState<Error | undefined>(undefined);
  const seq = useRef(0);
  const hasRef = useRef(has);
  hasRef.current = has;

  const load = useCallback(() => {
    if (!hasRef.current) return;
    const n = ++seq.current;
    api.usage().then(
      (u) => {
        if (n !== seq.current || !hasRef.current) return;
        setData(u);
        setError(undefined);
      },
      (e: Error) => {
        if (n === seq.current) setError(e);
      },
    );
  }, []);

  useEffect(() => {
    if (!has) {
      seq.current++;
      setData(undefined);
      setError(undefined);
      return;
    }
    load();
  }, [has, kind, reconnects, load]);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const off = subscribeUsage(() => {
      clearTimeout(timer);
      timer = setTimeout(load, 500);
    });
    return () => {
      clearTimeout(timer);
      off();
    };
  }, [subscribeUsage, load]);

  // "Today" belongs to a UTC day: reload when it is over (at most once a minute).
  const now = useNow(30_000, has);
  const lastDayReload = useRef(0);
  useEffect(() => {
    if (!data || utcDay(now) === data.spend.day || now - lastDayReload.current < 60_000) return;
    lastDayReload.current = now;
    load();
  }, [now, data, load]);

  return { data: has ? data : undefined, error, loading: has && !data && !error };
}

const utcDay = (ms: number) => new Date(ms).toISOString().slice(0, 10);

// ---------------------------------------------------------------- presentation rules

export type UsageLevel = 'ok' | 'high' | 'limit';

/** A window whose reset time has passed: shown as "reset", never with its old percentage. */
export const isReset = (w: UsageWindow, now: number) => w.resetsAt != null && Date.parse(w.resetsAt) <= now;

/** `0.42` → `42` (may exceed 100). */
export const percent = (utilization: number) => Math.round(utilization * 100);

/** Level of a window that has not reset. */
export function usageLevel(w: UsageWindow): UsageLevel {
  if (w.status === 'rejected') return 'limit';
  if ((w.utilization != null && w.utilization >= 0.8) || w.status === 'allowed_warning') return 'high';
  return 'ok';
}

/**
 * The window closest to stopping the user's agents: a reached limit (the one that resets last), otherwise the highest
 * utilization. Reset windows and windows without a utilization (and not rejected) are no candidates.
 */
export function mostConstrained(windows: UsageWindow[], now: number): UsageWindow | null {
  const live = windows.filter((w) => !isReset(w, now));
  const limited = live.filter((w) => w.status === 'rejected');
  if (limited.length) {
    const at = (w: UsageWindow) => (w.resetsAt ? Date.parse(w.resetsAt) : -Infinity);
    return limited.reduce((a, b) => (at(b) > at(a) ? b : a));
  }
  let best: UsageWindow | null = null;
  for (const w of live) if (w.utilization != null && (best === null || w.utilization > best.utilization!)) best = w;
  return best;
}

const LABELS: [type: string, long: string, short: string][] = [
  ['five_hour', '5-hour session', '5h'],
  ['seven_day', 'Weekly', '7d'],
  ['seven_day_opus', 'Weekly · Opus', '7d Opus'],
  ['seven_day_sonnet', 'Weekly · Sonnet', '7d Sonnet'],
  ['seven_day_overage_included', 'Weekly · included extra usage', '7d extra'],
  ['overage', 'Extra usage', 'extra'],
];
const generic = (type: string) => type.replace(/_/g, ' ');

export const windowLabel = (type: string) => LABELS.find((l) => l[0] === type)?.[1] ?? generic(type);
export const windowShortLabel = (type: string) => LABELS.find((l) => l[0] === type)?.[2] ?? generic(type);

/** Known windows in a fixed order, unknown ones after them alphabetically. */
export function sortWindows(windows: UsageWindow[]): UsageWindow[] {
  const rank = (t: string) => {
    const i = LABELS.findIndex((l) => l[0] === t);
    return i < 0 ? LABELS.length : i;
  };
  return [...windows].sort((a, b) => rank(a.type) - rank(b.type) || a.type.localeCompare(b.type));
}

/** "resets in 2h 05m" (`under a minute`, `Nm`, `Nh MMm`, from 24 h `Nd Nh`). */
export function resetsIn(resetsAt: string, now: number): string {
  const min = Math.floor((Date.parse(resetsAt) - now) / 60_000);
  if (min < 1) return 'resets in under a minute';
  if (min < 60) return `resets in ${min}m`;
  const h = Math.floor(min / 60);
  if (h < 24) return `resets in ${h}h ${String(min % 60).padStart(2, '0')}m`;
  return `resets in ${Math.floor(h / 24)}d ${h % 24}h`;
}

/** Dollars with exactly two decimals, e.g. `$0.00`, `$12.34`. */
export const money = (usd: number) => `$${usd.toFixed(2)}`;

/** The newest observation of a list of windows (for "updated … ago"). */
export const lastObserved = (windows: UsageWindow[]) =>
  windows.reduce<string | null>((a, w) => (a === null || w.observedAt > a ? w.observedAt : a), null);
