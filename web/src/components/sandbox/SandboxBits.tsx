import { Link } from 'react-router-dom';
import type { Run, RunSandbox, SandboxStatus } from '../../api/types';
import { sandboxUse } from './commands';

export type SandboxTone = 'pass' | 'escalate' | 'fail' | 'idle';

/** Short name of an image for tight spaces: `docker.io/library/eclipse-temurin:21-jdk` → `eclipse-temurin:21-jdk`. */
export const shortImage = (image: string) => image.replace(/^docker\.io\/library\//, '').replace(/^docker\.io\//, '');

export const shortHash = (sha: string | null | undefined) => (sha ? sha.slice(0, 12) : '—');

/** How a project's sandbox state reads in one pill and one line. */
export function sandboxSummary(st: SandboxStatus): { tone: SandboxTone; label: string; line: string } {
  const off = st.enabled ? '' : 'sandboxes off · ';
  switch (st.state) {
    case 'none':
      return { tone: 'idle', label: 'no definition', line: `${off}no ${st.path} on ${st.branch}` };
    case 'invalid':
      return { tone: 'fail', label: 'invalid', line: `${off}${st.errors.length} problem${st.errors.length === 1 ? '' : 's'} in ${st.path}` };
    case 'pending':
      return st.approval
        ? { tone: 'escalate', label: 'changed', line: `${off}changed since the approved version · needs an admin` }
        : { tone: 'escalate', label: 'needs approval', line: `${off}${shortImage(st.definition?.image ?? '')} · not approved yet` };
    case 'approved':
      return { tone: 'pass', label: 'approved', line: `${off}${shortImage(st.definition?.image ?? '')}` };
  }
}

export function SandboxPill({ status }: { status: SandboxStatus }) {
  const s = sandboxSummary(status);
  return (
    <span className={`pill pill--${s.tone}`}>
      <span className="pill-dot" aria-hidden />
      {s.label}
    </span>
  );
}

const WHY_NONE: Record<string, string> = {
  disabled: 'sandboxes are switched off on this server',
  none: 'the project has no .agern/sandbox.yml',
  unapproved: 'its sandbox definition is not approved',
  invalid: 'its sandbox definition is invalid',
};

/**
 * One line on the run page: the image the run's builder used (with the approved definition's hash), or why none; for an
 * approved run also whether the project builds on the host and, below the line, the commands the builder ran in the sandbox.
 */
export function RunSandboxLine({ run }: { run: Run }) {
  const sb = run.sandbox;
  if (!sb) return null;
  const approved = sb.state === 'approved';
  const use = approved ? sandboxUse(sb) : null;
  return (
    <>
      <p className={`run-sbx run-sbx--${approved ? 'on' : 'off'}`} title={sb.reason}>
        <span className="run-sbx-k">sandbox</span>
        {approved ? (
          <>
            <code className="run-sbx-image">{sb.image}</code>
            <span className="run-sbx-why">
              definition <code>{shortHash(sb.sha256)}</code>
            </span>
            {use?.host && (
              <span className="run-sbx-why run-sbx-host" title="The builder builds and runs plain tests on the host; the sandbox is only for commands that need it">
                build on host
              </span>
            )}
            {use?.notUsed && (
              <span className="run-sbx-why" title="The builder ran no command in the sandbox">
                sandbox not used
              </span>
            )}
            {(sb.platforms?.length ?? 0) > 0 && <RunPlatforms declared={sb.platforms} used={sb.platformsUsed ?? []} />}
            {sb.reviewUsed && (
              <span className="run-sbx-why run-sbx-review" title="The ux and ui-design reviewers ran the app in a read-only review sandbox">
                review sandbox ✓
              </span>
            )}
          </>
        ) : (
          <span className="run-sbx-why">none — {WHY_NONE[sb.state] ?? sb.reason}; built and tested on the host</span>
        )}
        {/* A workspace run's sandbox is its target member's. */}
        <Link to={`/p/${run.targetRepo ?? run.repo}/sandbox`} className="run-sbx-link" aria-label={`Sandbox settings of ${run.targetRepo ?? run.repo}`}>
          settings
        </Link>
      </p>
      {use?.summary && <RunSandboxCommands sb={sb} summary={use.summary} more={use.more} />}
    </>
  );
}

/** The commands the builder ran in the build sandbox, collapsed under their count (a native, keyboard-operable disclosure). */
function RunSandboxCommands({ sb, summary, more }: { sb: RunSandbox; summary: string; more: number }) {
  return (
    <details className="run-sbx-cmds">
      <summary>{summary}</summary>
      <ol>
        {(sb.commands ?? []).map((c, i) => (
          <li key={i}>
            <code>{c.command}</code>
            {c.platform && <span className="sbx-plat">{c.platform}</span>}
          </li>
        ))}
      </ol>
      {more > 0 && <p className="muted small run-sbx-more">and {more} more: see the builder's transcript</p>}
    </details>
  );
}

/** The definition's platforms on the run line: which ones the builder ran commands on, and which it did not (yet). */
function RunPlatforms({ declared, used }: { declared: string[]; used: string[] }) {
  const unused = declared.filter((p) => !used.includes(p));
  return (
    <span className="run-sbx-why run-sbx-plats">
      platforms{' '}
      {used.filter((p) => p !== 'default').map((p) => (
        <span key={p} className="sbx-plat run-sbx-plat--used" title={`Commands ran on ${p}`}>
          ✓ {p}
        </span>
      ))}
      {unused.map((p) => (
        <span key={p} className="sbx-plat run-sbx-plat--unused" title={`No command ran on ${p}`}>
          {p}
        </span>
      ))}
      {unused.length > 0 && <span className="run-sbx-plat-note"> · {unused.length === declared.length ? 'none used yet' : `${unused.length} not used`}</span>}
    </span>
  );
}
