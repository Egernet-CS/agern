import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, isRunWorking, isSessionActive } from '../api';
import { Art, Empty, ErrorBox, Loading, Notice, ProjectIcon } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useMe } from '../lib/auth';
import { timeAgo } from '../lib/format';
import { useLiveRuns, useLiveSessions } from '../lib/realtime';

export function SetupNotices() {
  const me = useMe();
  return (
    <>
      {!me.hasGithubToken && (
        <Notice tone="warn" title="Add your GitHub token to see projects" action={<Link className="btn btn--primary" to="/settings#github">Add GitHub token</Link>}>
          Agern shows exactly the repositories your own GitHub token can read — nothing more. Until you add one, the project list stays empty.
        </Notice>
      )}
      {me.hasGithubToken && !me.hasClaudeCredential && (
        <Notice tone="info" title="Register your Claude credential to start agents" action={<Link className="btn" to="/settings#claude">Set up Claude</Link>}>
          You can browse projects and observe other people’s agents, but chat and pipeline runs need your own Claude credential. Agents always run on the credential of the person who starts them.
        </Notice>
      )}
    </>
  );
}

export function ProjectsPage() {
  const me = useMe();
  const projects = useAsync(() => api.projects(), [me.hasGithubToken]);
  const agentsBase = useAsync(() => api.agents(true), []);
  const runsBase = useAsync(() => api.runs({ active: true }), []);
  const agents = useLiveSessions(agentsBase.data, () => true).filter(isSessionActive);
  const runs = useLiveRuns(runsBase.data, () => true);
  const activeRuns = runs.filter(isRunWorking);
  const waiting = runs.filter((r) => r.status === 'needs_human');
  const [q, setQ] = useState('');

  useEffect(() => {
    document.title = 'Projects · Agern';
  }, []);

  const perRepo = useMemo(() => {
    const m = new Map<string, { agents: number; runs: number }>();
    for (const a of agents) m.set(a.repo, { agents: (m.get(a.repo)?.agents ?? 0) + 1, runs: m.get(a.repo)?.runs ?? 0 });
    for (const r of activeRuns) m.set(r.repo, { agents: m.get(r.repo)?.agents ?? 0, runs: (m.get(r.repo)?.runs ?? 0) + 1 });
    return m;
  }, [agents, activeRuns]);

  const list = (projects.data ?? [])
    .filter((p) => !q || `${p.name} ${p.description ?? ''} ${p.language ?? ''}`.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => (perRepo.get(b.name)?.agents ?? 0) - (perRepo.get(a.name)?.agents ?? 0) || (b.pushedAt ?? '').localeCompare(a.pushedAt ?? ''));

  return (
    <div className="page page--wide">
      <section className="hero">
        <Art src="/art/hero.webp" className="hero-art" />
        <div className="hero-copy">
          <p className="eyebrow">Good {greeting()}, {me.displayName.split(' ')[0] || me.username}</p>
          <h1>{!me.hasGithubToken ? 'Welcome to the floor.' : agents.length ? 'The floor is running.' : 'The floor is quiet.'}</h1>
          <p className="hero-lede">Pick a project, talk to its agent, or hand an issue to the pipeline: spec, build, four reviewers, approval, merge.</p>
          <dl className="hero-stats">
            <div>
              <dt>projects</dt>
              <dd>{projects.data?.length ?? '–'}</dd>
            </div>
            <div className={agents.length ? 'is-live' : ''}>
              <dt>agents active</dt>
              <dd>{agentsBase.data ? agents.length : '–'}</dd>
            </div>
            <div>
              <dt>runs in flight</dt>
              <dd>{runsBase.data ? activeRuns.length : '–'}</dd>
            </div>
            <div className={waiting.length ? 'is-warn' : ''}>
              <dt>need a human</dt>
              <dd>{runsBase.data ? waiting.length : '–'}</dd>
            </div>
          </dl>
        </div>
      </section>

      <SetupNotices />

      {waiting.length > 0 && (
        <section className="strip" aria-label="Runs waiting for a human">
          {waiting.map((r) => (
            <Link key={r.id} to={`/p/${r.repo}/runs/${r.id}`} className="strip-item strip-item--warn">
              <span className="strip-k">needs human</span>
              <span className="mono">{r.repo}#{r.issueNumber}</span> {r.issueTitle}
              <span className="muted"> · {r.owner.username}</span>
            </Link>
          ))}
        </section>
      )}

      {me.hasGithubToken && (
      <div className="section-head">
        <h2>Projects</h2>
        {(projects.data?.length ?? 0) > 4 && (
          <input className="search" type="search" placeholder="Filter projects" aria-label="Filter projects" value={q} onChange={(e) => setQ(e.target.value)} />
        )}
      </div>
      )}

      {projects.loading && !projects.data && <Loading />}
      {projects.error && <ErrorBox error={projects.error} retry={projects.reload} />}
      {projects.data && projects.data.length === 0 && me.hasGithubToken && (
        <Empty title="No repositories visible">Your GitHub token can’t read any repository in the organization. Ask an owner to grant you access.</Empty>
      )}
      {me.hasGithubToken && projects.data && !projects.data.some((p) => p.private) && (
        <p className="muted small token-hint">
          {projects.data.length ? 'Only public repositories are listed.' : 'No repositories are listed.'} If you expected more, your GitHub token may lack access —{' '}
          <Link to="/settings#github">check it in Settings</Link>.
        </p>
      )}
      <ul className="project-grid">
        {list.map((p) => {
          const live = perRepo.get(p.name);
          return (
            <li key={p.name}>
              <Link to={`/p/${p.name}`} className={`project-card ${live?.agents ? 'is-live' : ''}`}>
                <div className="pc-top">
                  <span className="pc-ident">
                    <ProjectIcon name={p.name} iconUrl={p.iconUrl} size={32} />
                    <span className="pc-name">{p.name}</span>
                  </span>
                  {p.private ? <span className="tag">private</span> : <span className="tag">public</span>}
                </div>
                <p className="pc-desc">{p.description ?? <span className="muted">No description</span>}</p>
                <div className="pc-live">
                  <span className={`pc-meter ${live?.agents ? 'is-on' : ''}`}>
                    <span className="pc-dot" aria-hidden /> {live?.agents ?? 0} agent{live?.agents === 1 ? '' : 's'}
                  </span>
                  <span className={`pc-meter ${live?.runs ? 'is-on' : ''}`}>
                    <span className="pc-bar" aria-hidden /> {live?.runs ?? 0} run{live?.runs === 1 ? '' : 's'}
                  </span>
                </div>
                <div className="pc-meta">
                  {p.language && <span className={`lang lang--${p.language.toLowerCase()}`}>{p.language}</span>}
                  <span>{p.openIssues} open</span>
                  <span>pushed {timeAgo(p.pushedAt)}</span>
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? 'night' : h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening';
}
