// Turns raw session events (incl. Claude Code stream-json) into renderable transcript items.
// Everything here is defensive: unknown shapes become compact "unknown" items, never throws.
import type { Attachment, FactoryEvent, SandboxLog, SessionStatus } from '../../api/types';

export type ToolResult = { text: string; isError: boolean; images: number };

/** A message the run's owner sent to a running pipeline agent (directly, or forwarded by their chat agent). */
export type Forwarded = {
  messageId: string;
  origin: 'direct' | 'chat';
  viaSessionId: string | null;
  /** `queued` until the CLI picks it up (a `system` event with the same messageId), `undelivered` if the agent stopped first. */
  delivery: 'queued' | 'delivered' | 'undelivered';
};

export type Item =
  | { k: 'user'; id: string; text: string; username: string; ts: string; attachments: Attachment[]; forwarded?: Forwarded }
  | { k: 'text'; id: string; text: string; streaming?: boolean }
  | { k: 'thinking'; id: string; text: string; streaming?: boolean }
  | { k: 'tool'; id: string; name: string; input: unknown; inputPartial?: string; result?: ToolResult; streaming?: boolean }
  | { k: 'init'; id: string; model: string; cwd: string | null; tools: number; mcp: { name: string; status: string }[] }
  | { k: 'result'; id: string; ok: boolean; subtype: string; costUsd: number | null; turns: number | null; durationMs: number | null; text: string | null }
  | { k: 'system'; id: string; level: 'info' | 'warn' | 'error'; text: string; sandbox?: SandboxLog }
  | { k: 'status'; id: string; status: string }
  | { k: 'unknown'; id: string; label: string; json: string };

export type TranscriptSummary = {
  model: string | null;
  costUsd: number | null;
  turns: number | null;
  status: SessionStatus | null;
  /** True while a chat turn is in flight (last user input not yet answered by a result). */
  turnOpen: boolean;
  turnStartedAt: string | null;
  lastActivity: string | null;
};

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

function compactJson(v: unknown, max = 220): string {
  let s: string;
  try {
    s = JSON.stringify(v);
  } catch {
    s = String(v);
  }
  return s.length > max ? `${s.slice(0, max)}…` : s;
}

/** Attachment refs of a `user` event, defensively (only well-formed entries). */
export function attachmentsOf(v: unknown): Attachment[] {
  if (!Array.isArray(v)) return [];
  return v.filter((a): a is Attachment => isObj(a) && typeof a.id === 'string' && typeof a.name === 'string').map((a) => ({
    ...a,
    kind: a.kind === 'image' ? 'image' : 'file',
    size: num(a.size) ?? 0,
  }));
}

/** The `sandbox` log of a system event, defensively (undefined unless well-formed). */
export function sandboxLogOf(v: unknown): SandboxLog | undefined {
  if (!isObj(v) || typeof v.command !== 'string') return undefined;
  return {
    kind: v.kind === 'setup' ? 'setup' : 'exec',
    command: v.command,
    exitCode: num(v.exitCode) ?? -1,
    timedOut: v.timedOut === true,
    durationMs: num(v.durationMs) ?? 0,
    output: str(v.output) ?? '',
    outputTruncated: v.outputTruncated === true,
    platform: str(v.platform) ?? null,
  };
}

export function normalizeToolContent(c: unknown): { text: string; images: number } {
  if (typeof c === 'string') return { text: c, images: 0 };
  if (Array.isArray(c)) {
    let images = 0;
    const parts: string[] = [];
    for (const p of c) {
      if (isObj(p) && p.type === 'text' && typeof p.text === 'string') parts.push(p.text);
      else if (isObj(p) && p.type === 'image') images++;
      else if (typeof p === 'string') parts.push(p);
      else parts.push(compactJson(p));
    }
    return { text: parts.join('\n'), images };
  }
  if (c == null) return { text: '', images: 0 };
  return { text: compactJson(c, 4000), images: 0 };
}

