// The mock's GitHub status (docs/ARCHITECTURE.md §6 "GitHub status"). Scenarios: default (working), github-degraded
// (the situation of 25 Nov: 5xx on issues and releases while githubstatus.com says all is well) and github-down.
import type { GitHubCallCounts, GitHubKindStatus, GitHubRateLimit, GitHubStatus, User } from '../api/types';
import { scenario } from './engine';

const KINDS: [string, string][] = [
  ['issues', 'Issues and labels'],
  ['pulls', 'Pull requests'],
  ['contents', 'Contents'],
  ['git_data', 'Branches and commits (API)'],
  ['checks', 'Checks and statuses'],
  ['repos', 'Repositories'],
  ['releases', 'Releases'],
  ['actions', 'Actions'],
  ['graphql', 'GraphQL'],
  ['git', 'Git (clone, fetch, push)'],
  ['other', 'Other'],
];

/** Plausible call volumes per kind (15 min, 24 h). */
const VOLUME: Record<string, [number, number]> = {
  issues: [42, 1630], pulls: [12, 410], contents: [18, 720], git_data: [6, 230], checks: [24, 960],
  repos: [30, 1210], releases: [0, 14], actions: [0, 0], graphql: [0, 0], git: [9, 340], other: [3, 96],
};

const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
const ahead = (ms: number) => new Date(Date.now() + ms).toISOString();
const counts = (calls: number, f: Partial<GitHubCallCounts> = {}): GitHubCallCounts => ({ calls, serverErrors: 0, network: 0, rateLimited: 0, auth: 0, ...f });
const limit = (remaining: number, max = 5000, resetMin = 42): GitHubRateLimit => ({ limit: max, remaining, resetAt: ahead(resetMin * 60_000), observedAt: ago(20_000) });

function kinds(): GitHubKindStatus[] {
  return KINDS.map(([kind, label]) => {
    const [n15, n24] = VOLUME[kind];
    const k: GitHubKindStatus = { kind, label, last15m: counts(n15), last24h: counts(n24), lastError: null };
    if (scenario === 'github-degraded' && (kind === 'issues' || kind === 'releases')) {
      const n = kind === 'issues' ? 4 : 2;
      k.last15m = counts(n15 + n, { serverErrors: n });
      k.last24h = counts(n24 + n, { serverErrors: n });
      k.lastError = {
        at: ago(kind === 'issues' ? 70_000 : 160_000), class: 'server_error', status: kind === 'issues' ? 500 : 502,
        requestId: kind === 'issues' ? '8F3A:1B2C:3D4E:5F60' : 'C0DE:4A11:77B2:0E19', message: kind === 'issues' ? 'HTTP 500' : 'HTTP 502: Server Error',
      };
    }
    if (scenario === 'github-down' && n15 > 0) {
      const n = Math.max(1, Math.round(n15 / 6));
      k.last15m = counts(n, { network: n });
      k.last24h = counts(n24 + n, { network: n });
      k.lastError = {
        at: ago(30_000), class: 'network', status: null, requestId: null,
        message: kind === 'git' ? "fatal: unable to access 'https://github.com/…': Could not resolve host: github.com" : 'HttpConnectTimeoutException: HTTP connect timed out',
      };
    }
    if (scenario === 'default' && kind === 'checks') {
      k.last24h = counts(n24, { rateLimited: 1 });
      k.lastError = { at: ago(5 * 3_600_000), class: 'rate_limited', status: 403, requestId: '9A1B:22C3:0F44:7D10', message: 'HTTP 403: API rate limit exceeded for user ID [redacted].' };
    }
    return k;
  });
}

export function githubStatus(me: User, brief: boolean): GitHubStatus {
  const degraded = scenario === 'github-degraded';
  const down = scenario === 'github-down';
  const status: GitHubStatus = {
    verdict: degraded
      ? { state: 'degraded', reason: '6 of 72 GitHub calls by Agern failed in the last 5 minutes (6 server errors, 0 timeouts or network errors).' }
      : down
        ? { state: 'down', reason: 'All 21 GitHub calls by Agern failed in the last 5 minutes (0 server errors, 21 timeouts or network errors).' }
        : { state: 'working', reason: "Agern's calls to GitHub are succeeding." },
    generatedAt: new Date().toISOString(),
    official: down
      ? { state: 'unreachable', indicator: null, description: null, components: [], incidents: [], fetchedAt: ago(25_000), error: 'HttpConnectTimeoutException: HTTP connect timed out' }
      : { state: 'ok', indicator: 'none', description: 'All Systems Operational', components: [], incidents: [], fetchedAt: ago(25_000), error: null },
    kinds: brief ? [] : kinds(),
    rateLimits: brief
      ? null
      : {
          own: me.hasGithubToken ? { rest: limit(4987), graphql: limit(5000, 5000, 60) } : null,
          otherTokens: 2,
          lowestOther: limit(3120, 5000, 17),
        },
  };
  return status;
}
