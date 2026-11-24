import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { api, ApiError, PROJECT_ICON_MAX_BYTES, PROJECT_ICON_TYPES, type Project } from '../../api';
import { Dialog, ProjectIcon } from './ui';

const errorMessage = (e: unknown) => (e instanceof ApiError || e instanceof Error ? e.message : 'The icon could not be changed');

/**
 * The project header's icon for admins: a button opening a small menu to upload, replace or remove the icon, or to
 * take it from a file in the repository (ARCHITECTURE §8 "Project icons"). The server checks everything again; only
 * the size is checked here up front. The icon itself is only ever shown through `<img>`.
 */
export function ProjectIconControl({
  repo,
  iconUrl,
  size,
  defaultBranch,
  onChanged,
  onError,
}: {
  repo: string;
  iconUrl: string | null;
  size: number;
  /** The branch a file from the repository is read from. */
  defaultBranch: string;
  onChanged: (p: Project) => void;
  /** A message to show, or null to clear the previous one. */
  onError: (message: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const [fromRepository, setFromRepository] = useState(false);
  const [busy, setBusy] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const refocus = useRef(false);

  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const outside = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) button.current?.focus();
  };

  const onMenuKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const at = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'Escape') {
      e.preventDefault();
      close(true);
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const next = e.key === 'ArrowDown' ? (at + 1) % items.length : (at - 1 + items.length) % items.length;
      items[next]?.focus();
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault();
      items[e.key === 'Home' ? 0 : items.length - 1]?.focus();
    }
  };

  const run = async (action: () => Promise<Project>) => {
    onError(null);
    setBusy(true);
    try {
      onChanged(await action());
    } catch (e) {
      onError(errorMessage(e));
    } finally {
      setBusy(false);
      refocus.current = true;
    }
  };

  // The button is disabled while busy, so focus can only go back to it once it is enabled again.
  useEffect(() => {
    if (!busy && refocus.current) {
      refocus.current = false;
      button.current?.focus();
    }
  }, [busy]);

  // Closing the repository dialog (any way) puts focus back on the icon button; the dialog's own close runs first.
  const dialogWasOpen = useRef(false);
  useEffect(() => {
    if (fromRepository) dialogWasOpen.current = true;
    else if (dialogWasOpen.current) {
      dialogWasOpen.current = false;
      button.current?.focus();
    }
  }, [fromRepository]);

  const picked = (f: File | undefined) => {
    if (file.current) file.current.value = '';
    if (!f) return;
    if (f.size > PROJECT_ICON_MAX_BYTES) {
      onError('The icon is larger than 1.0 MB');
      return;
    }
    void run(() => api.setProjectIcon(repo, f));
  };

  return (
    <div
      className="ph-icon-control"
      ref={wrap}
      onBlur={(e) => {
        if (open && !wrap.current?.contains(e.relatedTarget as Node | null)) setOpen(false);
      }}
    >
      <button
        ref={button}
        type="button"
        className="ph-icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Change project icon"
        title="Change project icon"
        disabled={busy}
        aria-busy={busy}
        onClick={() => setOpen((o) => !o)}
      >
        <ProjectIcon name={repo} iconUrl={iconUrl} size={size} />
      </button>
      {open && (
        <div className="usermenu-pop ph-icon-menu" role="menu" aria-label="Project icon" ref={menu} onKeyDown={onMenuKey}>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              close(false);
              file.current?.click();
            }}
          >
            {iconUrl ? 'Replace image…' : 'Upload image…'}
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              close(false);
              setFromRepository(true);
            }}
          >
            Use a file from the repository…
          </button>
          {iconUrl && (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                close(false);
                void run(() => api.deleteProjectIcon(repo));
              }}
            >
              Remove icon
            </button>
          )}
          <div className="usermenu-version ph-icon-hint">PNG, JPEG, WebP, GIF or SVG, up to 1 MB. Shown as a square.</div>
        </div>
      )}
      <input
        ref={file}
        type="file"
        accept={[...PROJECT_ICON_TYPES, '.svg'].join(',')}
        hidden
        tabIndex={-1}
        aria-hidden
        onChange={(e) => picked(e.target.files?.[0])}
      />
      <Dialog open={fromRepository} onClose={() => setFromRepository(false)} title="Icon from the repository">
        <RepositoryIconForm
          repo={repo}
          defaultBranch={defaultBranch}
          onCancel={() => setFromRepository(false)}
          onDone={(p) => {
            onError(null);
            onChanged(p);
            setFromRepository(false);
          }}
        />
      </Dialog>
    </div>
  );
}

/**
 * The repository dialog's form. It only exists while the dialog is open, so every opening starts empty; an error
 * keeps the dialog open with the path as typed.
 */
function RepositoryIconForm({
  repo,
  defaultBranch,
  onCancel,
  onDone,
}: {
  repo: string;
  defaultBranch: string;
  onCancel: () => void;
  onDone: (p: Project) => void;
}) {
  const [path, setPath] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!path.trim() || busy) return;
    setBusy(true);
    setErr(null);
    try {
      onDone(await api.setProjectIconFromRepository(repo, path.trim()));
    } catch (e2) {
      setErr(errorMessage(e2));
      setBusy(false);
    }
  };
  return (
    <form className="form" onSubmit={submit}>
      <div className="field">
        <label htmlFor="icon-repo-path">Path in the repository</label>
        <input
          id="icon-repo-path"
          className="mono"
          value={path}
          onChange={(e) => setPath(e.target.value)}
          maxLength={1024}
          placeholder="e.g. web/public/logo.svg"
          autoFocus
          data-autofocus
          aria-describedby="icon-repo-path-hint"
          spellCheck={false}
          autoComplete="off"
        />
        <p id="icon-repo-path-hint" className="muted small">
          Read once from the default branch (<code>{defaultBranch}</code>). PNG, JPEG, WebP, GIF or SVG, up to 1 MB.
        </p>
      </div>
      {err && (
        <p className="form-error" role="alert">
          {err}
        </p>
      )}
      <div className="dialog-actions">
        <button type="button" className="btn btn--ghost" onClick={onCancel}>
          Cancel
        </button>
        <button className="btn btn--primary" disabled={busy || !path.trim()}>
          {busy ? 'Fetching…' : 'Use as icon'}
        </button>
      </div>
    </form>
  );
}
