import { Link } from 'react-router-dom';
import type { Run, SandboxVmCheck, SandboxVmStatus } from '../../api/types';
import { shortHash, type SandboxTone } from '../sandbox/SandboxBits';

/** How a project's sandbox VM state reads in one pill and one line (the Project page's row, the page's header). */
export function sandboxVmSummary(st: SandboxVmStatus): { tone: SandboxTone; label: string; line: string } {
  const off = st.enabled ? '' : 'sandbox VMs off · ';
  switch (st.state) {
    case 'none':
      return { tone: 'idle', label: 'none', line: `${off}No sandbox VM` };
    case 'invalid':
      return { tone: 'fail', label: 'invalid', line: `${off}${st.errors.length} problem${st.errors.length === 1 ? '' : 's'} in ${st.path}` };
    case 'pending':
      return st.approval
        ? { tone: 'escalate', label: 'changed', line: `${off}Changed since approval` }
        : { tone: 'escalate', label: 'needs approval', line: `${off}${st.vm ? `${st.vm.name} (${st.vm.host}) · ` : ''}Waiting for approval` };
    case 'approved': {
      const vm = st.vm ? `${st.vm.name} (${st.vm.host})` : 'Approved';
      const lease = st.lease ? (st.lease.state === 'resetting' ? ' · being reset' : ' · in use') : '';
      return { tone: st.key ? 'pass' : 'escalate', label: 'approved', line: `${off}${vm}${st.key ? '' : ' · no key yet'}${lease}` };
    }
  }
}

export function SandboxVmPill({ status }: { status: SandboxVmStatus }) {
  const s = sandboxVmSummary(status);
  return (
    <span className={`pill pill--${s.tone}`}>
      <span className="pill-dot" aria-hidden />
      {s.label}
    </span>
  );
}

/** The sentence the check shows: what worked, or the step that failed. */
export function sandboxVmCheckSentence(c: SandboxVmCheck, user: string): string {
  const secs = `${(c.durationMs / 1000).toFixed(1)} s`;
  switch (c.stage) {
    case 'ok':
      return `Reachable, host key matches, key accepted${c.exitCode != null ? ', ready check passed' : ''} (${secs})`;
    case 'ready':
      return `Connected, but the ready check failed${c.exitCode != null ? ` (exit ${c.exitCode})` : ''}: ${c.message}`;
    case 'host_key':
      return `Host key does not match: ${c.message}`;
    case 'auth':
      return `Key refused — is the public key in authorized_keys of ${user}?`;
    case 'connect':
      return `Could not connect: ${c.message}`;
  }
}

const WHY_NONE: Record<string, string> = {
  unapproved: 'its sandbox VM definition is not approved',
  invalid: 'its sandbox VM definition is invalid',
};

/**
 * One line on the run page, below the sandbox line: the VM the run's builder may deploy to (with the approved
 * definition's hash, and whether it did), or why it has none. Nothing when the project has no VM or the feature is off.
 */
export function RunSandboxVmLine({ run }: { run: Run }) {
  const vm = run.sandboxVm;
  if (!vm || vm.state === 'none' || vm.state === 'disabled') return null;
  const approved = vm.state === 'approved';
  const repo = run.targetRepo ?? run.repo;
  return (
    <p className={`run-svm run-svm--${approved ? 'on' : 'off'}`} title={vm.reason}>
      <span className="run-svm-k">sandbox VM</span>
      {approved ? (
        <>
          <code className="run-svm-name">{vm.name}</code>
          <span className="run-svm-why">
            · definition <code>{shortHash(vm.sha256)}</code>
          </span>
          {vm.used && (
            <span className="run-svm-used" title="The builder deployed to the sandbox VM">
              · used ✓
            </span>
          )}
        </>
      ) : (
        <span className="run-svm-why">none — {WHY_NONE[vm.state] ?? vm.reason}</span>
      )}
      <Link to={`/p/${repo}/sandbox-vm`} className="run-svm-link" aria-label={`Sandbox VM settings of ${repo}`}>
        settings
      </Link>
    </p>
  );
}
