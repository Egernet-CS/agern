// Turns raw session events (incl. Claude Code stream-json) into renderable transcript items.
// Everything here is defensive: unknown shapes become compact "unknown" items, never throws.
import type { Attachment, CheckoutInfo, DeployLog, FactoryEvent, ImagesPayload, SandboxLog, SandboxVmLog, SessionStatus } from '../../api/types';

export type ToolResult = { text: string; isError: boolean; images: number };

/** Live heartbeat of a tool call that is still running: seconds it has run so far (null when the CLI did not say). */
export type ToolProgress = { elapsedSeconds: number | null };

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
  | { k: 'tool'; id: string; name: string; input: unknown; inputPartial?: string; result?: ToolResult; streaming?: boolean; progress?: ToolProgress }
  | { k: 'init'; id: string; model: string; cwd: string | null; tools: number; mcp: { name: string; status: string }[] }
  | { k: 'result'; id: string; ok: boolean; subtype: string; costUsd: number | null; turns: number | null; durationMs: number | null; text: string | null }
  | { k: 'system'; id: string; level: 'info' | 'warn' | 'error'; text: string; sandbox?: SandboxLog; deploy?: DeployLog; sandboxVm?: SandboxVmLog }
  | { k: 'status'; id: string; status: string }
  | { k: 'images'; id: string; images: ImagesPayload }
  | { k: 'compact'; id: string; trigger: 'auto' | 'manual' | null; preTokens: number | null; postTokens: number | null }
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
  /** The latest `checkout` event: the state of the chat's checkout (null before the first one). */
  checkout: CheckoutInfo | null;
  /** Live estimate of the tokens the model has thought so far in the current message (null when not thinking). */
  thinkingTokens: number | null;
  /**
   * How full the agent's context is: `used` from the latest main-thread assistant usage (or a compaction's
   * `post_tokens`), `window` from the `contextWindow` of a result's `modelUsage`. Null while unknown.
   */
  context: ContextUsage;
  /** True while the CLI reports that it is compacting the conversation. */
  compacting: boolean;
};

export type ContextUsage = { used: number | null; window: number | null };

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/**
 * Subtypes of Claude Code `system` events that only report progress (`thinking_tokens`: about every 100 thinking
 * tokens). The server sends them live only; ones stored by older versions are skipped in the transcript.
 */
export const TRANSIENT_SYSTEM_SUBTYPES: ReadonlySet<string> = new Set(['thinking_tokens']);

/**
 * True for a `tool_progress` heartbeat: the CLI sends one every few seconds while a tool call runs. The server sends
 * them live only; ones stored by older versions are skipped in the transcript.
 */
export function isToolProgress(p: unknown): p is { type: 'tool_progress'; tool_use_id?: unknown; parent_tool_use_id?: unknown; elapsed_time_seconds?: unknown } {
  return isObj(p) && p.type === 'tool_progress';
}

/** True for a live `system/thinking_tokens` progress payload. */
export function isThinkingTokens(p: unknown): p is { type: 'system'; subtype: 'thinking_tokens'; estimated_tokens?: unknown } {
  return isObj(p) && p.type === 'system' && p.subtype === 'thinking_tokens';
}

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
/**
 * The displayed width (CSS px) of a transcript thumbnail of `width`×`height`, fitted into `maxW`×`maxH` without upscaling,
 * so its box can be reserved before the image loads. Null when the size is unknown.
 */
export function fitWidth(width: number | null | undefined, height: number | null | undefined, maxW = 220, maxH = 160): number | null {
  if (width == null || height == null || !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;
  return Math.max(1, Math.round(width * Math.min(1, maxW / width, maxH / height)));
}

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
    ...(v.review === true ? { review: true } : {}),
  };
}

