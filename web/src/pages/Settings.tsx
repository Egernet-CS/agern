import { useEffect, useState, type FormEvent } from 'react';
import { useLocation } from 'react-router-dom';
import { api, type CredentialKind } from '../api';
import { CodeInput } from '../components/ui/CodeInput';
import { RecoveryCodes } from '../components/ui/RecoveryCodes';
import { CopyButton, Dialog, Notice } from '../components/ui/ui';
import { useAuth, useMe } from '../lib/auth';

export function SettingsPage() {
  const me = useMe();
  const loc = useLocation();
  useEffect(() => {
    document.title = 'Settings · Agern';
  }, []);
  useEffect(() => {
    if (loc.hash) document.getElementById(loc.hash.slice(1))?.scrollIntoView({ block: 'start' });
  }, [loc.hash]);

  return (
    <div className="page page--narrow">
      <h1 className="page-title">Your credentials</h1>
      <div className="privacy">
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden><path d="M12 3l7 3v5c0 4.5-3 8.4-7 10-4-1.6-7-5.5-7-10V6l7-3z" /></svg>
        <p>
          <strong>These are yours alone.</strong> Agern encrypts them at rest and never shows them again — not to you, not to admins. Agents you start run on your credentials; nobody else can ever start, drive or resume an agent with them. Other people can only <em>observe</em> your agents.
        </p>
      </div>
      <ClaudeSection />
      <GithubSection />
      <SecuritySection />
      <p className="muted small pad-top">Signed in as <strong>{me.username}</strong>{me.isAdmin ? ' · admin' : ''}.</p>
    </div>
  );
}

function Status({ on, children }: { on: boolean; children: React.ReactNode }) {
  return <span className={`status ${on ? 'status--on' : 'status--off'}`}><span aria-hidden>{on ? '●' : '○'}</span> {children}</span>;
}

