import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type DragEvent as ReactDragEvent, type ReactNode } from 'react';
import { api, ApiError, STEERABLE_ROLES, type AgentSession, type Attachment, type StageRole } from '../../api';
import { useMe } from '../../lib/auth';
import { cost, duration, modelLabel } from '../../lib/format';
import { useSessionStream } from '../../lib/realtime';
import { useNow } from '../../lib/useNow';
import { DraftChips, MessageAttachments, useAttachmentDrafts } from './attachments';
import { attachmentsOf, buildTranscript } from './model';
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
  /**
   * Pipeline stage transcripts: no chat input, no observing banner if it is mine. The owner may message a running
   * spec-writer or builder through a text-only composer.
   */
  embedded?: boolean;
  header?: ReactNode;
  /**
   * A text-only composer for an embedded interactive agent other than a chat (the run assistant): its owner sends
   * through [ComposeOverride.send] instead of the session's input endpoint. Absent: no composer.
   */
  compose?: ComposeOverride;
  /** Shown where the composer would be (e.g. why a conversation has ended). */
  endedNote?: ReactNode;
};

export type ComposeOverride = {
  send: (text: string) => Promise<void>;
  /** Why sending is not possible right now (e.g. the reviewers are checking a change). */
  blocked?: string | null;
  label: string;
  placeholder: string;
  /** Shown above the composer. */
  note?: ReactNode;
};