export function buildTranscript(events: FactoryEvent[], partials: unknown[]): { items: Item[]; summary: TranscriptSummary } {
  const items: Item[] = [];
  const tools = new Map<string, Extract<Item, { k: 'tool' }>>();
  const blocksPerMessage = new Map<string, number>();
  const summary: TranscriptSummary = { model: null, costUsd: null, turns: null, status: null, turnOpen: false, turnStartedAt: null, lastActivity: null };
  const forwarded = new Map<string, Forwarded>();

  for (const ev of events) {
    const id = `e${ev.seq}`;
    const p = ev.payload;
    try {
      if (ev.type === 'user') {
        const u = isObj(p) ? p : {};
        const messageId = str(u.messageId);
        const fwd: Forwarded | undefined = messageId
          ? { messageId, origin: u.origin === 'chat' ? 'chat' : 'direct', viaSessionId: str(u.viaSessionId), delivery: 'queued' }
          : undefined;
        if (fwd) forwarded.set(fwd.messageId, fwd);
        items.push({ k: 'user', id, text: str(u.text) ?? '', username: str(u.username) ?? '', ts: ev.ts, attachments: attachmentsOf(u.attachments), forwarded: fwd });
        const isCommand = (str(u.text) ?? '').trim().startsWith('/factory');
        // A message to a working pipeline agent joins the running turn; it does not start one.
        if (!isCommand && !fwd) {
          summary.turnOpen = true;
          summary.turnStartedAt = ev.ts;
        }
        continue;
      }
      if (ev.type === 'system') {
        const s = isObj(p) ? p : {};
        // Delivery confirmation of a forwarded message: shown on the message itself.
        const fwd = forwarded.get(str(s.messageId) ?? '');
        if (fwd) {
          fwd.delivery = s.level === 'warn' || s.level === 'error' ? 'undelivered' : 'delivered';
          continue;
        }
        const level = s.level === 'warn' || s.level === 'error' ? s.level : 'info';
        items.push({ k: 'system', id, level, text: str(s.text) ?? compactJson(p), sandbox: sandboxLogOf(s.sandbox) });
        continue;
      }
      if (ev.type === 'status') {
        const st = isObj(p) ? str(p.status) : null;
        if (st) summary.status = st as SessionStatus;
        if (st === 'stopped' || st === 'failed') {
          items.push({ k: 'status', id, status: st });
          summary.turnOpen = false;
        }
        continue;
      }
      if (ev.type !== 'claude' || !isObj(p)) {
        items.push({ k: 'unknown', id, label: String(ev.type), json: compactJson(p) });
        continue;
      }
      const t = str(p.type);
      if (t === 'system' && p.subtype === 'init') {
        const model = str(p.model) ?? '';
        summary.model = model || summary.model;
        const mcp = Array.isArray(p.mcp_servers)
          ? p.mcp_servers.filter(isObj).map((m) => ({ name: str(m.name) ?? '?', status: str(m.status) ?? '?' }))
          : [];
        items.push({ k: 'init', id, model, cwd: str(p.cwd), tools: Array.isArray(p.tools) ? p.tools.length : 0, mcp });
      } else if (t === 'assistant' && isObj(p.message)) {
        const msg = p.message;
        const msgId = str(msg.id);
        if (str(msg.model)) summary.model = summary.model ?? str(msg.model);
        const content = Array.isArray(msg.content) ? msg.content : [];
        if (msgId) blocksPerMessage.set(msgId, (blocksPerMessage.get(msgId) ?? 0) + content.length);
        content.forEach((b, i) => {
          const bid = `${id}.${i}`;
          if (!isObj(b)) return;
          if (b.type === 'text') {
            const text = str(b.text) ?? '';
            if (text.trim()) items.push({ k: 'text', id: bid, text });
          } else if (b.type === 'thinking' || b.type === 'redacted_thinking') {
            items.push({ k: 'thinking', id: bid, text: str(b.thinking) ?? '(redacted)' });
          } else if (b.type === 'tool_use') {
            const tool: Extract<Item, { k: 'tool' }> = { k: 'tool', id: str(b.id) ?? bid, name: str(b.name) ?? 'Tool', input: b.input };
            tools.set(tool.id, tool);
            items.push(tool);
            summary.lastActivity = tool.name;
          } else {
            items.push({ k: 'unknown', id: bid, label: `assistant.${String(b.type)}`, json: compactJson(b) });
          }
        });
      } else if (t === 'user' && isObj(p.message)) {
        const content = Array.isArray(p.message.content) ? p.message.content : typeof p.message.content === 'string' ? [{ type: 'text', text: p.message.content }] : [];
        content.forEach((b, i) => {
          if (!isObj(b)) return;
          if (b.type === 'tool_result') {
            const { text, images } = normalizeToolContent(b.content);
            const res: ToolResult = { text, isError: b.is_error === true, images };
            const tool = tools.get(str(b.tool_use_id) ?? '');
            if (tool) tool.result = res;
            else items.push({ k: 'unknown', id: `${id}.${i}`, label: 'tool_result', json: text.slice(0, 200) });
          }
          // Plain user text inside claude events is the prompt echo; the factory 'user' event already shows it.
        });
      } else if (t === 'result') {
        const ok = p.is_error !== true && (p.subtype === 'success' || p.subtype === undefined);
        summary.costUsd = num(p.total_cost_usd) ?? summary.costUsd;
        summary.turns = num(p.num_turns) ?? summary.turns;
        summary.turnOpen = false;
        items.push({ k: 'result', id, ok, subtype: str(p.subtype) ?? 'result', costUsd: num(p.total_cost_usd), turns: num(p.num_turns), durationMs: num(p.duration_ms), text: str(p.result) });
      } else if (t === 'stream_event') {
        // Persisted partials should not happen; ignore quietly.
      } else if (t === 'system') {
        const sub = str(p.subtype) ?? 'system';
        if (sub !== 'hook_response' && sub !== 'status') items.push({ k: 'unknown', id, label: `system.${sub}`, json: compactJson(p, 160) });
      } else {
        items.push({ k: 'unknown', id, label: t ?? 'claude', json: compactJson(p) });
      }
    } catch {
      items.push({ k: 'unknown', id, label: 'unreadable event', json: compactJson(p) });
    }
  }

  // Partial (live-only) blocks for the message being generated.
  let msgId: string | null = null;
  const blocks = new Map<number, { type: string; text: string; name?: string }>();
  for (const raw of partials) {
    const e = isObj(raw) && isObj(raw.event) ? raw.event : null;
    if (!e) continue;
    const idx = num(e.index) ?? 0;
    if (e.type === 'message_start') {
      msgId = isObj(e.message) ? str(e.message.id) : null;
      blocks.clear();
    } else if (e.type === 'content_block_start' && isObj(e.content_block)) {
      const cb = e.content_block;
      blocks.set(idx, { type: str(cb.type) ?? 'text', text: str(cb.text) ?? str(cb.thinking) ?? '', name: str(cb.name) ?? undefined });
    } else if (e.type === 'content_block_delta' && isObj(e.delta)) {
      const d = e.delta;
      const b = blocks.get(idx) ?? { type: d.type === 'thinking_delta' ? 'thinking' : d.type === 'input_json_delta' ? 'tool_use' : 'text', text: '' };
      b.text += str(d.text) ?? str(d.thinking) ?? str(d.partial_json) ?? '';
      blocks.set(idx, b);
    }
  }
  const covered = msgId ? blocksPerMessage.get(msgId) ?? 0 : 0;
  for (const [idx, b] of [...blocks.entries()].sort((a, c) => a[0] - c[0])) {
    if (idx < covered) continue;
    const pid = `partial-${msgId ?? 'x'}-${idx}`;
    if (b.type === 'thinking') items.push({ k: 'thinking', id: pid, text: b.text, streaming: true });
    else if (b.type === 'tool_use') items.push({ k: 'tool', id: pid, name: b.name ?? 'Tool', input: undefined, inputPartial: b.text, streaming: true });
    else if (b.text) items.push({ k: 'text', id: pid, text: b.text, streaming: true });
  }

  return { items, summary };
}

