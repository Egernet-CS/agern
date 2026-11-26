import type { DeployCheck, DeployStatus } from '../../api/types';
import type { SandboxTone } from '../sandbox/SandboxBits';

/** How a project's deploy state reads in one pill and one line (the Project page's Deploy row, the Deploy page's header). */
export function deploySummary(st: DeployStatus): { tone: SandboxTone; label: string; line: string } {
  switch (st.state) {
    case 'none':
      return { tone: 'idle', label: 'none', line: 'No deploy targets' };
    case 'invalid':
      return { tone: 'fail', label: 'invalid', line: 'Invalid' };
    case 'pending':
      return st.approval
        ? { tone: 'escalate', label: 'changed', line: 'Changed since approval' }
        : { tone: 'escalate', label: 'needs approval', line: 'Waiting for approval' };
    case 'approved': {
      const n = st.targets.length;
      return { tone: st.key ? 'pass' : 'escalate', label: 'approved', line: `${n} target${n === 1 ? '' : 's'} approved${st.key ? '' : ' · no key yet'}` };
    }
  }
}

export function DeployPill({ status }: { status: DeployStatus }) {
  const s = deploySummary(status);
  return (
    <span className={`pill pill--${s.tone}`}>
      <span className="pill-dot" aria-hidden />
      {s.label}
    </span>
  );
}

/** The sentence a per-target check shows: what worked, or the step that failed. */
export function checkSentence(c: DeployCheck, user: string): string {
  const secs = `${(c.durationMs / 1000).toFixed(1)} s`;
  switch (c.stage) {
    case 'ok':
      return `Reachable, host key matches, key accepted (${secs})`;
    case 'host_key':
      return `Host key does not match: ${c.message}`;
    case 'auth':
      return `Key refused — is the public key in authorized_keys of ${user}?`;
    case 'connect':
      return `Could not connect: ${c.message}`;
  }
}
