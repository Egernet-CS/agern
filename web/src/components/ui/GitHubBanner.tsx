import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { GitHubVerdictState } from '../../api';
import { BANNER_DISMISSED_KEY, GITHUB_STATUS_PATH, bannerText, bannerVisible, nextDismissed } from '../../lib/githubStatus';

function readDismissed(): string | null {
  try {
    return sessionStorage.getItem(BANNER_DISMISSED_KEY);
  } catch {
    return null;
  }
}

function writeDismissed(v: string | null) {
  try {
    if (v === null) sessionStorage.removeItem(BANNER_DISMISSED_KEY);
    else sessionStorage.setItem(BANNER_DISMISSED_KEY, v);
  } catch {
    // Storage unavailable (private mode): the dismissal lasts until the next reload.
  }
}

/**
 * The shell's one-line banner while GitHub is degraded or down for Agern (docs/ARCHITECTURE.md §10). Dismissing hides
 * it for this verdict in this tab; it comes back when the verdict changes to the other non-working state, or after
 * GitHub was working in between.
 */
export function GitHubBanner({ state, pathname }: { state: GitHubVerdictState | undefined; pathname: string }) {
  const [dismissed, setDismissed] = useState<string | null>(readDismissed);

  useEffect(() => {
    if (!state) return;
    const next = nextDismissed(state, readDismissed());
    if (next !== readDismissed()) writeDismissed(next);
    setDismissed(next);
  }, [state]);

  if (!state || !bannerVisible(state, pathname, dismissed)) return null;
  return (
    <div className={`gh-banner gh-banner--${state}`} role="status">
      <span className="gh-banner-glyph" aria-hidden>{state === 'down' ? '✕' : '!'}</span>
      <span className="gh-banner-text">
        {bannerText(state)} <Link to={GITHUB_STATUS_PATH}>See GitHub status</Link>
      </span>
      <button
        type="button"
        className="gh-banner-close"
        aria-label="Dismiss"
        onClick={() => {
          writeDismissed(state);
          setDismissed(state);
        }}
      >
        ×
      </button>
    </div>
  );
}