// ---------------------------------------------------------------- tool presentation

const basename = (p: string) => p.split('/').filter(Boolean).slice(-2).join('/');

export function toolDisplayName(name: string): string {
  const m = /^mcp__([^_]+(?:_[^_]+)*)__(.+)$/.exec(name);
  if (m) return `${m[2]} (MCP)`;
  return name;
}

export function toolArgs(name: string, input: unknown, partial?: string): string {
  if (!isObj(input)) {
    if (partial) {
      const m = /"(?:command|file_path|pattern|url|description)"\s*:\s*"((?:[^"\\]|\\.)*)/.exec(partial);
      return m ? m[1] : '…';
    }
    return '';
  }
  const s = (k: string) => str(input[k]);
  let out: string | null = null;
  switch (name) {
    case 'Bash':
      out = s('command');
      break;
    case 'Read':
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit':
      out = s('file_path') ?? s('notebook_path');
      if (out) out = basename(out);
      break;
    case 'Grep':
      out = [s('pattern') && `pattern: "${s('pattern')}"`, s('path') && `path: "${s('path')}"`].filter(Boolean).join(', ');
      break;
    case 'Glob':
      out = s('pattern') && `pattern: "${s('pattern')}"`;
      break;
    case 'WebFetch':
      out = s('url');
      break;
    case 'WebSearch':
      out = s('query') && `"${s('query')}"`;
      break;
    case 'Task':
    case 'Agent':
      out = s('description');
      break;
    case 'TodoWrite':
      out = Array.isArray(input.todos) ? `${input.todos.length} todos` : null;
      break;
  }
  if (name.endsWith('__factory_sandbox_exec')) out = s('command');
  if (out == null) {
    const entries = Object.entries(input).filter(([, v]) => v !== undefined);
    out = entries
      .slice(0, 3)
      .map(([k, v]) => `${k}: ${typeof v === 'string' ? JSON.stringify(v.length > 40 ? `${v.slice(0, 40)}…` : v) : compactJson(v, 40)}`)
      .join(', ');
  }
  out = out.replace(/\s*\n\s*/g, ' ⏎ ');
  return out.length > 120 ? `${out.slice(0, 120)}…` : out;
}

