// Time and cost of runs and releases in the mock (docs/ARCHITECTURE.md §6 "Time and cost"), in the server's shape and
// computed by the same rules from the mock's runs and sessions. Simplification: a retry run is "a later run of the same
// issue (in the same release, or among single runs)".
import type { AgentSession, Cost, IssueTotals, Release, ReleaseTotals, Run, RunTotals, TotalsRow, TotalsTime, WorkKind } from '../api/types';
import { db } from './engine';

const ROLE_ORDER = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver', 'advisor'];
const WORK_ORDER: WorkKind[] = ['build', 'fix', 'update', 'retry', 'conversation', 'sync'];
const active = (s: AgentSession) => s.status === 'starting' || s.status === 'running';
const round6 = (v: number) => Math.round(v * 1e6) / 1e6;

type Window = { start: number; end: number; running: boolean };

function sessionsOf(runIds: Set<string>): AgentSession[] {
  return [...db.sessions.values()].filter((s) => s.runId && runIds.has(s.runId));
}

/** The stage round of a session: from the stage that references it, else its title, else 0; null for advisors. */
function roundOf(s: AgentSession, run: Run | undefined): number | null {
  if (s.kind === 'advisor' || s.kind === 'chat') return null;
  const st = run?.stages.find((x) => x.sessionId === s.id);
  if (st) return st.round;
  const m = / round (\d+)$/.exec(s.title ?? '');
  return m ? Number(m[1]) : 0;
}

const credKind = (s: AgentSession) => db.users.find((u) => u.username === s.owner.username)?.claudeCredentialKind ?? null;

function interval(s: AgentSession, now: number): [number, number] | null {
  const end = s.endedAt ? Date.parse(s.endedAt) : active(s) ? now : null;
  const start = Date.parse(s.createdAt);
  return end !== null && end > start ? [start, end] : null;
}

function unionMs(list: [number, number][], from: number, to: number): number {
  const clipped = list
    .map(([a, b]) => [Math.max(a, from), Math.min(b, to)] as [number, number])
    .filter(([a, b]) => b > a)
    .sort((x, y) => x[0] - y[0]);
  let total = 0;
  let cur: [number, number] | null = null;
  for (const [a, b] of clipped) {
    if (!cur || a > cur[1]) {
      if (cur) total += cur[1] - cur[0];
      cur = [a, b];
    } else if (b > cur[1]) cur[1] = b;
  }
  if (cur) total += cur[1] - cur[0];
  return total;
}

function time(w: Window | null, sessions: AgentSession[], now: number): TotalsTime {
  if (!w) return { elapsedMs: 0, workingMs: 0, waitingMs: 0, running: false, working: false };
  const stage = sessions.filter((s) => s.kind !== 'advisor');
  const elapsedMs = Math.max(0, w.end - w.start);
  const workingMs = unionMs(stage.map((s) => interval(s, now)).filter((x): x is [number, number] => !!x), w.start, w.start + elapsedMs);
  return { elapsedMs, workingMs, waitingMs: Math.max(0, elapsedMs - workingMs), running: w.running, working: stage.some(active) };
}

function cost(sessions: AgentSession[]): Cost {
  let api = 0;
  let sub = 0;
  let runningSessions = 0;
  let unknownSessions = 0;
  for (const s of sessions) {
    if (s.costUsd != null) {
      if (credKind(s) === 'api_key') api += s.costUsd;
      else sub += s.costUsd;
    } else if (active(s)) runningSessions++;
    else unknownSessions++;
  }
  const apiKeyUsd = round6(api);
  const subscriptionUsd = round6(sub);
  return {
    usd: round6(apiKeyUsd + subscriptionUsd), apiKeyUsd, subscriptionUsd,
    sessions: sessions.length, turns: sessions.reduce((n, s) => n + (s.numTurns ?? 0), 0), runningSessions, unknownSessions,
  };
}

const isRetryRun = (r: Run) =>
  [...db.runs.values()].some(
    (o) => o.repo === r.repo && o.issueNumber === r.issueNumber && (o.releaseId ?? null) === (r.releaseId ?? null) && o.createdAt < r.createdAt,
  );

function workKey(s: AgentSession, run: Run | undefined, firstOfAttempt: Map<string, string>): WorkKind {
  const round = roundOf(s, run);
  if (run?.kind === 'sync') return 'sync';
  if (s.kind === 'advisor') return 'conversation';
  if ((run && isRetryRun(run)) || firstOfAttempt.get(`${s.runId}|${s.kind}|${round}`) !== s.id) return 'retry';
  if (round !== null && run?.updates?.some((u) => u.round === round)) return 'update';
  const from = run?.conversation.followUpFrom;
  if (from != null && (round ?? 0) >= from) return 'conversation';
  return (round ?? 0) === 0 ? 'build' : 'fix';
}

