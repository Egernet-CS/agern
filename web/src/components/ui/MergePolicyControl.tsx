import { useRef, useState, type FormEvent } from 'react';
import { api, ApiError } from '../../api';
import { MERGE_POLICIES, type MergePolicy, type Project, type ProjectSettings } from '../../api/types';
import { Dialog } from './ui';
import { MERGE_POLICY_EXPLANATION, MERGE_POLICY_LABEL, mergePolicySentence } from './mergePolicy';

const errorMessage = (e: unknown) => (e instanceof ApiError || e instanceof Error ? e.message : 'The settings could not be saved');
const splitList = (s: string) => s.split(',').map((x) => x.trim()).filter(Boolean);

/** "Reviewers requested: @a, @b, team core" for a project that merges on GitHub; null without reviewers. */
function reviewersLine(s: ProjectSettings): string | null {
  const all = [...s.reviewers.map((r) => `@${r}`), ...s.teamReviewers.map((t) => `team ${t}`)];
  return all.length ? `Reviewers requested: ${all.join(', ')}` : null;
}

/**
 * The project's merge policy (ARCHITECTURE §6 "Merge policy"): a read-only row for everyone, with a "Change" button that
 * opens the settings dialog for admins. [onChanged] gets the project as the server answers it.
 */
export function MergePolicyControl({ repo, settings, isAdmin, onChanged }: {
  repo: string;
  settings: ProjectSettings;
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
  const reviewers = settings.mergePolicy === 'github' ? reviewersLine(settings) : null;
  return (
    <div className="ph-policy">
      <span>
        <span className="ph-policy-label">Merge policy:</span> {mergePolicySentence(settings.mergePolicy)}
        {reviewers && <span className="muted"> {reviewers}.</span>}
      </span>
      {isAdmin && (
        <button ref={button} type="button" className="btn btn--ghost btn--sm" onClick={() => setOpen(true)} aria-haspopup="dialog">
          Change
        </button>
      )}
      <Dialog open={open} onClose={close} title="Merge policy">
        <SettingsForm
          repo={repo}
          settings={settings}
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

function SettingsForm({ repo, settings, onCancel, onSaved }: {
  repo: string;
  settings: ProjectSettings;
  onCancel: () => void;
  onSaved: (p: Project) => void;
}) {
  const [policy, setPolicy] = useState<MergePolicy>(settings.mergePolicy);
  const [reviewers, setReviewers] = useState(settings.reviewers.join(', '));
  const [teams, setTeams] = useState(settings.teamReviewers.join(', '));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      onSaved(await api.setProjectSettings(repo, { mergePolicy: policy, reviewers: splitList(reviewers), teamReviewers: splitList(teams) }));
    } catch (e2) {
      setErr(errorMessage(e2));
      setBusy(false);
    }
  };
  return (
    <form className="form" onSubmit={submit}>
      <fieldset className="field mode-choice retry-choice">
        <legend>Merge policy</legend>
        {MERGE_POLICIES.map((p) => (
          <label key={p}>
            <input
              type="radio"
              name="merge-policy"
              value={p}
              checked={policy === p}
              onChange={() => setPolicy(p)}
              data-autofocus={policy === p ? true : undefined}
            />
            <span>
              <span className="retry-label">{MERGE_POLICY_LABEL[p]}</span>
              <span className="muted small retry-detail">{MERGE_POLICY_EXPLANATION[p][0].toUpperCase() + MERGE_POLICY_EXPLANATION[p].slice(1)}</span>
            </span>
          </label>
        ))}
      </fieldset>
      {policy === 'github' && (
        <>
          <div className="field">
            <label htmlFor="policy-reviewers">Reviewers (GitHub logins, comma-separated)</label>
            <input id="policy-reviewers" className="mono" value={reviewers} onChange={(e) => setReviewers(e.target.value)} spellCheck={false} autoComplete="off" />
          </div>
          <div className="field">
            <label htmlFor="policy-teams">Team reviewers (team slugs, comma-separated)</label>
            <input id="policy-teams" className="mono" value={teams} onChange={(e) => setTeams(e.target.value)} spellCheck={false} autoComplete="off" />
          </div>
        </>
      )}
      <p className="muted small">Applies to runs started from now on; running runs keep their policy.</p>
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