/** One-line summary in the style of Claude Code, or null to show the first lines of output. */
export function toolSummary(name: string, r: ToolResult): string | null {
  if (r.isError) return null;
  const lines = r.text ? r.text.split('\n').length : 0;
  if (name === 'Read') return `Read ${lines} line${lines === 1 ? '' : 's'}`;
  if (name === 'Glob') return `Found ${lines} file${lines === 1 ? '' : 's'}`;
  if (name === 'Grep') return r.text.trim() ? `Found ${lines} match${lines === 1 ? '' : 'es'}` : 'No matches';
  if (name === 'TodoWrite') return 'Updated todos';
  if (name.endsWith('__factory_sandbox_exec')) {
    // The agent gets the tail as JSON; the full log is the sandbox block in the transcript.
    try {
      const j = JSON.parse(r.text) as { exitCode?: number; durationMs?: number; timedOut?: boolean };
      if (typeof j.exitCode === 'number') {
        const secs = typeof j.durationMs === 'number' ? ` in ${(j.durationMs / 1000).toFixed(1)} s` : '';
        return `${j.timedOut ? 'Timed out' : `Exit ${j.exitCode}`}${secs} · full log below`;
      }
    } catch {
      /* not JSON: show the text */
    }
  }
  if (r.images) return `${r.images} image${r.images > 1 ? 's' : ''}${r.text ? ` + ${lines} lines` : ''}`;
  return null;
}
