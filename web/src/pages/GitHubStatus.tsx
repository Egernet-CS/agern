import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api, type GitHubCallCounts, type GitHubKindStatus, type GitHubStatus } from '../api';
import { Loading } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { dateTime, timeAgo } from '../lib/format';
import {
  componentStatusWords,
  otherTokensSentence,
  rateLimitSentence,
  sortComponents,
  verdictExplanation,
  verdictGlyph,
  verdictHeading,
} from '../lib/githubStatus';
import { useNow } from '../lib/useNow';

/** The GitHub status page (docs/ARCHITECTURE.md §10): verdict, GitHub's own status, what Agern sees, rate limits. */
export function GitHubStatusPage() {
  const q = useAsync(() => api.githubStatus(), [], { pollMs: 60_000 });
  const now = useNow(15_000);

  useEffect(() => {
    document.title = 'GitHub status · Agern';
  }, []);

  const s = q.data;
  return (
    <div className="page page--wide gh-status">
      <div className="page-head">
        <div>
          <h1 className="page-title">GitHub status</h1>
          <p className="muted">Whether GitHub is working for this Agern right now: GitHub’s own status page and what Agern’s own calls see.</p>
        </div>
        <button type="button" className="btn btn--ghost" onClick={q.reload} disabled={q.loading} aria-busy={q.loading}>
          {q.loading && s ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {q.loading && !s && !q.error && <Loading label="Loading GitHub status" />}
      {q.error && !s && (
        <div className="notice notice--error" role="alert">
          <div className="notice-text">
            <strong>The GitHub status could not be loaded.</strong>
            <div>{q.error.message}</div>
          </div>
          <div className="notice-action">
            <button type="button" className="btn btn--ghost" onClick={q.reload}>Try again</button>
          </div>
        </div>
      )}
      {s && (
        <>
          <Verdict status={s} now={now} />
          <Official status={s} now={now} />
          <Calls kinds={s.kinds} now={now} />
          <RateLimits status={s} now={now} />
        </>
      )}
    </div>
  );
}

function Verdict({ status, now }: { status: GitHubStatus; now: number }) {
  const v = status.verdict;
  return (
    <section className={`gh-verdict gh-verdict--${v.state}`} data-verdict={v.state} aria-labelledby="gh-verdict-heading">
      <span className="gh-verdict-glyph" aria-hidden>{verdictGlyph(v.state)}</span>
      <div>
        <h2 id="gh-verdict-heading" className="gh-verdict-title">{verdictHeading(v.state)}</h2>
        <p className="gh-verdict-explain">{verdictExplanation(v.state)}</p>
        <p className="muted small">
          {v.reason} <span title={dateTime(status.generatedAt)}>Updated {timeAgo(status.generatedAt, now)}.</span>
        </p>
      </div>
    </section>
  );
}

function Official({ status, now }: { status: GitHubStatus; now: number }) {
  const o = status.official;
  const components = sortComponents(o.components);
  return (
    <section className="gh-section" aria-labelledby="gh-official">
      <h2 id="gh-official" className="gh-h2">GitHub’s official status</h2>
      {o.state === 'disabled' && <p className="muted">The official GitHub status is not checked in this installation.</p>}
      {o.state === 'unreachable' && (
        <div className="notice notice--warn" role="note">
          <div className="notice-text">
            <strong>GitHub’s status page (githubstatus.com) could not be reached from the Agern server.</strong>
            <div>
              {o.error && <span className="mono">{o.error}</span>}
              {o.fetchedAt && <span title={dateTime(o.fetchedAt)}> · tried {timeAgo(o.fetchedAt, now)}</span>}
            </div>
          </div>
        </div>
      )}
      {o.state === 'ok' && (
        <>
          {o.description && <p className="gh-official-desc">{o.description}</p>}
          {components.length === 0 && o.incidents.length === 0 && <p className="muted">GitHub reports no problems.</p>}
          {components.length > 0 && (
            <ul className="gh-list" aria-label="Components with problems">
              {components.map((c) => (
                <li key={c.name}>
                  <strong>{c.name}</strong>: {componentStatusWords(c.status)}
                  {c.relevant && <span className="muted small"> · used by Agern</span>}
                </li>
              ))}
            </ul>
          )}
          {o.incidents.length > 0 && (
            <ul className="gh-list" aria-label="Open incidents">
              {o.incidents.map((i, n) => (
                <li key={`${i.title}-${n}`}>
                  <strong>{i.title}</strong> · {componentStatusWords(i.status)}
                  {i.updatedAt && <span className="muted" title={dateTime(i.updatedAt)}> · updated {timeAgo(i.updatedAt, now)}</span>}
                  {i.url && (
                    <>
                      {' · '}
                      <a href={i.url} target="_blank" rel="noopener noreferrer">Details on githubstatus.com</a>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
          {status.verdict.state !== 'working' && o.indicator === 'none' && (
            <p className="muted small">
              GitHub’s status page can say that everything works while calls from Agern fail; the table below shows what Agern sees.
            </p>
          )}
        </>
      )}
    </section>
  );
}

function Count({ n }: { n: number }) {
  return n === 0 ? <span className="muted">0</span> : <strong className="gh-fail">{n}</strong>;
}

function CountCells({ c }: { c: GitHubCallCounts }) {
  return (
    <>
      <td className="num">{c.calls}</td>
      <td className="num"><Count n={c.serverErrors} /></td>
      <td className="num"><Count n={c.network} /></td>
      <td className="num"><Count n={c.rateLimited} /></td>
      <td className="num"><Count n={c.auth} /></td>
    </>
  );
}

function Calls({ kinds, now }: { kinds: GitHubKindStatus[]; now: number }) {
  const cols = ['Calls', '5xx', 'Timeouts/network', 'Rate limited', 'Auth (401/403)'];
  return (
    <section className="gh-section" aria-labelledby="gh-calls">
      <h2 id="gh-calls" className="gh-h2">What Agern sees</h2>
      <div className="table-wrap gh-table-wrap">
        <table className="table gh-table">
          <caption className="sr-only">Agern’s own calls to GitHub per kind, in the last 15 minutes and the last 24 hours</caption>
          <thead>
            <tr>
              <th scope="col" rowSpan={2}>Kind</th>
              <th scope="colgroup" colSpan={5} className="gh-group">Last 15 minutes</th>
              <th scope="colgroup" colSpan={5} className="gh-group">Last 24 hours</th>
              <th scope="col" rowSpan={2}>Last error</th>
            </tr>
            <tr>
              {[...cols, ...cols].map((c, i) => (
                <th scope="col" key={i} className={i === 0 || i === 5 ? 'gh-group' : undefined}>{c}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {kinds.map((k) => (
              <tr key={k.kind}>
                <th scope="row">{k.label}</th>
                <CountCells c={k.last15m} />
                <CountCells c={k.last24h} />
                <td className="gh-last-error">
                  {k.lastError ? (
                    <>
                      <span>{k.lastError.message}</span>
                      <span className="muted small">
                        <time dateTime={k.lastError.at} title={dateTime(k.lastError.at)}>{timeAgo(k.lastError.at, now)}</time>
                        {k.lastError.status != null && !k.lastError.message.startsWith(`HTTP ${k.lastError.status}`) && <> · HTTP {k.lastError.status}</>}
                        {k.lastError.requestId && <> · request id <span className="mono">{k.lastError.requestId}</span></>}
                      </span>
                    </>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function RateLimits({ status, now }: { status: GitHubStatus; now: number }) {
  const r = status.rateLimits;
  if (!r) return null;
  return (
    <section className="gh-section" aria-labelledby="gh-limits">
      <h2 id="gh-limits" className="gh-h2">Rate limits</h2>
      <h3 className="gh-h3">Your token</h3>
      {r.own ? (
        <dl className="gh-limits">
          <dt>REST</dt>
          <dd>{rateLimitSentence(r.own.rest, now)}</dd>
          <dt>GraphQL</dt>
          <dd>{rateLimitSentence(r.own.graphql, now)}</dd>
        </dl>
      ) : (
        <p>
          You have no GitHub token registered. <Link to="/settings">Register one in Settings</Link>.
        </p>
      )}
      <h3 className="gh-h3">Other tokens in use</h3>
      <p>{otherTokensSentence(r.otherTokens, r.lowestOther, now)}</p>
      <p className="muted small">Tokens are shown without their owners.</p>
    </section>
  );
}
