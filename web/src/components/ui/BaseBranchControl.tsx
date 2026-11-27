import { useRef, useState, type FormEvent } from 'react';
import { api, ApiError } from '../../api';
import type { Project, ProjectSettings } from '../../api/types';
import { Dialog } from './ui';

const errorMessage = (e: unknown) => (e instanceof ApiError || e instanceof Error ? e.message : 'The base branch could not be saved');

/**
 * The project's base branch (ARCHITECTURE §6 "Base branch"): the branch new runs start from, open their pull request
 * against and merge into. A read-only row for everyone, with a "Change" button that opens the dialog for admins.
 * [onChanged] gets the project as the server answers it.
 */
export function BaseBranchControl({ repo, settings, defaultBranch, isAdmin, onChanged }: {
  repo: string;
  settings: ProjectSettings;
  defaultBranch: string;
  isAdmin: boolean;
  onChanged: (p: Project) => void;
}) {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const close = () => {
    setOpen(false);
    // Focus goes back to the button that opened the dialog, whichever way it closed.
    requestAnimationFrame(() => button.current?.focus());
  };
  const base = settings.baseBranch;
  return (
    <div className="ph-base">
      <span>
        <span className="ph-base-label">Base branch:</span> <code>{base ?? defaultBranch}</code>
        {!base && <span className="muted"> (the repository’s default branch)</span>}
      </span>
      {isAdmin && (
        <button
          ref={button}
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={() => setOpen(true)}
          aria-haspopup="dialog"
          aria-label="Change base branch"
        >
          Change
        </button>
      )}
      <Dialog open={open} onClose={close} title="Base branch">
        <BaseBranchForm
          repo={repo}
          settings={settings}
          defaultBranch={defaultBranch}
          onCancel={close}
          onSaved={(p) => {
            onChanged(p);
            close();
          }}
        />
      </Dialog>
    </div>
  );
}

function BaseBranchForm({ repo, settings, defaultBranch, onCancel, onSaved }: {
  repo: string;
  settings: ProjectSettings;
  defaultBranch: string;
  onCancel: () => void;
  onSaved: (p: Project) => void;
}) {
  const [branch, setBranch] = useState(settings.baseBranch ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      // The endpoint replaces the whole settings object: the merge policy and its reviewers go along unchanged.
      const trimmed = branch.trim();
      onSaved(await api.setProjectSettings(repo, { ...settings, baseBranch: trimmed === '' ? null : trimmed }));
    } catch (e2) {
      setErr(errorMessage(e2));
      setBusy(false);
    }
  };
  return (
    <form className="form" onSubmit={submit}>
      <div className="field">
        <label htmlFor="base-branch">Base branch</label>
        <input
          id="base-branch"
          className="mono"
          value={branch}
          onChange={(e) => setBranch(e.target.value)}
          placeholder={defaultBranch}
          spellCheck={false}
          autoComplete="off"
          data-autofocus
          aria-describedby="base-branch-hint"
        />
        <p id="base-branch-hint" className="muted small">
          Leave empty to use the repository’s default branch (<code>{defaultBranch}</code>). The branch must exist on GitHub.
        </p>
      </div>
      <p className="muted small">Applies to runs started from now on; running runs keep their base branch.</p>
      <p className="muted small">Releases are still created from and merged into the default branch.</p>
      {err && (
        <p className="form-error" role="alert">
          {err}
        </p>
      )}
      <div className="dialog-actions">
        <button type="button" className="btn btn--ghost" onClick={onCancel}>
          Cancel
        </button>
        <button className="btn btn--primary" disabled={busy}>
          {busy ? 'Saving…' : 'Save'}
        </button>
      </div>
    </form>
  );
}
