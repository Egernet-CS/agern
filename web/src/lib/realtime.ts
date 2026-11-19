import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, type AgentSession, type Channel, type FactoryEvent, type Issue, type Run } from '../api';

export type ConnState = 'connecting' | 'live' | 'reconnecting';

/** Opens a channel and reopens it with backoff when it drops. `open` is re-read on every attempt. */
function useReconnecting(open: (h: { onOpen: () => void; onClose: () => void }) => Channel | null, key: string | null): ConnState {
  const [state, setState] = useState<ConnState>('connecting');
  const openRef = useRef(open);
  openRef.current = open;
  useEffect(() => {
    if (key === null) return;
    let ch: Channel | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;
    let disposed = false;
    const connect = () => {
      ch = openRef.current({
        onOpen: () => {
          attempt = 0;
          setState('live');
        },
        onClose: () => {
          if (disposed) return;
          setState('reconnecting');
          const delay = Math.min(10_000, 500 * 2 ** attempt++);
          timer = setTimeout(connect, delay);
        },
      });
    };
    setState('connecting');
    connect();
    return () => {
      disposed = true;
      clearTimeout(timer);
      ch?.close();
    };
  }, [key]);
  return state;
}

export type SessionStream = {
  events: FactoryEvent[];
  /** Live-only stream_event payloads for the message currently being generated. */
  partials: unknown[];
  conn: ConnState;
  lastSeq: number;
};

/** Persisted events + live partials for one session. Reconnects with ?after=<lastSeq>. */
export function useSessionStream(sessionId: string | null): SessionStream {
  const [events, setEvents] = useState<FactoryEvent[]>([]);
  const [partials, setPartials] = useState<unknown[]>([]);
  const lastSeq = useRef(0);

  const pending = useRef<FactoryEvent[]>([]);
  useEffect(() => {
    setEvents([]);
    setPartials([]);
    lastSeq.current = 0;
    pending.current = [];
  }, [sessionId]);

  const flushTimer = useRef<number | null>(null);
  const flush = useCallback(() => {
    flushTimer.current = null;
    const batch = pending.current;
    pending.current = [];
    if (batch.length) setEvents((prev) => prev.concat(batch));
  }, []);

  const conn = useReconnecting(
    (h) =>
      sessionId
        ? api.openSessionSocket(sessionId, lastSeq.current, {
            onOpen: h.onOpen,
            onClose: h.onClose,
            onFrame: (ev) => {
              if (ev.seq === -1) {
                const p = ev.payload as { event?: { type?: string } } | null;
                if (p?.event?.type === 'message_start') setPartials([p]);
                else setPartials((prev) => [...prev, p]);
                return;
              }
              if (ev.seq <= lastSeq.current) return; // replay overlap
              lastSeq.current = ev.seq;
              const t = (ev.payload as { type?: string } | null)?.type;
              if (ev.type === 'claude' && (t === 'result' || t === 'assistant')) {
                if (t === 'result') setPartials([]);
              }
              // A turn's end clears the live partials; `running` may arrive after the first deltas, so it must not.
              if (ev.type === 'status') {
                const st = (ev.payload as { status?: string } | null)?.status;
                if (st !== 'running' && st !== 'starting') setPartials([]);
              }
              pending.current.push(ev);
              // Batch the replay burst into one render.
              if (flushTimer.current === null) flushTimer.current = window.setTimeout(flush, 16);
            },
          })
        : null,
    sessionId,
  );

  useEffect(() => () => {
    if (flushTimer.current !== null) clearTimeout(flushTimer.current);
  }, []);

  return { events, partials, conn, lastSeq: lastSeq.current };
}

// ---------------------------------------------------------------- global feed

type IssueListener = (repo: string, issue: Issue) => void;

type FeedState = {
  sessions: Map<string, AgentSession>;
  runs: Map<string, Run>;
  version: number;
  conn: ConnState;
  /** Increments each time the feed is re-established after a drop (not on the first open). The feed has no replay, so lists derived from it should reload when this changes. */
  reconnects: number;
  /** Registers a listener for `issue` frames; returns the unsubscribe function. Stable identity. */
  subscribeIssues: (fn: IssueListener) => () => void;
};

