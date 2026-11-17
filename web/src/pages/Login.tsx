import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError, type LoginStep, type User } from '../api';
import { CodeInput } from '../components/ui/CodeInput';
import { QrCode } from '../components/ui/QrCode';
import { RecoveryCodes } from '../components/ui/RecoveryCodes';
import { Art, CopyButton, Logo } from '../components/ui/ui';
import { useAsync } from '../lib/async';
import { useAuth } from '../lib/auth';

type Step =
  | { k: 'password' }
  | { k: 'totp'; recovery: boolean }
  | { k: 'enroll'; secret: string; uri: string }
  | { k: 'codes'; codes: string[]; user: User }
  | { k: 'locked'; message: string };

const PROVIDER_LABEL: Record<string, string> = { os: 'machine account', ldap: 'directory (LDAP)', dev: 'dev users' };

/**
 * Second-factor errors (docs/ARCHITECTURE.md §8): 422 `invalid_code` = wrong or replayed code (stay);
 * 423 `locked` (+ lockedUntil); 401 = the pending session expired or is gone, 409 `wrong_login_step` =
 * it is in another step — both mean: start again from the password.
 */
const pendingLost = (e: ApiError) => e.status === 401 || (e.status === 409 && e.code === 'wrong_login_step');

const clock = (iso: string | null) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : null;

