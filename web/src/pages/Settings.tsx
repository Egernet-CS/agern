import { useEffect, useState, type FormEvent } from 'react';
import { useLocation } from 'react-router-dom';
import { api, type CredentialKind, type PreferenceOverrides, type Preferences, type StageRole } from '../api';
import { CodeInput } from '../components/ui/CodeInput';
import { RecoveryCodes } from '../components/ui/RecoveryCodes';
import { UsageBlock } from '../components/ui/UsageBlock';
import { CopyButton, Dialog, ErrorBox, Notice } from '../components/ui/ui';
import { useAsync } from '../lib/async';
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
      <ImageSection />
      <ModelsSection />
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

/** The image-generation provider: one per user, its key validated by the provider before it is stored. */
function ImageSection() {
  const { me, setMe } = useAuth();
  const cfg = useAsync(() => api.config(), []);
  const [chosen, setChosen] = useState<string | null>(null);
  const [model, setModel] = useState<string | null>(null);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  if (!me) return null;
  const catalogue = cfg.data?.images;
  const providers = catalogue?.providers ?? [];
  const saved = providers.find((p) => p.id === me.imageProvider);
  const current = providers.find((p) => p.id === chosen) ?? saved ?? providers[0];
  // The model field follows the chosen provider: its saved model for the saved provider, else its default.
  const modelValue = model ?? (current && current.id === me.imageProvider && me.imageModel ? me.imageModel : current?.defaultModel ?? '');
  const choose = (id: string) => {
    setChosen(id);
    setModel(null);
    setErr(null);
    setMsg(null);
  };
  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!current) return;
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      setMe(await api.putImageProvider(current.id, modelValue.trim(), key.trim()));
      setKey('');
      setModel(null);
      setMsg('Saved. New image requests will use it.');
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      setMe(await api.deleteImageProvider());
      setMsg('Removed.');
    } catch (e2) {
      setErr((e2 as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const isSet = !!me.imageProvider;
  return (
    <section className="card settings-card" id="images" aria-labelledby="images-h">
      <header className="sc-head">
        <h2 id="images-h">Image generation</h2>
        <Status on={isSet}>{isSet ? `${saved?.label ?? me.imageProvider} · ${me.imageModel}` : 'not set — agents cannot generate images'}</Status>
      </header>
      <p>Agents can have icons, illustrations and other graphics made by an image AI. It runs on your own API key and bills to you; the key is used only for chats and runs you own.</p>
      {cfg.loading && !catalogue && <p className="muted small">Loading…</p>}
      {cfg.error && <ErrorBox error={cfg.error} retry={cfg.reload} />}
      {current && catalogue && (
        <form className="form" onSubmit={save}>
          <fieldset className="seg seg--wide">
            <legend className="sr-only">Image provider</legend>
            {providers.map((p) => (
              <label key={p.id} className={current.id === p.id ? 'is-on' : ''}>
                <input type="radio" name="image-provider" className="sr-only" checked={current.id === p.id} onChange={() => choose(p.id)} />
                {p.label}
              </label>
            ))}
          </fieldset>
          <div className="field">
            <label htmlFor="ik">API key</label>
            <input id="ik" type="password" className="mono" autoComplete="off" spellCheck={false} placeholder={current.keyPlaceholder} value={key} onChange={(e) => setKey(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="im">Model</label>
            <input id="im" className="mono" autoComplete="off" spellCheck={false} list="image-models" value={modelValue} onChange={(e) => setModel(e.target.value)} />
            <datalist id="image-models">
              {current.models.map((m) => <option key={m} value={m} />)}
            </datalist>
          </div>
          {err && <p className="form-error" role="alert">{err}</p>}
          {msg && <p className="form-ok" role="status">{msg}</p>}
          <div className="row">
            <button className="btn btn--primary" disabled={busy || !key.trim() || !modelValue.trim()}>
              {busy ? `Checking with ${current.label}…` : isSet ? 'Replace' : 'Save'}
            </button>
            {isSet && <button type="button" className="btn btn--ghost btn--danger" onClick={() => void remove()} disabled={busy}>Remove</button>}
          </div>
          <p className="muted small">
            Limits: up to {catalogue.limits.maxPerRequest} images per request, {catalogue.limits.maxPerTurn} per chat message and {catalogue.limits.maxPerStage} per run stage.
          </p>
        </form>
      )}
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

// ---------------------------------------------------------------- models & cost

const ROLES: StageRole[] = ['spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'approver'];
const EMPTY: PreferenceOverrides = { chatModel: null, pipelineModels: {}, maxFixRounds: null };

/** One-click choices. Economy: cheaper models where depth matters least (chat, reviews), one fix round. */
function presets(max: number): { name: string; help: string; value: PreferenceOverrides }[] {
  return [
    { name: 'Default', help: 'Use the factory defaults for everything', value: EMPTY },
    {
      name: 'Economy',
      help: 'Sonnet for chat and spec, Haiku for the four reviewers, at most one fix round',
      value: {
        chatModel: 'sonnet',
        pipelineModels: { 'spec-writer': 'sonnet', security: 'haiku', ux: 'haiku', 'ui-design': 'haiku', code: 'haiku' },
        maxFixRounds: Math.min(1, max),
      },
    },
  ];
}

const same = (a: PreferenceOverrides, b: PreferenceOverrides) =>
  (a.chatModel ?? null) === (b.chatModel ?? null) &&
  (a.maxFixRounds ?? null) === (b.maxFixRounds ?? null) &&
  ROLES.every((r) => (a.pipelineModels[r] ?? null) === (b.pipelineModels[r] ?? null));

function ModelsSection() {
  const { me } = useAuth();
  const usage = me?.hasClaudeCredential ? <UsageBlock /> : null;
  const [prefs, setPrefs] = useState<Preferences | null>(null);
  const [draft, setDraft] = useState<PreferenceOverrides>(EMPTY);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => {
    api.preferences().then(
      (p) => {
        setPrefs(p);
        setDraft(p.overrides);
      },
      (e: Error) => setErr(e.message),
    );
  }, []);
  if (!prefs) {
    return (
      <section className="card settings-card" id="models" aria-labelledby="models-h">
        <header className="sc-head"><h2 id="models-h">Models &amp; cost</h2></header>
        {err ? <p className="form-error" role="alert">{err}</p> : <p className="muted">Loading…</p>}
        {usage}
      </section>
    );
  }
  const max = prefs.effective.maxFixRounds.max;
  const dirty = !same(draft, prefs.overrides);
  const save = async (value: PreferenceOverrides, label?: string) => {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      const p = await api.putPreferences(value);
      setPrefs(p);
      setDraft(p.overrides);
      setMsg(label ? `${label} applied. New chats and runs you start use it.` : 'Saved. New chats and runs you start use it.');
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const defaultOf = (role: StageRole | 'chat') => {
    // What the factory default is, i.e. what applies once this row is reset.
    const e = role === 'chat' ? prefs.effective.chatModel : prefs.effective.pipelineModels[role];
    return e.source === 'default' ? e.value : null;
  };
  const row = (role: StageRole | 'chat') => {
    const value = role === 'chat' ? draft.chatModel : draft.pipelineModels[role] ?? null;
    const set = (v: string | null) =>
      setDraft((d) => (role === 'chat' ? { ...d, chatModel: v } : { ...d, pipelineModels: { ...d.pipelineModels, [role]: v ?? undefined } }));
    const def = defaultOf(role);
    return (
      <div key={role} className="pref-row">
        <label htmlFor={`pref-${role}`}>{role}</label>
        <input
          id={`pref-${role}`}
          className="mono"
          list="model-suggestions"
          value={value ?? ''}
          placeholder={def ? `default (${def})` : 'default'}
          spellCheck={false}
          autoComplete="off"
          onChange={(e) => set(e.target.value.trim() ? e.target.value : null)}
        />
        <span className={`pref-src ${value ? 'pref-src--user' : ''}`}>{value ? 'your choice' : 'default'}</span>
        <button type="button" className="btn btn--ghost btn--sm" disabled={!value} onClick={() => set(null)} aria-label={`Reset ${role} to the default`}>
          reset
        </button>
      </div>
    );
  };
  const rounds = prefs.effective.maxFixRounds;
  return (
    <section className="card settings-card" id="models" aria-labelledby="models-h">
      <header className="sc-head">
        <h2 id="models-h">Models &amp; cost</h2>
        <span className="muted small">for agents you start</span>
      </header>
      <p>
        Every chat and pipeline run you start runs on <strong>your</strong> Claude account and is billed to it. Choose cheaper models here — an alias
        ({prefs.suggestions.join(', ')}) or a full model id. Empty means the factory default. A run keeps the models and fix rounds it started with.
      </p>
      {usage}
      <div className="row pref-presets" role="group" aria-label="Presets">
        {presets(max).map((p) => (
          <button key={p.name} type="button" className="btn btn--sm" title={p.help} disabled={busy} onClick={() => void save(p.value, p.name)}>
            {p.name}
          </button>
        ))}
        <span className="muted small">Economy: Sonnet for chat and spec, Haiku reviewers, one fix round.</span>
      </div>
      <datalist id="model-suggestions">
        {prefs.suggestions.map((m) => <option key={m} value={m} />)}
      </datalist>
      <div className="pref-grid">
        {row('chat')}
        {ROLES.map(row)}
        <div className="pref-row">
          <label htmlFor="pref-rounds">fix rounds</label>
          <select
            id="pref-rounds"
            value={draft.maxFixRounds == null ? '' : String(draft.maxFixRounds)}
            onChange={(e) => setDraft((d) => ({ ...d, maxFixRounds: e.target.value === '' ? null : Number(e.target.value) }))}
          >
            <option value="">default ({max})</option>
            {Array.from({ length: max + 1 }, (_, n) => (
              <option key={n} value={n}>{n === 0 ? '0 — no fix rounds' : n}</option>
            ))}
            {draft.maxFixRounds != null && draft.maxFixRounds > max && <option value={draft.maxFixRounds}>{draft.maxFixRounds} (above the maximum)</option>}
          </select>
          <span className={`pref-src ${draft.maxFixRounds != null ? 'pref-src--user' : ''}`}>{draft.maxFixRounds != null ? 'your choice' : 'default'}</span>
          <button type="button" className="btn btn--ghost btn--sm" disabled={draft.maxFixRounds == null} onClick={() => setDraft((d) => ({ ...d, maxFixRounds: null }))} aria-label="Reset fix rounds to the default">
            reset
          </button>
        </div>
      </div>
      <p className="muted small">
        Fix rounds: how often the builder may answer reviewer changes before a human decides (0–{max}; the factory allows at most {max}). 0 sends any requested change straight to you.
      </p>
      {rounds.clamped && (
        <Notice tone="info" title="Fix rounds capped">
          You chose {prefs.overrides.maxFixRounds}, but the factory now allows at most {max}; your runs use {rounds.value}.
        </Notice>
      )}
      {err && <p className="form-error" role="alert">{err}</p>}
      {msg && !dirty && <p className="form-ok" role="status">{msg}</p>}
      <div className="row">
        <button className="btn btn--primary" disabled={!dirty || busy} onClick={() => void save(draft)}>Save models</button>
        {dirty && <button className="btn btn--ghost" onClick={() => setDraft(prefs.overrides)}>Revert</button>}
      </div>
    </section>
  );
}