/** The `deploy` log of a system event (a chat's deploy command or copy), defensively (undefined unless well-formed). */
export function deployLogOf(v: unknown): DeployLog | undefined {
  if (!isObj(v) || typeof v.target !== 'string' || !v.target) return undefined;
  const kind = v.kind === 'copy' ? 'copy' : v.kind === 'exec' ? 'exec' : null;
  if (!kind) return undefined;
  const command = str(v.command) ?? undefined;
  const path = str(v.path) ?? undefined;
  const dest = str(v.dest) ?? undefined;
  if (kind === 'exec' && command === undefined) return undefined;
  if (kind === 'copy' && (path === undefined || dest === undefined)) return undefined;
  const exit = num(v.exitCode);
  return {
    kind,
    target: v.target,
    host: str(v.host) ?? '',
    port: num(v.port) ?? 22,
    user: str(v.user) ?? '',
    command,
    path,
    dest,
    bytes: num(v.bytes) ?? undefined,
    exitCode: exit != null && Number.isInteger(exit) ? exit : null,
    timedOut: v.timedOut === true,
    durationMs: num(v.durationMs) ?? 0,
    output: str(v.output) ?? '',
    outputTruncated: v.outputTruncated === true,
    error: str(v.error) || null,
  };
}

const VM_STEPS = ['upload', 'checkout', 'setup', 'ready'] as const;

/**
 * The `sandboxVm` log of a system event (a deploy step, a command or a reset on a run's sandbox VM), defensively
 * (undefined unless well-formed: a known kind, a VM name, and a command for `exec` and `reset`).
 */
export function sandboxVmLogOf(v: unknown): SandboxVmLog | undefined {
  if (!isObj(v) || typeof v.vm !== 'string' || !v.vm) return undefined;
  const kind = v.kind === 'deploy' || v.kind === 'exec' || v.kind === 'reset' ? v.kind : null;
  if (!kind) return undefined;
  const command = str(v.command);
  if (kind !== 'deploy' && command === null) return undefined;
  const step = kind === 'deploy' ? (VM_STEPS.find((x) => x === v.step) ?? null) : null;
  const exit = num(v.exitCode);
  return {
    kind,
    step,
    vm: v.vm,
    host: str(v.host) ?? '',
    port: num(v.port) ?? 22,
    user: str(v.user) ?? '',
    command,
    sha: str(v.sha) || null,
    exitCode: exit != null && Number.isInteger(exit) ? exit : null,
    timedOut: v.timedOut === true,
    durationMs: num(v.durationMs) ?? 0,
    output: str(v.output) ?? '',
    outputTruncated: v.outputTruncated === true,
    error: str(v.error) || null,
  };
}