const FeedContext = createContext<FeedState>({
  sessions: new Map(),
  runs: new Map(),
  version: 0,
  conn: 'connecting',
  reconnects: 0,
  subscribeIssues: () => () => {},
});

export function FeedProvider({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  const store = useRef({ sessions: new Map<string, AgentSession>(), runs: new Map<string, Run>() });
  // Issue frames are events, not state: issues can change on GitHub without a frame, so a stored copy would go stale.
  const issueListeners = useRef(new Set<IssueListener>());
  const subscribeIssues = useCallback((fn: IssueListener) => {
    issueListeners.current.add(fn);
    return () => {
      issueListeners.current.delete(fn);
    };
  }, []);
  const opened = useRef(false);
  const [reconnects, setReconnects] = useState(0);
  const [version, setVersion] = useState(0);
  const raf = useRef<number | null>(null);
  const bump = () => {
    if (raf.current !== null) return;
    raf.current = window.setTimeout(() => {
      raf.current = null;
      setVersion((v) => v + 1);
    }, 100);
  };
  const conn = useReconnecting(
    (h) =>
      api.openFeed({
        ...h,
        onOpen: () => {
          h.onOpen();
          if (opened.current) setReconnects((n) => n + 1);
          opened.current = true;
        },
        onFrame: (f) => {
          if (f.type === 'issue') {
            for (const fn of issueListeners.current) fn(f.repo, f.issue);
            return;
          }
          if (f.type === 'session') store.current.sessions.set(f.session.id, f.session);
          else if (f.type === 'run') store.current.runs.set(f.run.id, f.run);
          bump();
        },
      }),
    enabled ? 'feed' : null,
  );
  const value = useMemo(
    () => ({ sessions: store.current.sessions, runs: store.current.runs, version, conn, reconnects, subscribeIssues }),
    [version, conn, reconnects, subscribeIssues],
  );
  return createElement(FeedContext.Provider, { value }, children);
}

export const useFeed = () => useContext(FeedContext);

/** Number of feed reconnects so far; use it as a reload dependency for data the feed keeps live. */
export const useFeedReconnects = () => useContext(FeedContext).reconnects;

/** Calls `onIssue` for every issue created through Agern in `repo` while mounted. The latest callback is always used. */
export function useIssueFeed(repo: string, onIssue: (issue: Issue) => void) {
  const { subscribeIssues } = useFeed();
  const cb = useRef(onIssue);
  cb.current = onIssue;
  useEffect(
    () =>
      subscribeIssues((r, issue) => {
        if (r === repo) cb.current(issue);
      }),
    [repo, subscribeIssues],
  );
}

/** The feed version wins unless the fetched one is strictly newer (the server bumps updatedAt on every change, stages included). */
const newer = <T extends { updatedAt: string }>(a: T, b: T | undefined) => (b && b.updatedAt >= a.updatedAt ? b : a);

/** Overlays live feed updates onto a fetched list; optionally adds items that appear later. */
export function useLiveRuns(base: Run[] | undefined, include?: (r: Run) => boolean): Run[] {
  const feed = useFeed();
  return useMemo(() => {
    const map = new Map((base ?? []).map((r) => [r.id, r]));
    for (const [id, r] of feed.runs) {
      if (map.has(id)) map.set(id, newer(map.get(id)!, r));
      else if (include?.(r)) map.set(id, r);
    }
    return [...map.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [base, feed.version]);
}

export function useLiveSessions(base: AgentSession[] | undefined, include?: (s: AgentSession) => boolean): AgentSession[] {
  const feed = useFeed();
  return useMemo(() => {
    const map = new Map((base ?? []).map((s) => [s.id, s]));
    for (const [id, s] of feed.sessions) {
      if (map.has(id)) map.set(id, newer(map.get(id)!, s));
      else if (include?.(s)) map.set(id, s);
    }
    return [...map.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [base, feed.version]);
}

export function useLiveRun(run: Run | undefined): Run | undefined {
  const feed = useFeed();
  if (!run) return run;
  return newer(run, feed.runs.get(run.id));
}

export function useLiveSession(s: AgentSession | undefined): AgentSession | undefined {
  const feed = useFeed();
  if (!s) return s;
  return newer(s, feed.sessions.get(s.id));
}
