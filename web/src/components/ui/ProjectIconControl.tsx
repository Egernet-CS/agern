import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { api, ApiError, PROJECT_ICON_MAX_BYTES, PROJECT_ICON_TYPES, type Project } from '../../api';
import { ProjectIcon } from './ui';

/**
 * The project header's icon for admins: a button opening a small menu to upload, replace or remove the icon
 * (ARCHITECTURE §8 "Project icons"). The server checks everything again; only the size is checked here up front.
 */
export function ProjectIconControl({
  repo,
  iconUrl,
  size,
  onChanged,
  onError,
}: {
  repo: string;
  iconUrl: string | null;
  size: number;
  onChanged: (p: Project) => void;
  /** A message to show, or null to clear the previous one. */
  onError: (message: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
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
      onError(e instanceof ApiError || e instanceof Error ? e.message : 'The icon could not be changed');
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
          <div className="usermenu-version ph-icon-hint">PNG, JPEG, WebP or GIF, up to 1 MB. Shown as a square.</div>
        </div>
      )}
      <input
        ref={file}
        type="file"
        accept={PROJECT_ICON_TYPES.join(',')}
        hidden
        tabIndex={-1}
        aria-hidden
        onChange={(e) => picked(e.target.files?.[0])}
      />
    </div>
  );
}
