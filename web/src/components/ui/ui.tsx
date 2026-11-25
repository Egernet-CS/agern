import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { IssueKind, ReleaseIssueState, ReleaseStatus, RunStatus, SessionKind, SessionStatus } from '../../api/types';

/** Decorative artwork from /art that degrades to a gradient (never a broken-image icon). */
export function Art({ src, className = '', children }: { src: string; className?: string; children?: ReactNode }) {
  const [state, setState] = useState<'loading' | 'ok' | 'missing'>('loading');
  return (
    <div className={`art art--${state} ${className}`}>
      {state !== 'missing' && (
        <img src={src} alt="" aria-hidden onLoad={() => setState('ok')} onError={() => setState('missing')} decoding="async" />
      )}
      {children}
    </div>
  );
}

/** Fallback acorn mark, used until/unless /art/logo.svg loads. */
function AcornMark() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className="mark-fallback">
      <path d="M16 3.5c.6 0 1 .4 1 1V7" className="mark-stem" />
      <path d="M5.5 13.2C5.5 9.4 10.2 6.8 16 6.8s10.5 2.6 10.5 6.4c0 .9-.7 1.6-1.6 1.6H7.1c-.9 0-1.6-.7-1.6-1.6z" className="mark-cap" />
      <path d="M7.4 16.2h17.2c0 7-3.9 11.9-8.6 12.6-4.7-.7-8.6-5.6-8.6-12.6z" className="mark-nut" />
      <path d="M10 11h12" className="mark-line" />
    </svg>
  );
}

export function Logo({ size = 28 }: { size?: number }) {
  const [ok, setOk] = useState(true);
  return (
    <span className="mark" style={{ width: size, height: size }}>
      {ok ? <img src="/art/logo.svg" alt="" aria-hidden width={size} height={size} onError={() => setOk(false)} /> : <AcornMark />}
    </span>
  );
}

export function Wordmark({ size = 28, to = '/' }: { size?: number; to?: string }) {
  return (
    <Link to={to} className="wordmark" aria-label="Agern — software factory, home">
      <Logo size={size} />
      <span className="wordmark-text">
        <span className="wordmark-name">Agern</span>
        <span className="wordmark-tag">software factory</span>
      </span>
    </Link>
  );
}

const RUN_TONE: Record<RunStatus, string> = {
  queued: 'idle',
  spec: 'active',
  building: 'active',
  reviewing: 'active',
  approving: 'active',
  merged: 'pass',
  needs_human: 'escalate',
  failed: 'fail',
  cancelled: 'idle',
};
export function RunStatusPill({ status }: { status: RunStatus }) {
  return (
    <span className={`pill pill--${RUN_TONE[status]}`}>
      <span className="pill-dot" aria-hidden />
      {status.replace('_', ' ')}
    </span>
  );
}

const SESSION_TONE: Record<SessionStatus, string> = { starting: 'active', running: 'active', idle: 'idle', done: 'pass', failed: 'fail', stopped: 'idle' };
export function SessionStatusPill({ status }: { status: SessionStatus }) {
  return (
    <span className={`pill pill--${SESSION_TONE[status]}`}>
      <span className="pill-dot" aria-hidden />
      {status}
    </span>
  );
}

const RELEASE_LABEL: Record<ReleaseStatus, string> = {
  planning: 'in preparation',
  scheduled: 'scheduled',
  building: 'building',
  paused: 'paused',
  ready: 'ready to release',
  released: 'released',
  cancelled: 'cancelled',
};
const RELEASE_TONE: Record<ReleaseStatus, string> = {
  planning: 'idle',
  scheduled: 'active',
  building: 'active',
  paused: 'escalate',
  // As a run waiting for a person, and a merged run.
  ready: 'escalate',
  released: 'pass',
  cancelled: 'idle',
};
/** A state this version does not know yet (from a newer server) shows as its raw text. */
export function ReleaseStatusPill({ status }: { status: ReleaseStatus | (string & {}) }) {
  const known = Object.prototype.hasOwnProperty.call(RELEASE_LABEL, status);
  return (
    <span className={`pill pill--${known ? RELEASE_TONE[status as ReleaseStatus] : 'idle'}`}>
      <span className="pill-dot" aria-hidden />
      {known ? RELEASE_LABEL[status as ReleaseStatus] : status}
    </span>
  );
}

