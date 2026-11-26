import { memo, useRef, useState } from 'react';
import { cost, duration, modelLabel } from '../../lib/format';
import { useNow } from '../../lib/useNow';
import { DeployLogBlock } from '../deploy/DeployLog';
import { SandboxLogBlock } from '../sandbox/SandboxLog';
import { MessageAttachments } from './attachments';
import { GeneratedImages } from './generated';
import { Markdown } from './Markdown';
import { toolArgs, toolDisplayName, toolSummary, type Item, type ToolProgress } from './model';

const PREVIEW_LINES = 3;

/**
 * "running… 2m 10s" beside a running tool call. Heartbeats come every few seconds; in between the time counts on
 * from the latest one (or from the first heartbeat seen when they carry no elapsed time).
 */
function RunningFor({ progress }: { progress: ToolProgress }) {
  const now = useNow(1000);
  const base = useRef<{ elapsed: number | null; at: number } | null>(null);
  if (!base.current || (progress.elapsedSeconds !== null && progress.elapsedSeconds !== base.current.elapsed)) {
    base.current = { elapsed: progress.elapsedSeconds, at: Date.now() };
  }
  const ms = (base.current.elapsed ?? 0) * 1000 + Math.max(0, now - base.current.at);
  return <span className="t-tool-progress"> running… {duration(ms)}</span>;
}

