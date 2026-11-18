import { useEffect, useState } from 'react';
import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { api, isMock, isSessionActive } from './api';
import { Logo, Wordmark } from './components/ui/ui';
import { useAsync } from './lib/async';
import { AuthProvider, useAuth } from './lib/auth';
import { FeedProvider, useFeed, useLiveSessions } from './lib/realtime';
import { AdminPage } from './pages/Admin';
import { AgentsPage } from './pages/Agents';
import { LoginPage } from './pages/Login';
import { ProjectPage } from './pages/Project';
import { ProjectsPage } from './pages/Projects';
import { RunPage } from './pages/Run';
import { SettingsPage } from './pages/Settings';

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<Gate />}>
            <Route element={<Shell />}>
              <Route index element={<ProjectsPage />} />
              <Route path="/p/:repo" element={<ProjectPage />} />
              <Route path="/p/:repo/runs/:runId" element={<RunPage />} />
              <Route path="/agents" element={<AgentsPage />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/admin" element={<AdminPage />} />
              <Route path="*" element={<NotFound />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}

function Gate() {
  const { me, loading, error, refresh } = useAuth();
  const loc = useLocation();
  if (loading)
    return (
      <div className="splash" role="status">
        <Logo size={44} />
        <span className="sr-only">Loading Agern…</span>
      </div>
    );
  if (!me && error)
    return (
      <div className="splash splash--error">
        <Logo size={44} />
        <p>The Agern server is not answering.</p>
        <p className="muted">{error.message}</p>
        <button className="btn" onClick={() => void refresh()}>Try again</button>
      </div>
    );
  if (!me) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  return (
    <FeedProvider enabled>
      <Outlet />
    </FeedProvider>
  );
}

function LiveCount() {
  const feed = useFeed();
  const base = useAsync(() => api.agents(true), []);
  const sessions = useLiveSessions(base.data, () => true);
  const n = sessions.filter(isSessionActive).length;
  return (
    <NavLink to="/agents" className={`live-count ${n ? 'is-on' : ''}`} title={`${n} agents active · feed ${feed.conn}`}>
      <span className="live-dot" aria-hidden />
      <span>{n}</span>
      <span className="live-count-label"> active</span>
      {feed.conn !== 'live' && <span className="sr-only"> (feed {feed.conn})</span>}
    </NavLink>
  );
}

/** Pages laid out as an app shell: exactly the viewport tall, only their panes scroll (the project page). */
const APP_SHELL = /^\/p\/[^/]+\/?$/;

/**
 * While an app-shell page is shown the document itself never scrolls, and the shell's height follows the
 * visual viewport, so the prompt stays above an on-screen keyboard (iOS does not resize the layout viewport;
 * Android does with `interactive-widget=resizes-content`, where `100dvh` alone would do).
 */
function useAppShell(on: boolean) {
  useEffect(() => {
    if (!on) return;
    const root = document.documentElement;
    root.classList.add('app-locked');
    const vv = window.visualViewport;
    const sync = () => {
      if (!vv) return;
      // Pinch zoom also shrinks the visual viewport; only follow it at scale 1 (i.e. the keyboard).
      if (Math.abs(vv.scale - 1) < 0.01) root.style.setProperty('--app-h', `${Math.round(vv.height)}px`);
      if (window.scrollY !== 0) window.scrollTo(0, 0);
    };
    sync();
    vv?.addEventListener('resize', sync);
    vv?.addEventListener('scroll', sync);
    return () => {
      root.classList.remove('app-locked');
      root.style.removeProperty('--app-h');
      vv?.removeEventListener('resize', sync);
      vv?.removeEventListener('scroll', sync);
    };
  }, [on]);
}

function Shell() {
  const { me, logout } = useAuth();
  const [menu, setMenu] = useState(false);
  const loc = useLocation();
  useEffect(() => setMenu(false), [loc.pathname]);
  const appShell = APP_SHELL.test(loc.pathname);
  useAppShell(appShell);
  if (!me) return null;
  return (
    <div className={`shell ${appShell ? 'shell--app' : ''}`}>
      <a href="#main" className="skip">Skip to content</a>
      <header className="topbar">
        <Wordmark />
        <nav className="topnav" aria-label="Main">
          <NavLink to="/" end>Projects</NavLink>
          <NavLink to="/agents">Agents</NavLink>
          <NavLink to="/settings">Settings</NavLink>
          {me.isAdmin && <NavLink to="/admin">Admin</NavLink>}
        </nav>
        <div className="topbar-right">
          {isMock && <span className="mock-flag" title="VITE_MOCK=1 — data is simulated in the browser">mock</span>}
          <LiveCount />
          <div className="usermenu">
            <button type="button" className="avatar-btn" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)}>
              <span className="avatar" aria-hidden>{(me.displayName || me.username).slice(0, 1).toUpperCase()}</span>
              <span className="avatar-name">{me.username}</span>
            </button>
            {menu && (
              <div className="usermenu-pop" role="menu">
                <div className="usermenu-who">
                  <strong>{me.displayName}</strong>
                  <span className="muted">{me.githubLogin ? `@${me.githubLogin}` : 'no GitHub token'}</span>
                </div>
                <NavLink role="menuitem" to="/settings">Credentials & security</NavLink>
                <button role="menuitem" type="button" onClick={() => void logout()}>Sign out</button>
              </div>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="main">
        <Outlet />
      </main>
    </div>
  );
}

function NotFound() {
  return (
    <div className="page page--narrow">
      <h1 className="page-title">Nothing here</h1>
      <p className="muted">That page doesn’t exist. Maybe a squirrel buried it.</p>
      <NavLink to="/" className="btn">Back to projects</NavLink>
    </div>
  );
}
