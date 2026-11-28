// Texts and rules for the time and cost of runs and releases (docs/ARCHITECTURE.md §6 "Time and cost"). Pure, so the
// unit tests run them without a browser; the hooks that load the totals are in useTotals.ts.
import type { Cost, TotalsTime, WorkKind } from '../api/types';
import { cost, duration } from './format.ts';

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
/** Like `cost()`, but nothing at all is `$0.00` (not `$0.0000`). */
const usd = (v: number) => (v === 0 ? '$0.00' : cost(v));

/** `$4.31`, or `≥ $4.31 (2 sessions without a cost)` when some sessions ended without reporting one. */
export function amountText(c: Cost): string {
  if (c.unknownSessions === 0) return usd(c.usd);
  return `≥ ${usd(c.usd)} (${plural(c.unknownSessions, 'session')} without a cost)`;
}

/** Just the amount, with `≥` when it is a lower bound (for tight places like an issue row). */
export const shortAmount = (c: Cost) => (c.unknownSessions > 0 ? `≥ ${usd(c.usd)}` : usd(c.usd));

const SUBSCRIPTION_NOTE = 'API-equivalent cost reported by Claude Code (subscription), not what was billed.';
const API_KEY_NOTE = 'API cost (API key).';

/** What the amount means: subscription (API-equivalent), API key (actual API cost), or both with their parts. */
export function credentialNote(c: Cost): string {
  if (c.apiKeyUsd > 0 && c.subscriptionUsd > 0) {
    return `${cost(c.subscriptionUsd)} API-equivalent (subscription, not what was billed) + ${cost(c.apiKeyUsd)} API cost (API key).`;
  }
  if (c.apiKeyUsd > 0) return API_KEY_NOTE;
  return SUBSCRIPTION_NOTE;
}

/** The short form of [credentialNote] for the run header. */
export function credentialShort(c: Cost): string {
  if (c.apiKeyUsd > 0 && c.subscriptionUsd > 0) return `${cost(c.subscriptionUsd)} API-equivalent + ${cost(c.apiKeyUsd)} API cost`;
  if (c.apiKeyUsd > 0) return 'API cost';
  return 'API-equivalent';
}

/** `2 agents running; their cost is added when they finish.`, or null when none runs. */
export function runningNote(c: Cost): string | null {
  if (c.runningSessions <= 0) return null;
  return `${plural(c.runningSessions, 'agent')} running; their cost is added when they finish.`;
}

/**
 * [t] as of [now], when it was received at [receivedAt]: while it runs, elapsed grows by the time since, which goes to
 * working while an agent works and to waiting otherwise.
 */
export function tick(t: TotalsTime, receivedAt: number, now: number): TotalsTime {
  if (!t.running) return t;
  const d = Math.max(0, now - receivedAt);
  return {
    ...t,
    elapsedMs: t.elapsedMs + d,
    workingMs: t.working ? t.workingMs + d : t.workingMs,
    waitingMs: t.working ? t.waitingMs : t.waitingMs + d,
  };
}

/** The largest cost among a release's issues: their bars are relative to it. */
export const maxUsd = (issues: { cost: Cost }[]) => issues.reduce((m, i) => Math.max(m, i.cost.usd), 0);

/** The width of an issue's cost bar, 0..1, relative to the most expensive issue ([max]); 0 when [max] is 0. */
export const barFraction = (usd: number, max: number) => (max > 0 ? Math.min(1, Math.max(0, usd / max)) : 0);

export const WORK_LABEL: Record<WorkKind, string> = {
  build: 'First build',
  fix: 'Fix rounds',
  update: 'Update rounds',
  retry: 'Retries',
  conversation: 'Conversation at needs human',
  sync: 'Bringing the release up to date',
};

export const workLabel = (key: string) => WORK_LABEL[key as WorkKind] ?? key;
export const roleLabel = (key: string) => (key === 'advisor' ? 'advisor conversations' : key);

/** `"Issue #12: 1h 12m elapsed, 40m 00s agents working, $4.31 in 2 runs"` (the accessible text of an issue's figures). */
export function issueLabel(n: number, t: TotalsTime, c: Cost, runs: number): string {
  return `Issue #${n}: ${duration(t.elapsedMs)} elapsed, ${duration(t.workingMs)} agents working, ${amountText(c)} in ${plural(runs, 'run')}`;
}

export const runsText = (n: number) => plural(n, 'run');
export const turnsText = (n: number) => plural(n, 'turn');