function ToolCall({ item }: { item: Extract<Item, { k: 'tool' }> }) {
  const [open, setOpen] = useState(false);
  const r = item.result;
  const state = !r ? 'pending' : r.isError ? 'error' : 'ok';
  const args = toolArgs(item.name, item.input, item.inputPartial);
  const lines = r?.text ? r.text.replace(/\n+$/, '').split('\n') : [];
  const summary = r ? toolSummary(item.name, r) : null;
  const hidden = Math.max(0, lines.length - PREVIEW_LINES);
  const expandable = !!r && (summary ? lines.length > 0 : hidden > 0);
  const todos = item.name === 'TodoWrite' && item.input && typeof item.input === 'object' ? (item.input as { todos?: unknown }).todos : null;

  return (
    <div className={`t-tool t-tool--${state}`}>
      <div className="t-line">
        <span className="t-dot" aria-hidden>⏺</span>
        <span className="t-body">
          <span className="t-tool-name">{toolDisplayName(item.name)}</span>
          <span className="t-tool-args">({args})</span>
          {!r && item.progress && <RunningFor progress={item.progress} />}
          <span className="sr-only">{state === 'pending' ? ' running' : state === 'error' ? ' failed' : ' done'}</span>
        </span>
      </div>
      {Array.isArray(todos) && (
        <ul className="t-todos">
          {todos.slice(0, 12).map((t, i) => {
            const todo = t as { content?: string; status?: string };
            return (
              <li key={i} className={`todo todo--${todo.status ?? 'pending'}`}>
                <span aria-hidden>{todo.status === 'completed' ? '☒' : '☐'}</span> {todo.content ?? '…'}
              </li>
            );
          })}
        </ul>
      )}
      {r && (
        <div className="t-result">
          <span className="t-elbow" aria-hidden>⎿</span>
          <div className="t-result-body">
            {open ? (
              <pre className="t-out t-out--full">{r.text || '(no output)'}</pre>
            ) : summary ? (
              <span className="t-summary">{summary}</span>
            ) : (
              <pre className="t-out">{lines.length ? lines.slice(0, PREVIEW_LINES).join('\n') : '(no output)'}</pre>
            )}
            {expandable && (
              <button type="button" className="t-expand" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
                {open ? 'collapse' : summary ? 'show output' : `… +${hidden} line${hidden === 1 ? '' : 's'}`}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Thinking({ item }: { item: Extract<Item, { k: 'thinking' }> }) {
  const [open, setOpen] = useState(false);
  const long = item.text.length > 220 || item.text.split('\n').length > 3;
  return (
    <div className="t-thinking">
      <button type="button" className="t-thinking-head" onClick={() => setOpen((o) => !o)} aria-expanded={open} disabled={!long}>
        <span aria-hidden>✻</span> Thinking{item.streaming ? '…' : ''}
      </button>
      <div className={`t-thinking-body ${open || !long ? 'is-open' : ''}`}>{item.text}</div>
    </div>
  );
}

const TranscriptItem = memo(function TranscriptItem({ item, me, sessionId }: { item: Item; me?: string; sessionId: string }) {
  switch (item.k) {
    case 'user': {
      const cmd = item.text.trim().startsWith('/');
      return (
        <div className={`t-user ${cmd ? 't-user--cmd' : ''}`}>
          <span className="t-prompt" aria-hidden>&gt;</span>
          <div className="t-user-body">
            {item.text && <div className="t-user-text">{item.text}</div>}
            <MessageAttachments sessionId={sessionId} list={item.attachments} />
          </div>
          {item.forwarded ? (
            <span className={`t-user-fwd t-user-fwd--${item.forwarded.delivery}`}>
              {item.username} · {item.forwarded.origin === 'chat' ? <>via chat <code>{item.forwarded.viaSessionId?.slice(0, 8) ?? '?'}</code></> : 'direct'} ·{' '}
              {item.forwarded.delivery === 'queued' ? 'queued — delivered at the agent’s next step' : item.forwarded.delivery === 'delivered' ? 'delivered' : 'not delivered (the agent stopped first)'}
            </span>
          ) : (
            item.username && item.username !== me && <span className="t-user-name">{item.username}</span>
          )}
        </div>
      );
    }
    case 'text':
      return (
        <div className="t-text">
          <span className="t-dot t-dot--text" aria-hidden>⏺</span>
          <Markdown text={item.text} streaming={item.streaming} />
        </div>
      );
    case 'thinking':
      return <Thinking item={item} />;
    case 'tool':
      return <ToolCall item={item} />;
    case 'init':
      return (
        <div className="t-meta">
          <span aria-hidden>◆</span> {modelLabel(item.model)}
          {item.cwd && <> · <span className="t-cwd">{item.cwd}</span></>}
          {item.tools > 0 && <> · {item.tools} tools</>}
          {item.mcp.map((m) => (
            <span key={m.name}> · mcp {m.name} {m.status === 'connected' ? '✓' : m.status}</span>
          ))}
        </div>
      );
    case 'result':
      return (
        <div className={`t-result-line ${item.ok ? '' : 't-result-line--err'}`}>
          <span aria-hidden>{item.ok ? '✓' : '✗'}</span>{' '}
          {item.ok ? 'Done' : item.subtype.replace(/_/g, ' ')}
          {item.durationMs != null && item.durationMs > 0 && <> in {duration(item.durationMs)}</>}
          {item.costUsd != null && <> · {cost(item.costUsd)}</>}
          {item.turns != null && <> · {item.turns} turns</>}
        </div>
      );
    case 'system':
      if (item.sandbox) return <SandboxLogBlock log={item.sandbox} />;
      if (item.deploy) return <DeployLogBlock log={item.deploy} />;
      return (
        <div className={`t-system t-system--${item.level}`} role={item.level === 'error' ? 'alert' : undefined}>
          <span className="t-elbow" aria-hidden>※</span>
          <pre>{item.text}</pre>
        </div>
      );
    case 'status':
      return <div className="t-meta t-meta--status">— session {item.status} —</div>;
    case 'images':
      return <GeneratedImages sessionId={sessionId} payload={item.images} me={me} />;
    default:
      return (
        <div className="t-unknown" title={item.json}>
          <span className="t-unknown-label">{item.label}</span> {item.json}
        </div>
      );
  }
});

export function TranscriptItems({ items, me, sessionId }: { items: Item[]; me?: string; sessionId: string }) {
  return (
    <>
      {items.map((it) => (
        <TranscriptItem key={it.id} item={it} me={me} sessionId={sessionId} />
      ))}
    </>
  );
}