const ISSUE_STATE_TONE: Record<ReleaseIssueState, string> = { waiting: 'idle', running: 'active', merged: 'pass', held: 'escalate', skipped: 'idle' };
/** An issue's state within a release; the text always carries the state (not colour alone). */
export function IssueStateBadge({ state }: { state: ReleaseIssueState }) {
  return (
    <span className={`pill pill--${ISSUE_STATE_TONE[state]} issue-state issue-state--${state}`}>
      <span className="pill-dot" aria-hidden />
      {state}
    </span>
  );
}

export function KindBadge({ kind }: { kind: IssueKind }) {
  return <span className={`kind kind--${kind}`}>{kind}</span>;
}

export function RoleBadge({ kind }: { kind: SessionKind }) {
  return <span className={`role role--${kind}`}>{kind}</span>;
}

export function Verdict({ v }: { v: string | null }) {
  if (!v) return null;
  const tone = v === 'PASS' || v === 'APPROVE' ? 'pass' : v === 'CHANGES' ? 'changes' : 'escalate';
  return <span className={`verdict verdict--${tone}`}>{v}</span>;
}

export function Empty({ title, children, art = true }: { title: string; children?: ReactNode; art?: boolean }) {
  return (
    <div className="empty">
      {art && <Art src="/art/empty.webp" className="empty-art" />}
      <h3>{title}</h3>
      {children && <div className="empty-body">{children}</div>}
    </div>
  );
}

export function Notice({ tone = 'info', title, children, action }: { tone?: 'info' | 'warn' | 'error'; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className={`notice notice--${tone}`} role={tone === 'error' ? 'alert' : 'note'}>
      <div className="notice-text">
        <strong>{title}</strong>
        {children && <div>{children}</div>}
      </div>
      {action && <div className="notice-action">{action}</div>}
    </div>
  );
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <span className="loading-bar" aria-hidden />
      <span className="sr-only">{label}…</span>
    </div>
  );
}

export function ErrorBox({ error, retry }: { error: Error; retry?: () => void }) {
  return (
    <Notice tone="error" title="Something went wrong" action={retry && <button className="btn btn--ghost" onClick={retry}>Retry</button>}>
      {error.message}
    </Notice>
  );
}

/** Native <dialog> with focus management and Esc to close. */
export function Dialog({ open, onClose, title, children, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      d.showModal();
      // showModal focuses the first focusable element (✕); a child marked data-autofocus gets the focus instead.
      d.querySelector<HTMLElement>('[data-autofocus]')?.focus();
    }
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} className={`dialog ${wide ? 'dialog--wide' : ''}`} onClose={onClose} onCancel={onClose} aria-label={title}>
      {open && (
        <div className="dialog-inner">
          <header className="dialog-head">
            <h2>{title}</h2>
            <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">✕</button>
          </header>
          {children}
        </div>
      )}
    </dialog>
  );
}

export function CopyButton({ text, label = 'Copy', disabled }: { text: string; label?: string; disabled?: boolean }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="btn btn--ghost btn--sm"
      disabled={disabled}
      onClick={async () => {
        if (await copyText(text)) {
          setDone(true);
          setTimeout(() => setDone(false), 1500);
        }
      }}
    >
      {done ? 'Copied' : label}
    </button>
  );
}

/** Clipboard API needs a secure context; Agern often runs over plain HTTP on a LAN. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through */
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  ta.remove();
  return ok;
}

/**
 * A project's icon as a rounded square tile, or its first letter on a coloured tile when it has none (or the image
 * fails to load). Decorative: the project's name is always next to it.
 */
export function ProjectIcon({ name, iconUrl, size }: { name: string; iconUrl: string | null | undefined; size: number }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [iconUrl]);
  const letter = (Array.from(name)[0] ?? '?').toUpperCase();
  return (
    <span className={`project-icon ${iconUrl && !failed ? 'has-image' : ''}`} style={{ width: size, height: size }} aria-hidden>
      {iconUrl && !failed ? <img src={iconUrl} alt="" onError={() => setFailed(true)} decoding="async" /> : letter}
    </span>
  );
}