export function ChatView({ sessionId, session, disabledReason, embedded, header, compose, endedNote }: ChatViewProps) {
  const me = useMe();
  const stream = useSessionStream(sessionId);
  const { items, summary } = useMemo(() => buildTranscript(stream.events, stream.partials), [stream.events, stream.partials]);
  const isOwner = session ? session.owner.username === me.username : false;
  const isChat = session?.kind === 'chat';
  // The run assistant is interactive like a chat, but driven through the run (a compose override).
  const isAssistant = session?.kind === 'assistant';
  const interactive = isChat || isAssistant;
  const canDrive = isOwner && ((isChat && !embedded) || (isAssistant && !!compose));
  // The server's session status says whether the agent is mid-turn (`starting`/`running`) or waiting (`idle`).
  // It arrives as `status` events on this session's stream and as feed updates of the session.
  const status = summary.status ?? session?.status ?? null;
  // A stopped chat (or run assistant) is not over: the owner's next message resumes the conversation (--resume).
  const ended = !interactive && (status === 'stopped' || status === 'failed' || status === 'done');
  const resumable = interactive && status === 'stopped';
  const working = status === 'running' || status === 'starting';
  const [sending, setSending] = useState<{ text: string; attachments: Attachment[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The chat composer (attachments, /factory commands); the run assistant has its own text-only one.
  const canCompose = canDrive && isChat && !ended && !disabledReason;
  // Messages to a working pipeline agent: its owner only, spec-writer / builder only, only while it runs.
  const stageRole = !isChat && session ? (session.kind as StageRole) : null;
  const canSteer = isOwner && !!stageRole && STEERABLE_ROLES.includes(stageRole) && status === 'running' && session?.live !== false;
  const att = useAttachmentDrafts(sessionId, canCompose);

  // --- scrolling: stick to the bottom while the agent streams, unless the reader scrolled up
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const [atBottom, setAtBottom] = useState(true);
  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    setAtBottom(stick.current);
  };
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  });
  // The pane shrinks when the composer grows (chips, more lines): keep the bottom in view.
  useEffect(() => {
    const el = scroller.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => {
      if (stick.current) el.scrollTop = el.scrollHeight;
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  useEffect(() => {
    stick.current = true;
    setAtBottom(true);
  }, [sessionId]);
  const jumpToLatest = () => {
    const el = scroller.current;
    stick.current = true;
    setAtBottom(true);
    el?.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };

  // Clear optimistic echo once the server's user event arrives.
  useEffect(() => {
    if (!sending) return;
    const ids = sending.attachments.map((a) => a.id).join();
    const arrived = stream.events.some((e) => {
      if (e.type !== 'user') return false;
      const p = e.payload as { text?: string; attachments?: unknown };
      return p?.text === sending.text && attachmentsOf(p.attachments).map((a) => a.id).join() === ids;
    });
    if (arrived) setSending(null);
  }, [stream.events, sending]);

  const send = useCallback(
    async (text: string) => {
      setError(null);
      const attachments = att.ready;
      if (attachments.length && text.trim().startsWith('/factory')) {
        setError('/factory commands do not take attachments. Remove them, or send them in a normal message.');
        return false;
      }
      setSending({ text, attachments });
      stick.current = true;
      try {
        await api.sendInput(sessionId, text, attachments.map((a) => a.id));
      } catch (e) {
        setSending(null);
        setError(e instanceof ApiError ? e.message : 'Could not send.');
        return false;
      }
      att.clearSent();
      return true;
    },
    [sessionId, att],
  );

  const steer = useCallback(
    async (text: string) => {
      setError(null);
      setSending({ text, attachments: [] });
      stick.current = true;
      try {
        await api.sendToAgent(sessionId, text);
      } catch (e) {
        setSending(null);
        setError(e instanceof ApiError ? e.message : 'Could not send.');
        return false;
      }
      return true;
    },
    [sessionId],
  );

  const sendOverride = useCallback(
    async (text: string) => {
      if (!compose) return false;
      setError(null);
      setSending({ text, attachments: [] });
      stick.current = true;
      try {
        await compose.send(text);
      } catch (e) {
        setSending(null);
        setError(e instanceof ApiError ? e.message : 'Could not send.');
        return false;
      }
      return true;
    },
    [compose],
  );

  // --- drag and drop anywhere on the chat pane (owner only)
  const [dropping, setDropping] = useState(false);
  const dragDepth = useRef(0);
  const hasFiles = (e: ReactDragEvent) => Array.from(e.dataTransfer?.types ?? []).includes('Files');
  const dropHandlers = canCompose
    ? {
        onDragEnter: (e: ReactDragEvent) => {
          if (!hasFiles(e)) return;
          e.preventDefault();
          dragDepth.current++;
          setDropping(true);
        },
        onDragOver: (e: ReactDragEvent) => {
          if (!hasFiles(e)) return;
          e.preventDefault();
          e.dataTransfer.dropEffect = 'copy';
        },
        onDragLeave: (e: ReactDragEvent) => {
          if (!hasFiles(e)) return;
          dragDepth.current = Math.max(0, dragDepth.current - 1);
          if (dragDepth.current === 0) setDropping(false);
        },
        onDrop: (e: ReactDragEvent) => {
          if (!hasFiles(e)) return;
          e.preventDefault();
          dragDepth.current = 0;
          setDropping(false);
          att.add(Array.from(e.dataTransfer.files));
        },
      }
    : {};

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
    <section className={`chat ${embedded ? 'chat--embedded' : ''} ${dropping ? 'is-dropping' : ''}`} aria-label="Agent transcript" {...dropHandlers}>
      {dropping && (
        <div className="chat-drop" aria-hidden>
          <span>Drop files to attach</span>
        </div>
      )}
      {header}
      {session && !isOwner && (
        <div className="chat-banner" role="note">
          <span className="eye" aria-hidden>◉</span> observing <strong>{session.owner.displayName || session.owner.username}</strong>
          <span className="chat-banner-sub"> · read-only — only {session.owner.username} can drive this agent</span>
        </div>
      )}
      <div className="chat-scroll-wrap">
      <div className="chat-scroll" ref={scroller} onScroll={onScroll} tabIndex={0} aria-label="Transcript">
        <div className="chat-log" aria-live="polite" aria-relevant="additions">
          {loading && <div className="t-meta">connecting to session…</div>}
          {!loading && items.length === 0 && (
            <div className="t-meta">{isChat ? 'Empty session. Say hello — or type /factory help.' : 'Waiting for the agent to start…'}</div>
          )}
          <TranscriptItems items={items} me={me.username} sessionId={sessionId} />
          {sending && (
            <div className="t-user t-user--pending">
              <span className="t-prompt" aria-hidden>&gt;</span>
              <div className="t-user-body">
                {sending.text && <div className="t-user-text">{sending.text}</div>}
                <MessageAttachments sessionId={sessionId} list={sending.attachments} />
              </div>
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
      {!atBottom && (
        <button type="button" className="chat-jump" onClick={jumpToLatest}>
          ↓ jump to latest
        </button>
      )}
      </div>
      {error && (
        <div className="chat-error" role="alert">
          {error}
          <button type="button" className="link" onClick={() => setError(null)}>dismiss</button>
        </div>
      )}
      {canDrive && resumable && !disabledReason && (
        <div className="chat-resume muted small" role="note">
          {isChat ? 'This chat’s agent was stopped.' : 'The agent was stopped.'} Your next message resumes the conversation.
        </div>
      )}
      {canDrive && isAssistant && compose && !disabledReason && (
        <>
          {compose.note && <div className="chat-steer-note muted" role="note">{compose.note}</div>}
          <Prompt
            onSubmit={sendOverride}
            onInterrupt={interrupt}
            working={working}
            commands={false}
            label={compose.label}
            placeholder={compose.placeholder}
            history={history}
            blocked={compose.blocked ?? null}
          />
        </>
      )}
      {endedNote && <div className="chat-disabled">{endedNote}</div>}
      {canCompose && (
        <Prompt
          onSubmit={send}
          onInterrupt={interrupt}
          working={working}
          history={history}
          attachments={<DraftChips drafts={att.drafts} onRemove={att.remove} />}
          onFiles={att.add}
          hasAttachments={att.ready.length > 0}
          blocked={att.uploading ? 'Waiting for uploads to finish…' : att.failed ? 'Remove the attachments that failed before sending.' : null}
        />
      )}
      {canSteer && stageRole && (
        <>
          <div className="chat-steer-note muted" role="note">
            Message this {stageRole} while it works: your message is queued and picked up at its next step (after its current tool
            call). It is not posted on GitHub.
          </div>
          <Prompt
            onSubmit={steer}
            working={working}
            sendWhileWorking
            commands={false}
            label={`Message the ${stageRole}`}
            placeholder={`Tell the ${stageRole} something — queued for its next step`}
            history={history}
          />
        </>
      )}
      {canDrive && !ended && disabledReason && <div className="chat-disabled">{disabledReason}</div>}
      {canDrive && ended && <div className="chat-disabled">This session has {status}.</div>}
      <footer className="chat-footer">
        <span className="cf-item"><span className="cf-k">model</span> {modelLabel(model)}</span>
        <span className="cf-item"><span className="cf-k">cost</span> {cost(costUsd)}</span>
        <span className="cf-item"><span className="cf-k">turns</span> {turns ?? '—'}</span>
        <span className={`cf-conn cf-conn--${stream.conn}`}>
          <span aria-hidden>●</span> {stream.conn === 'live' ? 'live' : stream.conn}
        </span>
        {canDrive && isChat && !ended && !disabledReason && (
          <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · esc interrupt · / commands · paste or drop files</span>
        )}
        {canDrive && isAssistant && <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · esc interrupt</span>}
        {canSteer && <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · delivered at the agent’s next step</span>}
      </footer>
    </section>
  );
}