/** The payload of an `images` event, defensively (undefined unless well-formed). */
export function imagesOf(v: unknown): ImagesPayload | undefined {
  if (!isObj(v) || !Array.isArray(v.images)) return undefined;
  const images = v.images.flatMap((i) =>
    isObj(i) && typeof i.id === 'string'
      ? [{ id: i.id, mediaType: str(i.mediaType) ?? 'image/png', width: num(i.width) ?? 0, height: num(i.height) ?? 0, size: num(i.size) ?? 0 }]
      : [],
  );
  if (images.length === 0) return undefined;
  const issue = num(v.issueNumber);
  return {
    requestId: str(v.requestId) ?? '',
    provider: str(v.provider) ?? '',
    providerLabel: str(v.providerLabel) ?? str(v.provider) ?? 'image AI',
    model: str(v.model) ?? '',
    prompt: str(v.prompt) ?? '',
    purpose: str(v.purpose) ?? '',
    ...(issue != null && issue > 0 ? { issueNumber: issue } : {}),
    images,
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

const CHECKOUT_STATUSES = ['current', 'updated', 'behind'] as const;
const CHECKOUT_REASONS = ['other_branch', 'local_changes', 'local_commits', 'update_failed'] as const;

/** The payload of a `checkout` event, defensively (undefined unless well-formed). */
export function checkoutOf(v: unknown): CheckoutInfo | undefined {
  if (!isObj(v)) return undefined;
  const defaultBranch = str(v.defaultBranch);
  const commit = str(v.commit);
  const behind = num(v.behind);
  const ahead = num(v.ahead);
  const status = CHECKOUT_STATUSES.find((s) => s === v.status);
  if (!defaultBranch || !commit || !/^[0-9a-f]{7,64}$/.test(commit) || behind == null || ahead == null || !status) return undefined;
  if (v.branch != null && typeof v.branch !== 'string') return undefined;
  return {
    branch: str(v.branch),
    defaultBranch,
    commit,
    behind: Math.max(0, Math.floor(behind)),
    ahead: Math.max(0, Math.floor(ahead)),
    status,
    reason: CHECKOUT_REASONS.find((r) => r === v.reason) ?? null,
    updatedFrom: str(v.updatedFrom),
    switchedFrom: str(v.switchedFrom),
  };
}

// ---------------------------------------------------------------- context and compaction

/** Token counts the way the meter shows them: `950`, `48k`, `312k`, `1M`, `1.5M`. */
export function formatTokens(n: number): string {
  const v = Number.isFinite(n) ? Math.max(0, n) : 0;
  if (v < 1000) return String(Math.round(v));
  if (v < 999_500) return `${Math.round(v / 1000)}k`;
  return `${String(Math.round(v / 100_000) / 10)}M`;
}

/** The divider text of a `compact` item. */
export function compactLabel(c: { trigger: 'auto' | 'manual' | null; preTokens: number | null; postTokens: number | null }): string {
  let s = 'Conversation compacted here';
  if (c.trigger) s += ` (${c.trigger})`;
  if (c.preTokens != null && c.postTokens != null) s += ` — ${formatTokens(c.preTokens)} → ${formatTokens(c.postTokens)} tokens`;
  else if (c.preTokens != null && c.trigger) s += ` — was ${formatTokens(c.preTokens)} tokens`;
  return s;
}

/** The meter of [context]: null while the used amount is unknown; without a window only the used amount. */
export function contextMeter(context: ContextUsage): { label: string; percent: number | null; warn: boolean } | null {
  const { used, window } = context;
  if (used == null) return null;
  if (window == null || window <= 0) return { label: formatTokens(used), percent: null, warn: false };
  return { label: `${formatTokens(used)} / ${formatTokens(window)}`, percent: Math.min(100, Math.round((used / window) * 100)), warn: used / window >= 0.8 };
}

/** A chat message that is the CLI's `/compact [what to keep]` command (case-sensitive, after trimming). */
export function isCompactCommand(text: string): boolean {
  return /^\/compact(\s|$)/.test(text.trim());
}

const tokenCount = (v: unknown): number | null => {
  const n = num(v);
  return n != null && n >= 0 ? n : null;
};

/** The `compact_metadata` of a `compact_boundary` event, defensively (unknown parts null). */
export function compactOf(v: unknown): { trigger: 'auto' | 'manual' | null; preTokens: number | null; postTokens: number | null } {
  const m = isObj(v) ? v : {};
  return {
    trigger: m.trigger === 'auto' || m.trigger === 'manual' ? m.trigger : null,
    preTokens: tokenCount(m.pre_tokens),
    postTokens: tokenCount(m.post_tokens),
  };
}

/** The context a main-thread assistant message reports (input + cache read + cache creation), or null. */
function contextOfAssistant(p: Obj, msg: Obj): number | null {
  if (p.parent_tool_use_id != null || msg.model === '<synthetic>' || !isObj(msg.usage)) return null;
  const u = msg.usage;
  const sum = (num(u.input_tokens) ?? 0) + (num(u.cache_read_input_tokens) ?? 0) + (num(u.cache_creation_input_tokens) ?? 0);
  return sum > 0 ? sum : null;
}

/** The context window of a result's `modelUsage` for [model] (exact key, `model[…]` key, else the largest), or null. */
function contextWindowOf(modelUsage: Obj, model: string | null): number | null {
  const windowOf = (v: unknown): number | null => {
    const w = isObj(v) ? num(v.contextWindow) : null;
    return w != null && w > 0 ? w : null;
  };
  const entries = Object.entries(modelUsage);
  if (model) {
    const exact = windowOf(modelUsage[model]);
    if (exact != null) return exact;
    for (const [k, v] of entries) {
      const w = k.startsWith(`${model}[`) ? windowOf(v) : null;
      if (w != null) return w;
    }
  }
  let best: number | null = null;
  for (const [, v] of entries) {
    const w = windowOf(v);
    if (w != null && (best == null || w > best)) best = w;
  }
  return best;
}

export function buildTranscript(events: FactoryEvent[], partials: unknown[]): { items: Item[]; summary: TranscriptSummary } {
  const items: Item[] = [];
  const tools = new Map<string, Extract<Item, { k: 'tool' }>>();
  const blocksPerMessage = new Map<string, number>();
  const summary: TranscriptSummary = { model: null, costUsd: null, turns: null, status: null, turnOpen: false, turnStartedAt: null, lastActivity: null, checkout: null, thinkingTokens: null, context: { used: null, window: null }, compacting: false };
  let contextModel: string | null = null;
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
        items.push({ k: 'system', id, level, text: str(s.text) ?? compactJson(p), sandbox: sandboxLogOf(s.sandbox), deploy: deployLogOf(s.deploy), sandboxVm: sandboxVmLogOf(s.sandboxVm) });
        continue;
      }
      if (ev.type === 'status') {
        const st = isObj(p) ? str(p.status) : null;
        if (st) summary.status = st as SessionStatus;
        if (st === 'stopped' || st === 'failed') {
          summary.compacting = false;
          items.push({ k: 'status', id, status: st });
          summary.turnOpen = false;
        }
        continue;
      }
      if (ev.type === 'images') {
        const images = imagesOf(p);
        if (images) items.push({ k: 'images', id, images });
        else items.push({ k: 'unknown', id, label: 'images', json: compactJson(p) });
        continue;
      }
      if (ev.type === 'checkout') {
        // Shown in the chat header, not in the transcript; the latest one wins.
        const checkout = checkoutOf(p);
        if (checkout) summary.checkout = checkout;
        else items.push({ k: 'unknown', id, label: 'checkout', json: compactJson(p) });
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
        const used = contextOfAssistant(p, msg);
        if (used != null) {
          summary.context.used = used;
          contextModel = str(msg.model) ?? contextModel;
        }
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
        summary.compacting = false;
        if (isObj(p.modelUsage)) summary.context.window = contextWindowOf(p.modelUsage, contextModel) ?? summary.context.window;
        items.push({ k: 'result', id, ok, subtype: str(p.subtype) ?? 'result', costUsd: num(p.total_cost_usd), turns: num(p.num_turns), durationMs: num(p.duration_ms), text: str(p.result) });
      } else if (t === 'stream_event' || t === 'tool_progress' || t === 'response_length') {
        // Live-only progress; stored ones (partials should not be; heartbeats of older versions) are skipped.
      } else if (t === 'system' && p.subtype === 'compact_boundary') {
        const c = compactOf(p.compact_metadata);
        summary.context.used = c.postTokens;
        summary.compacting = false;
        items.push({ k: 'compact', id, ...c });
      } else if (t === 'system' && p.subtype === 'status') {
        summary.compacting = p.status === 'compacting';
      } else if (t === 'system') {
        const sub = str(p.subtype) ?? 'system';
        if (sub !== 'hook_response' && sub !== 'status' && !TRANSIENT_SYSTEM_SUBTYPES.has(sub)) items.push({ k: 'unknown', id, label: `system.${sub}`, json: compactJson(p, 160) });
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
    if (isThinkingTokens(raw)) {
      summary.thinkingTokens = num(raw.estimated_tokens) ?? summary.thinkingTokens;
      continue;
    }
    if (isToolProgress(raw)) {
      // Matched by tool_use_id, else its parent (a hook's heartbeat names the tool there), else the latest open call.
      // A heartbeat of a call that has its result already is stale.
      const known = tools.get(str(raw.tool_use_id) ?? '') ?? tools.get(str(raw.parent_tool_use_id) ?? '');
      const tool = known ? (known.result ? undefined : known) : [...tools.values()].reverse().find((x) => !x.result);
      if (tool) tool.progress = { elapsedSeconds: num(raw.elapsed_time_seconds) ?? tool.progress?.elapsedSeconds ?? null };
      continue;
    }
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
  if (name.endsWith('__factory_sandbox_wait')) out = s('handle')?.trim() || 'latest command';
  if (name.endsWith('__factory_deploy_exec')) out = `${s('target') ?? '?'}: ${s('command') ?? ''}`;
  if (name.endsWith('__factory_deploy_copy')) out = `${s('target') ?? '?'}: ${s('path') ?? '?'} → ${s('dest') ?? '?'}`;
  if (name.endsWith('__factory_sandbox_vm_deploy')) out = input.background === true ? 'HEAD, in the background' : 'HEAD';
  if (name.endsWith('__factory_sandbox_vm_exec')) out = s('command');
  if (name.endsWith('__factory_sandbox_vm_wait')) out = s('handle')?.trim() || 'latest operation';
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
  if (name.endsWith('__factory_sandbox_exec') || name.endsWith('__factory_sandbox_wait')) {
    // The agent gets the tail as JSON; the full log is the sandbox block in the transcript.
    try {
      const j = JSON.parse(r.text) as { exitCode?: number; durationMs?: number; timedOut?: boolean; running?: boolean; elapsedMs?: number };
      if (j.running === true && typeof j.exitCode !== 'number') {
        return typeof j.elapsedMs === 'number' ? `Still running after ${Math.round(j.elapsedMs / 1000)} s` : 'Started in the background';
      }
      if (typeof j.exitCode === 'number') {
        const secs = typeof j.durationMs === 'number' ? ` in ${(j.durationMs / 1000).toFixed(1)} s` : '';
        return `${j.timedOut ? 'Timed out' : `Exit ${j.exitCode}`}${secs} · full log below`;
      }
    } catch {
      /* not JSON: show the text */
    }
  }
  if (name.endsWith('__factory_deploy_exec') || name.endsWith('__factory_deploy_copy')) {
    // The agent gets JSON; the full record is the deploy block in the transcript.
    try {
      const j = JSON.parse(r.text) as { exitCode?: number; durationMs?: number; timedOut?: boolean; bytes?: number };
      const secs = typeof j.durationMs === 'number' ? ` in ${(j.durationMs / 1000).toFixed(1)} s` : '';
      if (typeof j.exitCode === 'number') return `${j.timedOut ? 'Timed out' : `Exit ${j.exitCode}`}${secs} · full log below`;
      if (typeof j.bytes === 'number') return `Copied ${j.bytes.toLocaleString('en-GB')} bytes${secs}`;
    } catch {
      /* not JSON: show the text */
    }
  }
  if (name.includes('__factory_sandbox_vm_')) {
    const vm = sandboxVmSummary(name, r.text);
    if (vm) return vm;
  }
  if (r.images) return `${r.images} image${r.images > 1 ? 's' : ''}${r.text ? ` + ${lines} lines` : ''}`;
  return null;
}

/** The one-line summary of a sandbox VM tool's JSON answer (the full logs are the sandbox VM blocks in the transcript). */
function sandboxVmSummary(name: string, text: string): string | null {
  let j: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(text);
    if (!isObj(parsed)) return null;
    j = parsed;
  } catch {
    return null;
  }
  const secs = (ms: unknown) => (typeof ms === 'number' ? ` in ${(ms / 1000).toFixed(1)} s` : '');
  if (name.endsWith('__factory_sandbox_vm_info')) {
    const vm = [str(j.name), str(j.user) && str(j.host) ? `(${str(j.user)}@${str(j.host)})` : null].filter(Boolean).join(' ');
    if (j.available !== true) return `Not available${str(j.reason) ? `: ${str(j.reason)}` : ''}`;
    const lease = isObj(j.lease) ? j.lease : {};
    const state = lease.heldByThisRun === true ? 'held by this run' : isObj(lease.heldByOther) ? 'in use by another run' : lease.resetting === true ? 'being reset' : 'free';
    return `${vm || 'Sandbox VM'} · ${state}`;
  }
  if (j.running === true && typeof j.exitCode !== 'number' && typeof j.ok !== 'boolean') {
    return typeof j.elapsedMs === 'number' ? `Still running after ${Math.round(j.elapsedMs / 1000)} s` : 'Started in the background';
  }
  if (typeof j.ok === 'boolean' && Array.isArray(j.steps)) {
    const sha = str(j.sha)?.slice(0, 7);
    const dirty = j.dirty === true ? ' (uncommitted changes not deployed)' : '';
    return j.ok
      ? `Deployed ${sha ?? 'HEAD'}${secs(j.durationMs)}${dirty} · full log below`
      : `Failed at ${str(j.failedStep) ?? 'a step'}${secs(j.durationMs)} · full log below`;
  }
  if (typeof j.exitCode === 'number') return `${j.timedOut === true ? 'Timed out' : `Exit ${j.exitCode}`}${secs(j.durationMs)} · full log below`;
  return null;
}
