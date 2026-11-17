import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, ApiError, type AgentSession } from '../../api';
import { useMe } from '../../lib/auth';
import { cost, duration, modelLabel } from '../../lib/format';
import { useSessionStream } from '../../lib/realtime';
import { useNow } from '../../lib/useNow';
import { buildTranscript } from './model';
import { Prompt } from './Prompt';
import { TranscriptItems } from './Transcript';

const GLYPHS = ['·', '✢', '✳', '✶', '✻', '✽', '✻', '✶', '✳', '✢'];
const VERBS = ['Forging', 'Welding', 'Riveting', 'Machining', 'Assembling', 'Tempering', 'Soldering', 'Calibrating', 'Milling', 'Gathering acorns', 'Annealing', 'Fettling'];

function Spinner({ since, label, canInterrupt, activity }: { since: string | null; label?: string; canInterrupt: boolean; activity: string | null }) {
  const now = useNow(120);
  const start = since ? Date.parse(since) : now;
  const glyph = GLYPHS[Math.floor(now / 120) % GLYPHS.length];
  const verb = label ?? VERBS[Math.abs(Math.floor(start / 1000)) % VERBS.length];
  return (
    <div className="t-spinner" role="status" aria-live="off">
      <span className="t-spinner-glyph" aria-hidden>{glyph}</span>
      <span className="t-spinner-verb">{verb}…</span>
      <span className="t-spinner-meta">
        ({duration(now - start)}
        {activity ? ` · ${activity}` : ''}
        {canInterrupt ? ' · esc to interrupt' : ''})
      </span>
    </div>
  );
}

export type ChatViewProps = {
  sessionId: string;
  session: AgentSession | undefined;
  /** Shown instead of the prompt when the owner cannot send (e.g. no Claude credential). */
  disabledReason?: ReactNode;
  /** Pipeline stage transcripts: never interactive, no observing banner if it is mine. */
  embedded?: boolean;
  header?: ReactNode;
};

export function ChatView({ sessionId, session, disabledReason, embedded, header }: ChatViewProps) {
  const me = useMe();
  const stream = useSessionStream(sessionId);
  const { items, summary } = useMemo(() => buildTranscript(stream.events, stream.partials), [stream.events, stream.partials]);
  const isOwner = session ? session.owner.username === me.username : false;
  const isChat = session?.kind === 'chat';
  const canDrive = isOwner && isChat && !embedded;
  // The server's session status says whether the agent is mid-turn (`starting`/`running`) or waiting (`idle`).
  // It arrives as `status` events on this session's stream and as feed updates of the session.
  const status = summary.status ?? session?.status ?? null;
  // A stopped chat is not over: the owner's next message resumes the conversation (--resume).
  const ended = !isChat && (status === 'stopped' || status === 'failed' || status === 'done');
  const resumable = isChat && status === 'stopped';
  const working = status === 'running' || status === 'starting';
  const [sending, setSending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // --- scrolling: stick to bottom unless the reader scrolled up
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const onScroll = () => {
    const el = scroller.current;
    if (el) stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  };
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  });
  useEffect(() => {
    stick.current = true;
  }, [sessionId]);

  // Clear optimistic echo once the server's user event arrives.
  useEffect(() => {
    if (sending && stream.events.some((e) => e.type === 'user' && (e.payload as { text?: string })?.text === sending)) setSending(null);
  }, [stream.events, sending]);

  const send = useCallback(
    async (text: string) => {
      setError(null);
      setSending(text);
      stick.current = true;
      try {
        await api.sendInput(sessionId, text);
      } catch (e) {
        setSending(null);
        setError(e instanceof ApiError ? e.message : 'Could not send.');
        return false;
      }
      return true;
    },
    [sessionId],
  );

  const interrupt = useCallback(async () => {
    try {
      await api.interrupt(sessionId);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not interrupt.');
    }
  }, [sessionId]);

  useEffect(() => {
    if (!canDrive || !working) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented && !document.querySelector('dialog[open]')) {
        e.preventDefault();
        void interrupt();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [canDrive, working, interrupt]);

  const history = useMemo(
    () => stream.events.filter((e) => e.type === 'user' && (e.payload as { username?: string })?.username === me.username).map((e) => (e.payload as { text: string }).text),
    [stream.events, me.username],
  );

  const model = summary.model ?? session?.model ?? null;
  // The session's totals span every process of the conversation; a `result` event's numbers are per process / per turn.
  const costUsd = session?.costUsd ?? summary.costUsd ?? null;
  const turns = session?.numTurns ?? summary.turns ?? null;
  const loading = stream.events.length === 0 && stream.conn !== 'live';

  return (
    <section className={`chat ${embedded ? 'chat--embedded' : ''}`} aria-label="Agent transcript">
      {header}
      {session && !isOwner && (
        <div className="chat-banner" role="note">
          <span className="eye" aria-hidden>◉</span> observing <strong>{session.owner.displayName || session.owner.username}</strong>
          <span className="chat-banner-sub"> · read-only — only {session.owner.username} can drive this agent</span>
        </div>
      )}
      <div className="chat-scroll" ref={scroller} onScroll={onScroll} tabIndex={0} aria-label="Transcript">
        <div className="chat-log" aria-live="polite" aria-relevant="additions">
          {loading && <div className="t-meta">connecting to session…</div>}
          {!loading && items.length === 0 && (
            <div className="t-meta">{isChat ? 'Empty session. Say hello — or type /factory help.' : 'Waiting for the agent to start…'}</div>
          )}
          <TranscriptItems items={items} me={me.username} />
          {sending && (
            <div className="t-user t-user--pending">
              <span className="t-prompt" aria-hidden>&gt;</span>
              <div className="t-user-text">{sending}</div>
            </div>
          )}
          {working && (
            <Spinner
              since={summary.turnStartedAt ?? session?.createdAt ?? null}
              canInterrupt={canDrive}
              activity={isChat ? null : session?.lastActivity ?? null}
            />
          )}
        </div>
      </div>
      {error && (
        <div className="chat-error" role="alert">
          {error}
          <button type="button" className="link" onClick={() => setError(null)}>dismiss</button>
        </div>
      )}
      {canDrive && resumable && !disabledReason && (
        <div className="chat-resume muted small" role="note">This chat’s agent was stopped. Your next message resumes the conversation.</div>
      )}
      {canDrive && !ended && !disabledReason && <Prompt onSubmit={send} onInterrupt={interrupt} working={working} history={history} />}
      {canDrive && !ended && disabledReason && <div className="chat-disabled">{disabledReason}</div>}
      {canDrive && ended && <div className="chat-disabled">This session has {status}.</div>}
      <footer className="chat-footer">
        <span className="cf-item"><span className="cf-k">model</span> {modelLabel(model)}</span>
        <span className="cf-item"><span className="cf-k">cost</span> {cost(costUsd)}</span>
        <span className="cf-item"><span className="cf-k">turns</span> {turns ?? '—'}</span>
        <span className={`cf-conn cf-conn--${stream.conn}`}>
          <span aria-hidden>●</span> {stream.conn === 'live' ? 'live' : stream.conn}
        </span>
        {canDrive && !ended && !disabledReason && (
          <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · esc interrupt · / commands</span>
        )}
      </footer>
    </section>
  );
}
