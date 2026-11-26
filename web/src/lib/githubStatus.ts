// Texts and rules of the GitHub status page and banner (docs/ARCHITECTURE.md §6 "GitHub status", §10).
import type { GitHubRateLimit, GitHubStatus, GitHubVerdictState } from '../api/types';

export const GITHUB_STATUS_PATH = '/github-status';
/** sessionStorage key of the dismissed banner: the verdict (`degraded` or `down`) it was dismissed for. */
export const BANNER_DISMISSED_KEY = 'agern.githubBanner.dismissed';

export function verdictHeading(state: GitHubVerdictState): string {
  switch (state) {
    case 'working':
      return 'GitHub is working';
    case 'degraded':
      return 'GitHub is degraded for Agern';
    case 'down':
      return 'GitHub is down for Agern';
  }
}

export function verdictExplanation(state: GitHubVerdictState): string {
  switch (state) {
    case 'working':
      return 'Runs, chats and releases can use GitHub normally.';
    case 'degraded':
      return 'Some GitHub calls are failing. Runs and releases wait and retry by themselves; actions you start yourself (creating issues, starting or merging runs, releasing) may fail — try them again in a few minutes.';
    case 'down':
      return 'Agern cannot reach GitHub right now. Runs and releases wait and continue by themselves when GitHub is back; chats keep working, but anything that needs GitHub (issues, pull requests, pushes, merges) fails until then.';
  }
}

/** A glyph so the verdict is not told by colour alone. */
export function verdictGlyph(state: GitHubVerdictState): string {
  return state === 'working' ? '✓' : state === 'degraded' ? '!' : '✕';
}

/** The user-menu item: "GitHub status", with " — degraded" / " — down" while GitHub is not working. */
export function menuLabel(state: GitHubVerdictState | undefined): string {
  return state === 'degraded' || state === 'down' ? `GitHub status — ${state}` : 'GitHub status';
}

/** `degraded_performance` → "degraded performance". */
export function componentStatusWords(status: string): string {
  return status.replace(/_/g, ' ');
}

/** Relevant components first, otherwise in GitHub's order. */
export function sortComponents<T extends { relevant: boolean }>(components: T[]): T[] {
  return [...components.filter((c) => c.relevant), ...components.filter((c) => !c.relevant)];
}

/** "in 12m", "in 2h", or "now" for a time that has passed. */
export function timeUntil(isoTs: string, now = Date.now()): string {
  const s = Math.round((Date.parse(isoTs) - now) / 1000);
  if (s <= 30) return 'now';
  const m = Math.round(s / 60);
  if (m < 60) return `in ${m}m`;
  return `in ${Math.round(m / 60)}h`;
}

/** "4987 of 5000 left, resets in 42m", or "unknown". */
export function rateLimitSentence(l: GitHubRateLimit | null, now = Date.now()): string {
  if (!l) return 'unknown';
  return `${l.remaining} of ${l.limit} left, resets ${timeUntil(l.resetAt, now)}`;
}

/** The line about the other tokens in use (shown without their owners). */
export function otherTokensSentence(n: number, lowest: GitHubRateLimit | null, now = Date.now()): string {
  if (n === 0) return 'No other tokens have been used recently.';
  const head = `${n} other token${n === 1 ? '' : 's'}`;
  if (!lowest) return `${head}.`;
  return `${head}; the lowest has ${lowest.remaining} of ${lowest.limit} REST calls left (resets ${timeUntil(lowest.resetAt, now)}).`;
}

/**
 * Whether the shell shows the banner: the verdict is degraded or down, the page is not the status page itself, and the
 * banner was not dismissed for this same verdict.
 */
export function bannerVisible(state: GitHubVerdictState | undefined, pathname: string, dismissed: string | null): boolean {
  if (state !== 'degraded' && state !== 'down') return false;
  if (pathname === GITHUB_STATUS_PATH || pathname.startsWith(`${GITHUB_STATUS_PATH}/`)) return false;
  return dismissed !== state;
}

/**
 * The stored dismissal after a poll answered [state]: cleared once GitHub is working again, so the next problem shows
 * the banner again; kept otherwise (a change to the other non-working state shows it because it no longer matches).
 */
export function nextDismissed(state: GitHubVerdictState, dismissed: string | null): string | null {
  return state === 'working' ? null : dismissed;
}

/** The banner's sentence. */
export function bannerText(state: GitHubStatus['verdict']['state']): string {
  return `${verdictHeading(state)}.`;
}
