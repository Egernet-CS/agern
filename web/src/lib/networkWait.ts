// Texts for runs and releases that wait for the network (docs/ARCHITECTURE.md §6 "Transient failures").
import type { Release, ReleaseIssue } from '../api/types';

/** "14:05" when [isoTs] is on the same day as [now], else "3 Dec 14:05". */
export function shortClock(isoTs: string, now = Date.now()): string {
  const d = new Date(isoTs);
  const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === new Date(now).toDateString()) return time;
  return `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`;
}

/** Ends a text with a full stop unless it already ends a sentence. */
const sentence = (text: string) => (/[.!?]$/.test(text.trim()) ? text.trim() : `${text.trim()}.`);

/** The status line of a release paused with reason `network`; null for any other release. */
export function releaseNetworkLine(release: Pick<Release, 'status' | 'pause'>, now = Date.now()): string | null {
  const p = release.pause;
  if (release.status !== 'paused' || p?.reason !== 'network') return null;
  const since = p.since ? ` since ${shortClock(p.since, now)}` : '';
  const note = p.note ? ` ${sentence(p.note)}` : '';
  const next = p.resumeAt ? ` Next try at ${shortClock(p.resumeAt, now)}.` : '';
  return `Waiting for the network${since}.${note}${next}`;
}

/** The line for a running issue whose run waits for the network; null when it does not wait. */
export function runningWaitLine(issue: Pick<ReleaseIssue, 'number' | 'title' | 'state' | 'waiting'>, now = Date.now()): string | null {
  const w = issue.waiting;
  if (issue.state !== 'running' || !w) return null;
  return `#${issue.number} ${issue.title} is waiting for the network since ${shortClock(w.since, now)}; next try at ${shortClock(w.nextAttemptAt, now)}.`;
}

/** The note on a waiting issue's row: the same, without the issue's name. */
export function issueWaitNote(issue: Pick<ReleaseIssue, 'state' | 'waiting'>, now = Date.now()): string | null {
  const w = issue.waiting;
  if (issue.state !== 'running' || !w) return null;
  return `Waiting for the network since ${shortClock(w.since, now)}; next try at ${shortClock(w.nextAttemptAt, now)}.`;
}

/** The issue a release paused with reason `network` could not start: its first waiting issue. */
export function networkBlockedIssue(release: Pick<Release, 'status' | 'pause' | 'issues'>): ReleaseIssue | null {
  if (release.status !== 'paused' || release.pause?.reason !== 'network') return null;
  return release.issues.find((i) => i.state === 'waiting') ?? null;
}