export function LoginPage() {
  const { me, setMe } = useAuth();
  const [params] = useSearchParams();
  const next = params.get('next') || '/';
  const nav = useNavigate();
  const providers = useAsync(() => api.providers(), []);
  const [step, setStep] = useState<Step>({ k: 'password' });
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    document.title = 'Sign in · Agern';
  }, []);

  if (me && step.k !== 'codes') return <Navigate to={next} replace />;

  const advance = (s: LoginStep) => {
    setCode('');
    setRecovery('');
    setError(null);
    if (s.next === 'totp') setStep({ k: 'totp', recovery: false });
    else if (s.next === 'enroll') setStep({ k: 'enroll', secret: s.secret, uri: s.otpauthUri });
    else if (s.recoveryCodes?.length) setStep({ k: 'codes', codes: s.recoveryCodes, user: s.user });
    else {
      setMe(s.user);
      nav(next, { replace: true });
    }
  };

  const fail = (e: unknown, restartOn401 = true) => {
    if (e instanceof ApiError) {
      if (e.status === 423) {
        const at = clock(e.lockedUntil);
        setStep({ k: 'locked', message: at ? `Too many wrong codes. Two-factor sign-in is locked until ${at}.` : 'Too many wrong codes. Try again in 15 minutes.' });
        return;
      }
      if (e.status === 429) {
        const at = clock(e.lockedUntil);
        setError(`Too many failed sign-in attempts. Try again${at ? ` after ${at}` : ' later'}.`);
        setCode('');
        return;
      }
      if (e.code === 'invalid_code') {
        setError('That code is not valid. Check your authenticator and try again.');
        setCode('');
        return;
      }
      if (restartOn401 && pendingLost(e)) {
        setStep({ k: 'password' });
        setPassword('');
        setError('Your sign-in expired. Please start again.');
        return;
      }
      setError(e.message);
    } else setError('Could not reach the Agern server.');
    setCode('');
  };

  const run = async (fn: () => Promise<LoginStep>, restartOn401 = true) => {
    setBusy(true);
    setError(null);
    try {
      advance(await fn());
    } catch (e) {
      fail(e, restartOn401);
    } finally {
      setBusy(false);
    }
  };

  const onPassword = (e: FormEvent) => {
    e.preventDefault();
    void run(() => api.login(username.trim(), password), false);
  };

  const restart = () => {
    setStep({ k: 'password' });
    setPassword('');
    setError(null);
  };

  const org = providers.data?.org;

  return (
    <div className="login">
      <Art src="/art/hero.webp" className="login-art">
        <div className="login-art-copy">
          <p className="eyebrow">{org ? `${org} · ` : ''}self-hosted</p>
          <h2>Put Claude agents to work on your issues.</h2>
          <p>Repos are projects, issues are work orders. A crew of agents takes each one from spec to merged pull request — while you watch the floor.</p>
        </div>
      </Art>
      <div className="login-panel">
        <div className="login-card">
          <div className="login-brand">
            <Logo size={40} />
            <div>
              <h1>Agern</h1>
              <p className="muted">the software factory</p>
            </div>
          </div>

          {step.k === 'password' && (
            <form onSubmit={onPassword} className="form" aria-label="Sign in">
              <div className="field">
                <label htmlFor="u">Username</label>
                <input id="u" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
              </div>
              <div className="field">
                <label htmlFor="p">Password</label>
                <input id="p" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              </div>
              {error && <p className="form-error" role="alert">{error}</p>}
              <button className="btn btn--primary btn--block" disabled={busy || !username || !password}>
                {busy ? 'Checking…' : 'Continue'}
              </button>
              <div className="login-meta">
                {providers.data && (
                  <p>
                    Sign in with your{' '}
                    {providers.data.providers.map((p, i) => (
                      <span key={p}>
                        {i > 0 && (i === providers.data!.providers.length - 1 ? ' or ' : ', ')}
                        <span className="chip">{PROVIDER_LABEL[p] ?? p}</span>
                      </span>
                    ))}{' '}
                    password.
                  </p>
                )}
                {org && (
                  <p>
                    Projects come from the GitHub organization <strong className="mono">{org}</strong>.
                  </p>
                )}
                <p className="muted">Two-factor authentication is required for everyone.</p>
              </div>
            </form>
          )}

          {step.k === 'totp' && !step.recovery && (
            <form className="form" onSubmit={(e) => (e.preventDefault(), code.length === 6 && void run(() => api.totp(code)))} aria-label="Two-factor code">
              <h2 className="step-title">Two-factor code</h2>
              <p className="muted">Enter the 6-digit code from your authenticator app for <strong>{username}</strong>.</p>
              <CodeInput value={code} onChange={setCode} onComplete={(v) => void run(() => api.totp(v))} disabled={busy} />
              {error && <p className="form-error" role="alert">{error}</p>}
              <button className="btn btn--primary btn--block" disabled={busy || code.length !== 6}>
                {busy ? 'Verifying…' : 'Verify'}
              </button>
              <div className="form-links">
                <button type="button" className="link" onClick={() => (setError(null), setStep({ k: 'totp', recovery: true }))}>Use a recovery code</button>
                <button type="button" className="link" onClick={restart}>Start over</button>
              </div>
            </form>
          )}

          {step.k === 'totp' && step.recovery && (
            <form className="form" onSubmit={(e) => (e.preventDefault(), void run(() => api.totp(recovery.trim())))} aria-label="Recovery code">
              <h2 className="step-title">Recovery code</h2>
              <p className="muted">Lost your authenticator? Enter one of the ten recovery codes you saved. Each code works once.</p>
              <div className="field">
                <label htmlFor="rc">Recovery code</label>
                <input id="rc" className="mono" autoComplete="one-time-code" placeholder="xxxx-xxxx" value={recovery} onChange={(e) => setRecovery(e.target.value)} autoFocus spellCheck={false} />
              </div>
              {error && <p className="form-error" role="alert">{error}</p>}
              <button className="btn btn--primary btn--block" disabled={busy || recovery.trim().length < 8}>
                {busy ? 'Verifying…' : 'Sign in'}
              </button>
              <div className="form-links">
                <button type="button" className="link" onClick={() => (setError(null), setStep({ k: 'totp', recovery: false }))}>Use authenticator code</button>
                <button type="button" className="link" onClick={restart}>Start over</button>
              </div>
            </form>
          )}

          {step.k === 'enroll' && (
            <form className="form" onSubmit={(e) => (e.preventDefault(), code.length === 6 && void run(() => api.totpEnroll(code)))} aria-label="Set up two-factor authentication">
              <p className="step-kicker">Step 1 of 2 · first sign-in</p>
              <h2 className="step-title">Set up your authenticator</h2>
              <p className="muted">Agern requires two-factor authentication. Scan this code with an authenticator app (1Password, Authy, Google Authenticator, …).</p>
              <div className="enroll">
                <QrCode value={step.uri} size={176} label="QR code for your authenticator app" />
                <div className="enroll-secret">
                  <span className="muted small">Can’t scan? Enter this key:</span>
                  <code className="secret">{step.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
                  <CopyButton text={step.secret} label="Copy key" />
                </div>
              </div>
              <label className="field-label" htmlFor="enroll-code">Then enter the 6-digit code it shows</label>
              <CodeInput value={code} onChange={setCode} onComplete={(v) => void run(() => api.totpEnroll(v))} disabled={busy} autoFocus={false} />
              {error && <p className="form-error" role="alert">{error}</p>}
              <button id="enroll-code" className="btn btn--primary btn--block" disabled={busy || code.length !== 6}>
                {busy ? 'Confirming…' : 'Confirm'}
              </button>
              <div className="form-links">
                <button type="button" className="link" onClick={restart}>Cancel</button>
              </div>
            </form>
          )}

          {step.k === 'codes' && (
            <div className="form" aria-label="Recovery codes">
              <p className="step-kicker">Step 2 of 2</p>
              <h2 className="step-title">Save your recovery codes</h2>
              <p className="muted">If you lose your phone, each of these codes lets you sign in once. <strong>They are shown only now.</strong> Store them somewhere safe — a password manager is ideal.</p>
              <RecoveryCodes codes={step.codes} username={step.user.username} />
              <label className="check">
                <input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} />
                <span>I have saved my recovery codes</span>
              </label>
              <button
                type="button"
                className="btn btn--primary btn--block"
                disabled={!saved}
                onClick={() => {
                  setMe(step.user);
                  nav(next, { replace: true });
                }}
              >
                Enter the factory
              </button>
            </div>
          )}

          {step.k === 'locked' && (
            <div className="form locked" role="alert" aria-label="Two-factor sign-in locked">
              <div className="locked-icon" aria-hidden>
                <svg viewBox="0 0 24 24" width="28" height="28"><rect x="5" y="10.5" width="14" height="10" rx="2" /><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" /></svg>
              </div>
              <h2 className="step-title">Sign-in locked</h2>
              <p>{step.message}</p>
              <p className="muted">Five wrong codes in a row lock two-factor sign-in for 15 minutes. If you have lost your authenticator and your recovery codes, ask an Agern admin to reset your two-factor setup.</p>
              <button type="button" className="btn btn--block" onClick={restart}>Back to sign in</button>
            </div>
          )}
        </div>
        <p className="login-foot muted small">Agern · Egernet</p>
      </div>
    </div>
  );
}
