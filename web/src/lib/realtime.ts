import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, type AgentSession, type Channel, type FactoryEvent, type Run } from '../api';

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

type FeedState = {
  sessions: Map<string, AgentSession>;
  runs: Map<string, Run>;
  version: number;
  conn: ConnState;
};

const FeedContext = createContext<FeedState>({ sessions: new Map(), runs: new Map(), version: 0, conn: 'connecting' });

export function FeedProvider({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  const store = useRef({ sessions: new Map<string, AgentSession>(), runs: new Map<string, Run>() });
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
        onFrame: (f) => {
          if (f.type === 'session') store.current.sessions.set(f.session.id, f.session);
          else if (f.type === 'run') store.current.runs.set(f.run.id, f.run);
          bump();
        },
      }),
    enabled ? 'feed' : null,
  );
  const value = useMemo(
    () => ({ sessions: store.current.sessions, runs: store.current.runs, version, conn }),
    [version, conn],
  );
  return createElement(FeedContext.Provider, { value }, children);
}

export const useFeed = () => useContext(FeedContext);

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