function ClaudeSection() {
  const { me, setMe } = useAuth();
  const [kind, setKind] = useState<CredentialKind>('oauth_token');
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  if (!me) return null;
  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      setMe(await api.putClaudeCredential(kind, value.trim()));
      setValue('');
      setMsg('Saved. New agents will use it.');
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    setBusy(true);
    try {
      setMe(await api.deleteClaudeCredential());
      setMsg('Removed.');
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card settings-card" id="claude" aria-labelledby="claude-h">
      <header className="sc-head">
        <h2 id="claude-h">Claude</h2>
        <Status on={me.hasClaudeCredential}>
          {me.hasClaudeCredential ? (me.claudeCredentialKind === 'api_key' ? 'API key registered' : 'OAuth token registered') : 'not set — chat and runs are disabled'}
        </Status>
      </header>
      <p>Agents are the <code>claude</code> CLI running headless. They need a credential that bills to <em>you</em>.</p>
      <ol className="steps">
        <li>On your own machine, with Claude Code installed and logged in, run:
          <div className="cmd"><code>claude setup-token</code><CopyButton text="claude setup-token" /></div>
        </li>
        <li>It opens a browser, then prints a long-lived token starting with <code>sk-ant-oat01-</code>.</li>
        <li>Paste it below. Prefer this for a Claude subscription; use an Anthropic API key for pay-as-you-go.</li>
      </ol>
      <form className="form" onSubmit={save}>
        <fieldset className="seg seg--wide">
          <legend className="sr-only">Credential kind</legend>
          {([['oauth_token', 'OAuth token (setup-token)'], ['api_key', 'Anthropic API key']] as const).map(([k, l]) => (
            <label key={k} className={kind === k ? 'is-on' : ''}>
              <input type="radio" name="ck" className="sr-only" checked={kind === k} onChange={() => setKind(k)} />
              {l}
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor="cv">{kind === 'oauth_token' ? 'Token' : 'API key'}</label>
          <input id="cv" type="password" className="mono" autoComplete="off" spellCheck={false} placeholder={kind === 'oauth_token' ? 'sk-ant-oat01-…' : 'sk-ant-api03-…'} value={value} onChange={(e) => setValue(e.target.value)} />
        </div>
        {err && <p className="form-error" role="alert">{err}</p>}
        {msg && <p className="form-ok" role="status">{msg}</p>}
        <div className="row">
          <button className="btn btn--primary" disabled={busy || !value.trim()}>{me.hasClaudeCredential ? 'Replace' : 'Save'}</button>
          {me.hasClaudeCredential && <button type="button" className="btn btn--ghost btn--danger" onClick={() => void remove()} disabled={busy}>Remove</button>}
        </div>
      </form>
    </section>
  );
}

function GithubSection() {
  const { me, setMe } = useAuth();
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  if (!me) return null;
  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      setMe(await api.putGithubToken(token.trim()));
      setToken('');
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card settings-card" id="github" aria-labelledby="gh-h">
      <header className="sc-head">
        <h2 id="gh-h">GitHub</h2>
        <Status on={me.hasGithubToken}>{me.hasGithubToken ? `connected as @${me.githubLogin}` : 'not set — you see no projects'}</Status>
      </header>
      <p>A personal access token decides which repositories you see and is what your agents push and open pull requests with. GitHub enforces the access — Agern never widens it.</p>
      {me.hasGithubToken && <TokenInfo kind={me.githubTokenKind} scopes={me.githubTokenScopes} />}
      <ul className="bullets small">
        <li>Fine-grained token for the organization with <strong>Contents</strong>, <strong>Issues</strong> and <strong>Pull requests</strong>: read &amp; write, plus <strong>Metadata</strong>: read.</li>
        <li>Or a classic token with the <code>repo</code> scope.</li>
      </ul>
      <form className="form" onSubmit={save}>
        <div className="field">
          <label htmlFor="gt">Personal access token</label>
          <input id="gt" type="password" className="mono" autoComplete="off" spellCheck={false} placeholder="github_pat_…" value={token} onChange={(e) => setToken(e.target.value)} />
        </div>
        {err && <p className="form-error" role="alert">{err}</p>}
        <div className="row">
          <button className="btn btn--primary" disabled={busy || !token.trim()}>{busy ? 'Checking with GitHub…' : me.hasGithubToken ? 'Replace' : 'Save'}</button>
          {me.hasGithubToken && (
            <button type="button" className="btn btn--ghost btn--danger" disabled={busy} onClick={async () => setMe(await api.deleteGithubToken())}>Remove</button>
          )}
        </div>
      </form>
    </section>
  );
}

/** What the registered token can do, with a warning when it cannot see private repositories or push. */
function TokenInfo({ kind, scopes }: { kind: 'classic' | 'fine_grained' | null; scopes: string[] | null }) {
  const missingRepo = kind === 'classic' && scopes !== null && !scopes.includes('repo');
  return (
    <div className="token-info">
      <p className="small">
        {kind === 'fine_grained' ? 'Fine-grained token' : kind === 'classic' ? 'Classic token' : 'Token'}
        {scopes && (
          <>
            {' '}with scopes{' '}
            {scopes.length ? scopes.map((sc) => <code key={sc} className="scope">{sc}</code>) : <em>none</em>}
          </>
        )}
      </p>
      {missingRepo && (
        <Notice tone="warn" title="This token can only see public repositories and cannot push">
          Create a classic token with the <code>repo</code> and <code>read:org</code> scopes and replace this one.
        </Notice>
      )}
      {kind === 'fine_grained' && (
        <p className="muted small">
          A fine-grained token sees only what it was granted: it must have access to the organization’s repositories with read &amp; write on <strong>Contents</strong>, <strong>Issues</strong> and <strong>Pull requests</strong> (plus Metadata: read).
        </p>
      )}
    </div>
  );
}

function SecuritySection() {
  const me = useMe();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const close = () => {
    setOpen(false);
    setCode('');
    setCodes(null);
    setErr(null);
  };
  const regenerate = async (c: string) => {
    setBusy(true);
    setErr(null);
    try {
      setCodes((await api.regenerateRecoveryCodes(c)).recoveryCodes);
    } catch (e) {
      setErr((e as Error).message);
      setCode('');
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card settings-card" id="security" aria-labelledby="sec-h">
      <header className="sc-head">
        <h2 id="sec-h">Sign-in security</h2>
        <Status on={me.twoFactorEnabled}>{me.twoFactorEnabled ? 'two-factor authentication on' : 'two-factor not set up'}</Status>
      </header>
      <p>Every sign-in asks for a code from your authenticator app. Two-factor authentication is required for everyone and can’t be turned off; if you lose your device, an admin can reset it so you enrol again.</p>
      <div className="row">
        <button className="btn" onClick={() => setOpen(true)}>Regenerate recovery codes</button>
        <span className="muted small">Your old codes stop working immediately.</span>
      </div>
      <Dialog open={open} onClose={close} title={codes ? 'Your new recovery codes' : 'Regenerate recovery codes'}>
        {!codes ? (
          <form className="form" onSubmit={(e) => (e.preventDefault(), code.length === 6 && void regenerate(code))}>
            <p className="muted">Enter a current code from your authenticator to confirm.</p>
            <CodeInput value={code} onChange={setCode} onComplete={(v) => void regenerate(v)} disabled={busy} />
            {err && <p className="form-error" role="alert">{err}</p>}
            <div className="dialog-actions">
              <button type="button" className="btn btn--ghost" onClick={close}>Cancel</button>
              <button className="btn btn--primary" disabled={busy || code.length !== 6}>{busy ? 'Generating…' : 'Generate new codes'}</button>
            </div>
          </form>
        ) : (
          <div className="form">
            <p className="muted"><strong>Shown only once.</strong> Save them in a password manager; each works a single time.</p>
            <RecoveryCodes codes={codes} username={me.username} />
            <div className="dialog-actions">
              <button className="btn btn--primary" onClick={close}>I have saved them</button>
            </div>
          </div>
        )}
      </Dialog>
    </section>
  );
}
