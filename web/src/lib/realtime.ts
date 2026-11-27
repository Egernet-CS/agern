import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useAsync } from './async';
import { isThinkingTokens, isToolProgress } from '../components/chat/model';
import { api, type AgentSession, type Channel, type FactoryEvent, type Issue, type Release, type Run, type SandboxApproval, type SandboxStatus } from '../api';

/** `closed`: the server ended the channel for good (e.g. the chat was closed); it is not reopened. */
export type ConnState = 'connecting' | 'live' | 'reconnecting' | 'closed';

type CloseInfo = { code: number; reason: string };

/**
 * Opens a channel and reopens it with backoff when it drops. `open` is re-read on every attempt. When `isFinal`
 * returns true for a close, the channel is not reopened and the state becomes `closed`.
 */
function useReconnecting(
  open: (h: { onOpen: () => void; onClose: (info?: CloseInfo) => void }) => Channel | null,
  key: string | null,
  isFinal?: (info: CloseInfo) => boolean,
): ConnState {
  const [state, setState] = useState<ConnState>('connecting');
  const openRef = useRef(open);
  openRef.current = open;
  const finalRef = useRef(isFinal);
  finalRef.current = isFinal;
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
        onClose: (info) => {
          if (disposed) return;
          if (info && finalRef.current?.(info)) {
            setState('closed');
            return;
          }
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

/** The tool_use_ids answered by the tool_result blocks of a stream-json `user` message. */
function toolResultIds(payload: unknown): Set<string> {
  const content = (payload as { message?: { content?: unknown } } | null)?.message?.content;
  const ids = new Set<string>();
  if (Array.isArray(content)) {
    for (const b of content as { type?: unknown; tool_use_id?: unknown }[]) {
      if (b && b.type === 'tool_result' && typeof b.tool_use_id === 'string') ids.add(b.tool_use_id);
    }
  }
  return ids;
}

export type SessionStream = {
  events: FactoryEvent[];
  /**
   * Live-only payloads for the message currently being generated: stream_event partials, plus at most one
   * `system/thinking_tokens` progress event (the latest) and the latest `tool_progress` heartbeat per running tool call.
   */
  partials: unknown[];
  conn: ConnState;
  lastSeq: number;
};

/** WebSocket close codes for a session that does not exist (any more): 4404 closed while watched, 1008 refused at open. */
const SESSION_GONE = new Set([4404, 1008]);

/**
 * Persisted events + live partials for one session. Reconnects with ?after=<lastSeq>, except when the server says the
 * session is gone: then the stream stops (`conn: 'closed'`) and the session is marked closed on the feed.
 */
export function useSessionStream(sessionId: string | null): SessionStream {
  const { markSessionClosed } = useFeed();
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
                // Thinking progress: only the latest estimate matters.
                if (isThinkingTokens(p)) setPartials((prev) => [...prev.filter((x) => !isThinkingTokens(x)), p]);
                // Tool heartbeats: only the latest per tool call matters.
                else if (isToolProgress(p)) setPartials((prev) => [...prev.filter((x) => !(isToolProgress(x) && x.tool_use_id === p.tool_use_id)), p]);
                else if (p?.event?.type === 'message_start') setPartials([p]);
                else setPartials((prev) => [...prev, p]);
                return;
              }
              if (ev.seq <= lastSeq.current) return; // replay overlap
              lastSeq.current = ev.seq;
              const t = (ev.payload as { type?: string } | null)?.type;
              if (ev.type === 'claude' && (t === 'result' || t === 'assistant')) {
                if (t === 'result') setPartials([]);
                // The thinking is over once the message arrives.
                else setPartials((prev) => (prev.some(isThinkingTokens) ? prev.filter((x) => !isThinkingTokens(x)) : prev));
              }
              // A tool result ends its call's heartbeats (and those that named no call).
              if (ev.type === 'claude' && t === 'user') {
                const done = toolResultIds(ev.payload);
                if (done.size) setPartials((prev) => (prev.some(isToolProgress) ? prev.filter((x) => !(isToolProgress(x) && (x.tool_use_id == null || done.has(String(x.tool_use_id))))) : prev));
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
    (info) => {
      if (!SESSION_GONE.has(info.code)) return false;
      if (sessionId) markSessionClosed(sessionId);
      return true;
    },
  );

  useEffect(() => () => {
    if (flushTimer.current !== null) clearTimeout(flushTimer.current);
  }, []);

  return { events, partials, conn, lastSeq: lastSeq.current };
}

// ---------------------------------------------------------------- global feed

type IssueListener = (repo: string, issue: Issue) => void;
type SandboxApprovalListener = (repo: string, approval: SandboxApproval | null) => void;
type SandboxesListener = (runId: string, change: 'created' | 'removed' | 'stopped' | 'prepared') => void;
type UsageListener = () => void;

type FeedState = {
  sessions: Map<string, AgentSession>;
  runs: Map<string, Run>;
  version: number;
  conn: ConnState;
  /** Increments each time the feed is re-established after a drop (not on the first open). The feed has no replay, so lists derived from it should reload when this changes. */
  reconnects: number;
  /** Registers a listener for `issue` frames; returns the unsubscribe function. Stable identity. */
  subscribeIssues: (fn: IssueListener) => () => void;
  /** Registers a listener for `sandbox_approval` frames; returns the unsubscribe function. Stable identity. */
  subscribeSandboxApprovals: (fn: SandboxApprovalListener) => () => void;
  /** Registers a listener for `sandboxes` frames (admins only: a sandbox container came or went); returns the unsubscribe function. */
  subscribeSandboxes: (fn: SandboxesListener) => () => void;
  /** Registers a listener for `usage` frames (the user's own Claude usage changed); returns the unsubscribe function. Stable identity. */
  subscribeUsage: (fn: UsageListener) => () => void;
  /** Ids of chats closed while this app was open (tombstones: they never reappear). */
  closedSessions: Set<string>;
  /** Records a chat as closed and drops it from the live sessions. Stable identity. */
  markSessionClosed: (id: string) => void;
  /** Latest known version of each release (from the feed and from this client's own changes). */
  releases: Map<string, Release>;
  /** Ids of releases deleted while this app was open (tombstones: they never reappear). */
  deletedReleases: Set<string>;
  /** Records releases this client received from an API answer, so every view shows them at once. Stable identity. */
  noteReleases: (list: Release[]) => void;
  /** Records a release as deleted. Stable identity. */
  markReleaseDeleted: (id: string) => void;
  /** A counter that grows with every release version recorded; read it before fetching a release list. Stable identity. */
  releaseMark: () => number;
  /** When each release's latest version was recorded, as a [releaseMark] value. */
  releaseSeen: Map<string, number>;
};

const FeedContext = createContext<FeedState>({
  sessions: new Map(),
  runs: new Map(),
  version: 0,
  conn: 'connecting',
  reconnects: 0,
  subscribeIssues: () => () => {},
  subscribeSandboxApprovals: () => () => {},
  subscribeSandboxes: () => () => {},
  subscribeUsage: () => () => {},
  closedSessions: new Set(),
  markSessionClosed: () => {},
  releases: new Map(),
  deletedReleases: new Set(),
  noteReleases: () => {},
  markReleaseDeleted: () => {},
  releaseMark: () => 0,
  releaseSeen: new Map(),
});

export function FeedProvider({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  const store = useRef({
    sessions: new Map<string, AgentSession>(),
    runs: new Map<string, Run>(),
    closed: new Set<string>(),
    releases: new Map<string, Release>(),
    deletedReleases: new Set<string>(),
    releaseSeen: new Map<string, number>(),
    releaseSeq: 0,
  });
  // Issue frames are events, not state: issues can change on GitHub without a frame, so a stored copy would go stale.
  const issueListeners = useRef(new Set<IssueListener>());
  const subscribeIssues = useCallback((fn: IssueListener) => {
    issueListeners.current.add(fn);
    return () => {
      issueListeners.current.delete(fn);
    };
  }, []);
  const approvalListeners = useRef(new Set<SandboxApprovalListener>());
  const subscribeSandboxApprovals = useCallback((fn: SandboxApprovalListener) => {
    approvalListeners.current.add(fn);
    return () => {
      approvalListeners.current.delete(fn);
    };
  }, []);
  const sandboxesListeners = useRef(new Set<SandboxesListener>());
  const subscribeSandboxes = useCallback((fn: SandboxesListener) => {
    sandboxesListeners.current.add(fn);
    return () => {
      sandboxesListeners.current.delete(fn);
    };
  }, []);
  const usageListeners = useRef(new Set<UsageListener>());
  const subscribeUsage = useCallback((fn: UsageListener) => {
    usageListeners.current.add(fn);
    return () => {
      usageListeners.current.delete(fn);
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
  const markSessionClosed = useCallback((id: string) => {
    if (store.current.closed.has(id)) return;
    store.current.closed.add(id);
    store.current.sessions.delete(id);
    // Not batched: lists and the open chat should react at once.
    setVersion((v) => v + 1);
  }, []);
  // Release changes are rare and user-driven: apply them at once rather than batched.
  const noteReleases = useCallback((list: Release[]) => {
    let changed = false;
    for (const r of list) {
      if (store.current.deletedReleases.has(r.id)) continue;
      const known = store.current.releases.get(r.id);
      if (known && known.updatedAt >= r.updatedAt) continue;
      store.current.releases.set(r.id, r);
      store.current.releaseSeen.set(r.id, ++store.current.releaseSeq);
      changed = true;
    }
    if (changed) setVersion((v) => v + 1);
  }, []);
  const markReleaseDeleted = useCallback((id: string) => {
    if (store.current.deletedReleases.has(id)) return;
    store.current.deletedReleases.add(id);
    store.current.releases.delete(id);
    store.current.releaseSeen.delete(id);
    setVersion((v) => v + 1);
  }, []);
  const releaseMark = useCallback(() => store.current.releaseSeq, []);
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
          if (f.type === 'sandbox_approval') {
            for (const fn of approvalListeners.current) fn(f.repo, f.approval);
            return;
          }
          if (f.type === 'sandboxes') {
            for (const fn of sandboxesListeners.current) fn(f.runId, f.change);
            return;
          }
          if (f.type === 'usage') {
            for (const fn of usageListeners.current) fn();
            return;
          }
          if (f.type === 'session_closed') {
            markSessionClosed(f.sessionId);
            return;
          }
          if (f.type === 'release') {
            noteReleases([f.release]);
            return;
          }
          if (f.type === 'release_deleted') {
            markReleaseDeleted(f.releaseId);
            return;
          }
          if (f.type === 'session') {
            if (store.current.closed.has(f.session.id)) return;
            store.current.sessions.set(f.session.id, f.session);
          } else if (f.type === 'run') store.current.runs.set(f.run.id, f.run);
          bump();
        },
      }),
    enabled ? 'feed' : null,
  );
  const value = useMemo(
    () => ({
      sessions: store.current.sessions,
      runs: store.current.runs,
      version,
      conn,
      reconnects,
      subscribeIssues,
      subscribeSandboxApprovals,
      subscribeSandboxes,
      subscribeUsage,
      closedSessions: store.current.closed,
      markSessionClosed,
      releases: store.current.releases,
      deletedReleases: store.current.deletedReleases,
      noteReleases,
      markReleaseDeleted,
      releaseMark,
      releaseSeen: store.current.releaseSeen,
    }),
    [version, conn, reconnects, subscribeIssues, subscribeSandboxApprovals, subscribeSandboxes, subscribeUsage, markSessionClosed, noteReleases, markReleaseDeleted, releaseMark],
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

/** Calls `onChange` whenever an admin approves or revokes `repo`'s sandbox definition while mounted. */
export function useSandboxApprovalFeed(repo: string, onChange: (approval: SandboxApproval | null) => void) {
  const { subscribeSandboxApprovals } = useFeed();
  const cb = useRef(onChange);
  cb.current = onChange;
  useEffect(
    () =>
      subscribeSandboxApprovals((r, approval) => {
        if (r === repo) cb.current(approval);
      }),
    [repo, subscribeSandboxApprovals],
  );
}

/** Calls `onChange` whenever a sandbox container is created or removed on the server (admins only receive these). */
export function useSandboxesFeed(onChange: (runId: string, change: 'created' | 'removed' | 'stopped' | 'prepared') => void) {
  const { subscribeSandboxes } = useFeed();
  const cb = useRef(onChange);
  cb.current = onChange;
  useEffect(() => subscribeSandboxes((runId, change) => cb.current(runId, change)), [subscribeSandboxes]);
}

/**
 * Applies a new approval to a fetched status: the state follows from whether the approval is of this exact content
 * (an invalid or missing file stays as it is).
 */
export function withApproval(st: SandboxStatus, approval: SandboxApproval | null): SandboxStatus {
  if (st.state === 'none' || st.state === 'invalid') return { ...st, approval };
  return { ...st, approval, state: approval && approval.sha256 === st.sha256 ? 'approved' : 'pending' };
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
    const map = new Map((base ?? []).filter((s) => !feed.closedSessions.has(s.id)).map((s) => [s.id, s]));
    for (const [id, s] of feed.sessions) {
      if (feed.closedSessions.has(id)) continue;
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

/**
 * The releases of `repo`: the fetched list overlaid with newer versions from the feed (or this client's own changes),
 * plus releases that appeared later, minus deleted ones. Newest first. `since` is the [FeedState.releaseMark] read
 * before `base` was fetched: a release known before then but missing from `base` was deleted meanwhile (e.g. while
 * the feed was down), so it is not added back.
 */
export function useLiveReleases(base: Release[] | undefined, repo: string, since = -1): Release[] {
  const feed = useFeed();
  return useMemo(() => {
    const map = new Map((base ?? []).filter((r) => !feed.deletedReleases.has(r.id)).map((r) => [r.id, r]));
    for (const [id, r] of feed.releases) {
      if (r.repo !== repo || feed.deletedReleases.has(id)) continue;
      const known = map.get(id);
      if (known) map.set(id, newer(known, r));
      else if ((feed.releaseSeen.get(id) ?? 0) > since) map.set(id, r);
    }
    return [...map.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [base, repo, feed.version]);
}

/** The newest known version of one release, and whether it was deleted while this app was open. */
export function useLiveRelease(release: Release | undefined): { release: Release | undefined; deleted: boolean } {
  const feed = useFeed();
  if (!release) return { release, deleted: false };
  return { release: newer(release, feed.releases.get(release.id)), deleted: feed.deletedReleases.has(release.id) };
}

/** Loads the releases of `repo` (again after a feed reconnect) and keeps them live. */
export function useProjectReleases(repo: string) {
  const feed = useFeed();
  const q = useAsync(async () => {
    const since = feed.releaseMark();
    return { list: await api.releases(repo), since };
  }, [repo, feed.reconnects]);
  const releases = useLiveReleases(q.data?.list, repo, q.data?.since ?? Number.MAX_SAFE_INTEGER);
  return { releases, loading: !q.data && q.loading, error: q.error, reload: q.reload };
}
