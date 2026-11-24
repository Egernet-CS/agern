import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type DragEvent as ReactDragEvent, type ReactNode } from 'react';
import { api, ApiError, STEERABLE_ROLES, type AgentSession, type Attachment, type CheckoutInfo, type Run, type StageRole } from '../../api';
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

/** A live thinking-token estimate, rounded like "~5,800 tokens". */
function thinkingLabel(tokens: number): string {
  const rounded = tokens < 100 ? Math.max(0, Math.round(tokens)) : Math.round(tokens / 100) * 100;
  return `~${rounded.toLocaleString('en-US')} tokens`;
}

function Spinner({ since, label, canInterrupt, activity, thinkingTokens }: { since: string | null; label?: string; canInterrupt: boolean; activity: string | null; thinkingTokens?: number | null }) {
  const now = useNow(120);
  const start = since ? Date.parse(since) : now;
  const glyph = GLYPHS[Math.floor(now / 120) % GLYPHS.length];
  const thinking = thinkingTokens != null;
  const verb = thinking ? 'Thinking' : label ?? VERBS[Math.abs(Math.floor(start / 1000)) % VERBS.length];
  return (
    <div className="t-spinner" role="status" aria-live="off">
      <span className="t-spinner-glyph" aria-hidden>{glyph}</span>
      <span className="t-spinner-verb">{verb}…</span>
      <span className="t-spinner-meta">
        ({duration(now - start)}
        {thinking ? ` · ${thinkingLabel(thinkingTokens)}` : activity ? ` · ${activity}` : ''}
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
  /** A node, or a function of what the transcript knows (the latest `checkout` event) for the chat header. */
  header?: ReactNode | ((ctx: { checkout: CheckoutInfo | null }) => ReactNode);
  /**
   * The run whose advisor this is (the conversation while a run needs a human). Its owner talks to the agent through a
   * text-only composer while the run is `needs_human` and no follow-up review runs.
   */
  advisorRun?: Run;
};

export function ChatView({ sessionId, session, disabledReason, embedded, header, advisorRun }: ChatViewProps) {
  const me = useMe();
  const stream = useSessionStream(sessionId);
  const { items, summary } = useMemo(() => buildTranscript(stream.events, stream.partials), [stream.events, stream.partials]);
  const isOwner = session ? session.owner.username === me.username : advisorRun ? advisorRun.owner.username === me.username : false;
  const isChat = session?.kind === 'chat';
  const canDrive = isOwner && isChat && !embedded;
  // The server's session status says whether the agent is mid-turn (`starting`/`running`) or waiting (`idle`).
  // It arrives as `status` events on this session's stream and as feed updates of the session.
  const status = summary.status ?? session?.status ?? null;
  // A stopped chat is not over: the owner's next message resumes the conversation (--resume).
  // An advisor is not over when its process stops either: the run's status decides whether the conversation is open.
  const isAdvisor = session?.kind === 'advisor' || !!advisorRun;
  const ended = !isChat && !isAdvisor && (status === 'stopped' || status === 'failed' || status === 'done');
  const resumable = isChat && status === 'stopped';
  const working = status === 'running' || status === 'starting';
  const [sending, setSending] = useState<{ text: string; attachments: Attachment[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const canCompose = canDrive && !ended && !disabledReason;
  // Messages to a working pipeline agent: its owner only, spec-writer / builder only, only while it runs.
  const stageRole = !isChat && session ? (session.kind as StageRole) : null;
  const canSteer = isOwner && !!stageRole && STEERABLE_ROLES.includes(stageRole) && status === 'running' && session?.live !== false;
  // The conversation of a run that needs a human: its owner only, text only, one message at a time.
  const advisorOpen = !!advisorRun && advisorRun.status === 'needs_human';
  const reviewing = advisorOpen && advisorRun.conversation.review === 'running';
  const canTalk = isOwner && isAdvisor && advisorOpen && !reviewing;
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

  const talk = useCallback(
    async (text: string) => {
      if (!advisorRun) return false;
      setError(null);
      setSending({ text, attachments: [] });
      stick.current = true;
      try {
        await api.talkToRun(advisorRun.id, text);
      } catch (e) {
        setSending(null);
        setError(e instanceof ApiError ? e.message : 'Could not send.');
        return false;
      }
      return true;
    },
    [advisorRun],
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
    if (!(canDrive || canTalk) || !working) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented && !document.querySelector('dialog[open]')) {
        e.preventDefault();
        void interrupt();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [canDrive, canTalk, working, interrupt]);

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
      {typeof header === 'function' ? header({ checkout: summary.checkout }) : header}
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
            <div className="t-meta">{isChat ? 'Empty session. Say hello — or type /factory help.' : isAdvisor ? 'No messages yet.' : 'Waiting for the agent to start…'}</div>
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
              canInterrupt={canDrive || canTalk}
              activity={isChat ? null : session?.lastActivity ?? null}
              thinkingTokens={summary.thinkingTokens}
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
        <div className="chat-resume muted small" role="note">This chat’s agent was stopped. Your next message resumes the conversation.</div>
      )}
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
      {canTalk && (
        <Prompt
          onSubmit={talk}
          onInterrupt={interrupt}
          working={working}
          commands={false}
          label="Message the agent"
          placeholder="Ask about this run, or ask for a change"
          history={history}
        />
      )}
      {isOwner && reviewing && (
        <div className="chat-disabled" role="status">The changes are being reviewed; you can continue when the reviews are in.</div>
      )}
      {isOwner && advisorRun && !advisorOpen && (
        <div className="chat-disabled">This run is {advisorRun.status.replace('_', ' ')}; the conversation is closed.</div>
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
        {canDrive && !ended && !disabledReason && (
          <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · esc interrupt · / commands · paste or drop files</span>
        )}
        {canSteer && <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · delivered at the agent’s next step</span>}
        {canTalk && <span className="cf-hints" aria-hidden>⏎ send · ⇧⏎ newline · esc interrupt</span>}
      </footer>
    </section>
  );
}
