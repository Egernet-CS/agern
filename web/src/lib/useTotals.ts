import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, type AgentSession, type Release, type ReleaseTotals, type Run, type RunTotals } from '../api';
import { useFeed } from './realtime';

/** How long changes are collected before the totals are reloaded. */
const DEBOUNCE_MS = 1000;
/** While a release's window runs, reload at least this often (the cost of agents that finished without a frame). */
const RUNNING_RELOAD_MS = 60_000;

export type Loaded<T> = {
  data: T | undefined;
  /** When [data] was received (`Date.now()`), for ticking its running time. */
  receivedAt: number;
  error: Error | undefined;
  reload: () => void;
};

/**
 * Loads with [fetch] whenever [key] changes (debounced, except the first load) and [reloadKey] changes (at once). A
 * sequence counter drops stale answers; a failed reload keeps the last value. Null [id]: nothing to load.
 */
function useDebouncedTotals<T>(id: string | null, fetch: (id: string) => Promise<T>, key: string, reloadKey: number): Loaded<T> {
  const [state, setState] = useState<{ id: string | null; data: T | undefined; receivedAt: number; error: Error | undefined }>({
    id: null, data: undefined, receivedAt: 0, error: undefined,
  });
  const seq = useRef(0);
  const fetchRef = useRef(fetch);
  fetchRef.current = fetch;

  const load = useCallback(() => {
    if (!id) return;
    const n = ++seq.current;
    fetchRef.current(id).then(
      (data) => {
        if (n === seq.current) setState({ id, data, receivedAt: Date.now(), error: undefined });
      },
      (e: Error) => {
        if (n === seq.current) setState((s) => (s.id === id ? { ...s, error: e } : { id, data: undefined, receivedAt: 0, error: e }));
      },
    );
  }, [id]);

  // First load, a new id, or a feed reconnect: at once.
  useEffect(() => {
    load();
  }, [load, reloadKey]);

  // Changes seen on the feed: debounced.
  const firstKey = useRef(true);
  useEffect(() => {
    if (firstKey.current) {
      firstKey.current = false;
      return;
    }
    const t = setTimeout(load, DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [key, load]);

  const current = state.id === id ? state : { data: undefined, receivedAt: 0, error: undefined };
  return { data: current.data, receivedAt: current.receivedAt, error: current.error, reload: load };
}

/**
 * The time and cost of [release] (`GET /api/releases/{id}/totals`), kept live: reloaded (debounced) when the release
 * changes, when one of its runs or their sessions changes on the feed, after a feed reconnect, and at least once a
 * minute while its window runs. Null [release]: nothing is loaded.
 */
export function useReleaseTotals(release: Release | null | undefined): Loaded<ReleaseTotals> {
  const feed = useFeed();
  const id = release?.id ?? null;
  const [runIds, setRunIds] = useState<Set<string>>(new Set());

  // A signature of everything on the feed that belongs to the release: a change means the totals may have changed.
  const feedKey = useMemo(() => {
    if (!id) return '';
    const parts: string[] = [];
    for (const r of feed.runs.values()) if (r.releaseId === id) parts.push(`r${r.id}:${r.updatedAt}`);
    for (const s of feed.sessions.values()) if (s.runId && runIds.has(s.runId)) parts.push(`s${s.id}:${s.updatedAt}`);
    return parts.sort().join('|');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, feed.version, runIds]);

  const loaded = useDebouncedTotals(id, (x) => api.releaseTotals(x), `${release?.updatedAt ?? ''}#${feedKey}`, feed.reconnects);
  const { data, reload } = loaded;

  useEffect(() => {
    if (data) setRunIds((prev) => (data.runIds.length === prev.size && data.runIds.every((x) => prev.has(x)) ? prev : new Set(data.runIds)));
  }, [data]);

  const running = !!data?.time.running;
  useEffect(() => {
    if (!running) return;
    const t = setInterval(reload, RUNNING_RELOAD_MS);
    return () => clearInterval(t);
  }, [running, reload]);

  return loaded;
}

/**
 * The time and cost of [run] (`GET /api/runs/{id}/totals`): reloaded (debounced) when the run or one of its [sessions]
 * changes, and after a feed reconnect.
 */
export function useRunTotals(run: Run | null | undefined, sessions: AgentSession[]): Loaded<RunTotals> {
  const feed = useFeed();
  const id = run?.id ?? null;
  const sessionsKey = useMemo(
    () => sessions.filter((s) => s.runId === id).map((s) => `${s.id}:${s.updatedAt}`).sort().join('|'),
    [sessions, id],
  );
  return useDebouncedTotals(id, (x) => api.runTotals(x), `${run?.updatedAt ?? ''}#${sessionsKey}`, feed.reconnects);
}