function breakdowns(sessions: AgentSession[], now: number): { byRole: TotalsRow[]; byWork: TotalsRow[] } {
  const firstOfAttempt = new Map<string, string>();
  for (const s of [...sessions].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))) {
    const key = `${s.runId}|${s.kind}|${roundOf(s, db.runs.get(s.runId!))}`;
    if (!firstOfAttempt.has(key)) firstOfAttempt.set(key, s.id);
  }
  const row = (key: string, list: AgentSession[]): TotalsRow => ({
    key,
    cost: cost(list),
    agentMs: list.reduce((n, s) => {
      const i = s.kind === 'advisor' ? null : interval(s, now);
      return n + (i ? i[1] - i[0] : 0);
    }, 0),
  });
  const group = (keyOf: (s: AgentSession) => string) => {
    const m = new Map<string, AgentSession[]>();
    for (const s of sessions) m.set(keyOf(s), [...(m.get(keyOf(s)) ?? []), s]);
    return m;
  };
  const roles = group((s) => s.kind);
  const roleKeys = [...ROLE_ORDER.filter((k) => roles.has(k)), ...[...roles.keys()].filter((k) => !ROLE_ORDER.includes(k)).sort()];
  const work = group((s) => workKey(s, db.runs.get(s.runId!), firstOfAttempt));
  return {
    byRole: roleKeys.map((k) => row(k, roles.get(k)!)),
    byWork: WORK_ORDER.filter((k) => work.has(k)).map((k) => row(k, work.get(k)!)),
  };
}

export function runTotalsOf(r: Run, now = Date.now()): RunTotals {
  const sessions = sessionsOf(new Set([r.id]));
  const w: Window = { start: Date.parse(r.createdAt), end: r.endedAt ? Date.parse(r.endedAt) : now, running: !r.endedAt };
  return { runId: r.id, time: time(w, sessions, now), cost: cost(sessions), ...breakdowns(sessions, now) };
}

function releaseWindow(r: Release, now: number): Window | null {
  if (!r.startedAt) return null;
  const end =
    r.status === 'released' ? r.releasedAt ?? r.endedAt ?? r.updatedAt
    : r.status === 'cancelled' ? r.endedAt ?? r.updatedAt
    : r.status === 'ready' ? r.readyAt
    : null;
  return { start: Date.parse(r.startedAt), end: end ? Date.parse(end) : now, running: !end };
}

export function releaseTotalsOf(r: Release, now = Date.now()): ReleaseTotals {
  const runs = [...db.runs.values()].filter((x) => x.releaseId === r.id).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const sessions = sessionsOf(new Set(runs.map((x) => x.id)));
  const w = releaseWindow(r, now);
  const final = r.status === 'released' || r.status === 'cancelled';
  const isIssueRun = (x: Run) => (x.kind ?? 'issue') === 'issue';
  const issues: IssueTotals[] = r.issues.map((it) => {
    const own = runs.filter((x) => isIssueRun(x) && x.issueNumber === it.number);
    if (!own.length) return { number: it.number, runs: 0, time: time(null, [], now), cost: cost([]) };
    const ids = new Set(own.map((x) => x.id));
    const list = sessions.filter((s) => ids.has(s.runId!));
    const start = Math.min(...own.map((x) => Date.parse(x.createdAt)));
    const allEnded = own.every((x) => x.endedAt);
    let end = allEnded ? Math.max(...own.map((x) => Date.parse(x.endedAt!))) : now;
    let running = !allEnded;
    if (w && final && end > w.end) {
      end = w.end;
      running = false;
    }
    return { number: it.number, runs: own.length, time: time({ start, end: Math.max(start, end), running }, list, now), cost: cost(list) };
  });
  const listed = new Set(r.issues.map((i) => i.number));
  const otherRuns = runs.filter((x) => !(isIssueRun(x) && listed.has(x.issueNumber)));
  const otherIds = new Set(otherRuns.map((x) => x.id));
  return {
    releaseId: r.id,
    time: time(w, sessions, now),
    cost: cost(sessions),
    runs: runs.length,
    runIds: runs.map((x) => x.id),
    issues,
    other: otherRuns.length ? { runs: otherRuns.length, cost: cost(sessions.filter((s) => otherIds.has(s.runId!))) } : null,
    ...breakdowns(sessions, now),
  };
}
