// Sandbox VMs in the mock (docs/SANDBOX-VMS.md): `.agern/sandbox-vm.yml` per project (precomputed validation; the mock
// parses no YAML), approvals, the lease of each VM, a run's decision, the builder's deploy in its transcript and the
// human preview on the run page, all in memory. The key is the project's deploy key (./deploy).
//
// engine.ts imports this module while it is being evaluated (and seeds runs right away): nothing here may touch `db` or
// ./deploy at module level, and the run decision and builder steps must not call into ./deploy at all.
import type { Owner, Run, RunSandboxVm, SandboxApproval, SandboxVm, SandboxVmCheck, SandboxVmLease, SandboxVmLog, SandboxVmStatus, User } from '../api/types';
import * as deploy from './deploy';
import { db, MockError, touchRun } from './engine';
import type { Step, SystemStep } from './scripts';

type MockVmFile = { content: string; sha256: string; vm: SandboxVm | null; errors: string[] };
type MockLease = Omit<SandboxVmLease, 'visible'> & { key: string };

const PREVIEW_MINUTES = 120;
const PATH = '.agern/sandbox-vm.yml';
const ownerOf = (u: User): Owner => ({ username: u.username, displayName: u.displayName });
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const later = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();
const hostKeyLine = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGxvYmJ5LXZtLWhvc3Qta2V5LWZvci10aGUtbW9jaw==';

const shopVm: SandboxVm = {
  name: 'shop-staging',
  host: 'vm.shop.example.net',
  port: 22,
  user: 'agern',
  hostKeyType: 'ssh-ed25519',
  hostKeyFingerprint: 'SHA256:k3Vn0bq3Jb6mF2yD5gQe8l1xHq7Yw9zA4cR2sT6uV0E',
  description: 'Debian VM with nginx, systemd and PostgreSQL 17',
  url: 'https://vm.shop.example.net/',
  checkout: 'rm -rf /srv/shop/src && mkdir -p /srv/shop/src && tar xzf "$AGERN_ARCHIVE" -C /srv/shop/src',
  setup: 'cd /srv/shop/src && ./gradlew -q installDist\nsudo systemctl restart shop',
  ready: { command: 'curl -fsS http://127.0.0.1:8080/health', timeoutSeconds: 60 },
  reset: '/srv/shop/bin/restore-test-db',
};

const shopFile = `name: shop-staging
host: vm.shop.example.net
user: agern
hostKey: ${hostKeyLine}
description: Debian VM with nginx, systemd and PostgreSQL 17
url: https://vm.shop.example.net/
checkout: rm -rf /srv/shop/src && mkdir -p /srv/shop/src && tar xzf "$AGERN_ARCHIVE" -C /srv/shop/src
setup: |
  cd /srv/shop/src && ./gradlew -q installDist
  sudo systemctl restart shop
ready:
  command: curl -fsS http://127.0.0.1:8080/health
reset: /srv/shop/bin/restore-test-db
`;

const billingFile = `host: 10.0.4.20
user: billing
hostKey: ${hostKeyLine}
checkout: tar xzf "$AGERN_ARCHIVE" -C /opt/billing
ready:
  command: systemctl is-active billing
  timeoutSeconds: 900
`;

const files = new Map<string, MockVmFile>([
  ['web-shop', { content: shopFile, sha256: '5e0c7d1a9b2f4e3c8d6a1b0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d', vm: shopVm, errors: [] }],
  ['billing-service', { content: billingFile, sha256: 'c0ffee11d0d0cafe5eed1234abcd5678ef90aa11bb22cc33dd44ee55ff667788', vm: null, errors: ['ready.timeoutSeconds: must be between 1 and 600 (is 900)'] }],
]);

const approvals = new Map<string, SandboxApproval>();
/** One lease per VM, keyed by `lowercase(host):port`. */
const leases = new Map<string, MockLease>();
const vmKey = (vm: SandboxVm) => `${vm.host.toLowerCase()}:${vm.port}`;
let seeded = false;

/** Demo: web-shop's VM is approved (by alice, two days ago); billing-service's file is invalid. Lazy, see the header. */
function seed() {
  if (seeded) return;
  seeded = true;
  const alice = db.users.find((u) => u.username === 'alice')!;
  approvals.set('web-shop', { sha256: files.get('web-shop')!.sha256, content: shopFile, approvedBy: ownerOf(alice), approvedAt: ago(60 * 24 * 2) });
}

function state(repo: string): SandboxVmStatus['state'] {
  seed();
  const f = files.get(repo);
  if (!f) return 'none';
  if (f.errors.length || !f.vm) return 'invalid';
  return approvals.get(repo)?.sha256 === f.sha256 ? 'approved' : 'pending';
}

function leaseFor(vm: SandboxVm | null): SandboxVmLease | null {
  const l = vm ? leases.get(vmKey(vm)) : undefined;
  if (!l) return null;
  // The mock's users see every project.
  return { kind: l.kind, state: l.state, since: l.since, expiresAt: l.expiresAt, runId: l.runId, repo: l.repo, issueNumber: l.issueNumber, visible: true };
}

export function getSandboxVm(repo: string, branch = 'main'): SandboxVmStatus {
  const s = state(repo);
  const f = files.get(repo);
  const approval = approvals.get(repo) ?? null;
  const enabled = db.sandboxEnabled;
  const vm = f && !f.errors.length ? f.vm : null;
  return {
    repo,
    enabled,
    mode: enabled ? 'ssh' : 'off',
    path: PATH,
    branch,
    state: s,
    sha256: f?.sha256 ?? null,
    content: f?.content ?? null,
    errors: f?.errors ?? [],
    vm,
    approval,
    approvers: db.users.filter((u) => u.isAdmin).map(ownerOf),
    key: deploy.deployStatus(repo, branch).key,
    lease: leaseFor(vm),
    previewMinutes: PREVIEW_MINUTES,
  };
}

function needAdmin(by: User, what: string) {
  if (!by.isAdmin) throw new MockError(403, 'forbidden', `Only an admin can ${what}`);
}

export function approveSandboxVm(repo: string, sha256: string, by: User): SandboxVmStatus {
  needAdmin(by, 'approve a sandbox VM');
  const f = files.get(repo);
  if (!f) throw new MockError(404, 'not_found', `${repo} has no ${PATH} on main`);
  if (f.sha256 !== sha256.trim().toLowerCase()) throw new MockError(409, 'sandbox_vm_definition_changed', `${PATH} on main has changed (it is now ${f.sha256}); review it again`);
  if (f.errors.length) throw new MockError(422, 'sandbox_vm_definition_invalid', `The file is invalid: ${f.errors.join('; ')}`);
  approvals.set(repo, { sha256: f.sha256, content: f.content, approvedBy: ownerOf(by), approvedAt: new Date().toISOString() });
  return getSandboxVm(repo);
}

export function revokeSandboxVm(repo: string, by: User): SandboxVmStatus {
  needAdmin(by, 'revoke a sandbox VM approval');
  seed();
  approvals.delete(repo);
  // Like the server: the repository's leases end at once, without reset.
  for (const [k, l] of leases) {
    if (l.repo !== repo) continue;
    leases.delete(k);
    const r = l.runId ? db.runs.get(l.runId) : undefined;
    if (r?.sandboxVm?.preview && ['deploying', 'ready'].includes(r.sandboxVm.preview.state)) {
      r.sandboxVm.preview = { ...r.sandboxVm.preview, state: 'released', endedAt: new Date().toISOString() };
      touchRun(r);
    }
  }
  return getSandboxVm(repo);
}

function approvedVm(repo: string): SandboxVm {
  if (!db.sandboxEnabled) throw new MockError(409, 'sandbox_vm_disabled', 'Sandbox VMs are switched off on this server (FACTORY_SANDBOX_VM=off)');
  if (state(repo) !== 'approved') throw new MockError(409, 'sandbox_vm_not_approved', `${PATH} is not approved in this version`);
  if (!deploy.deployStatus(repo).key) throw new MockError(409, 'sandbox_vm_key_missing', `The project has no deploy key; an admin must generate it on the sandbox VM page`);
  return files.get(repo)!.vm!;
}

/** Like the fake transport: hosts starting with `unreachable`, `badkey` or `noauth` fail at that step. */
export function checkSandboxVm(repo: string, by: User): SandboxVmCheck {
  needAdmin(by, 'check a sandbox VM');
  const vm = approvedVm(repo);
  const durationMs = 400 + Math.round(Math.random() * 300);
  const base = { exitCode: null, output: null };
  if (vm.host.startsWith('unreachable')) return { ...base, ok: false, stage: 'connect', message: `${vm.host}:${vm.port}: connection timed out`, durationMs };
  if (vm.host.startsWith('badkey')) return { ...base, ok: false, stage: 'host_key', message: 'The host presented SHA256:Zm9vYmFy, not the pinned key', durationMs };
  if (vm.host.startsWith('noauth')) return { ...base, ok: false, stage: 'auth', message: `${vm.user}@${vm.host} refused the key`, durationMs };
  if (vm.ready) return { ok: true, stage: 'ok', message: 'Reachable, host key matches, key accepted, ready', durationMs: durationMs + 180, exitCode: 0, output: '{"status":"UP"}\n' };
  return { ...base, ok: true, stage: 'ok', message: 'Reachable, host key matches, key accepted', durationMs };
}

export function releaseSandboxVmLease(repo: string, by: User): SandboxVmStatus {
  needAdmin(by, 'release a sandbox VM');
  const vm = files.get(repo)?.vm;
  const l = vm ? leases.get(vmKey(vm)) : undefined;
  if (!vm || !l) throw new MockError(404, 'not_found', 'Nobody holds the sandbox VM');
  endLease(vm, l);
  return getSandboxVm(repo);
}

/** Ends a lease: `resetting` for a moment (the reset), then free. A preview it belonged to becomes `released`. */
function endLease(vm: SandboxVm, l: MockLease, expired = false) {
  l.state = 'resetting';
  const r = l.runId ? db.runs.get(l.runId) : undefined;
  if (l.kind === 'preview' && r?.sandboxVm?.preview && ['deploying', 'ready'].includes(r.sandboxVm.preview.state)) {
    r.sandboxVm.preview = { ...r.sandboxVm.preview, state: 'released', endedAt: new Date().toISOString(), expired };
    touchRun(r);
  }
  setTimeout(() => {
    if (leases.get(vmKey(vm)) === l) leases.delete(vmKey(vm));
  }, vm.reset ? 1500 : 0);
}

// ---------------------------------------------------------------- a run's decision and its builder

/** The decision stored on a run when it starts (never touches ./deploy: see the header). */
export function runSandboxVmFor(repo: string): RunSandboxVm {
  const base = { sha256: null, name: null, host: null, user: null, url: null, used: false, previewMinutes: PREVIEW_MINUTES, preview: null };
  if (!db.sandboxEnabled) return { ...base, state: 'disabled', reason: 'Sandbox VMs are switched off on this server (FACTORY_SANDBOX_VM=off).' };
  const f = files.get(repo);
  switch (state(repo)) {
    case 'none':
      return { ...base, state: 'none', reason: `The repository has no ${PATH} on its default branch.` };
    case 'invalid':
      return { ...base, state: 'invalid', sha256: f!.sha256, reason: `${PATH} is invalid: ${f!.errors.join('; ')}` };
    case 'pending':
      return { ...base, state: 'unapproved', sha256: f!.sha256, reason: `${PATH} (${f!.sha256.slice(0, 12)}) is not approved. An admin can approve it on the project's sandbox VM page.` };
    case 'approved': {
      const vm = f!.vm!;
      return { ...base, state: 'approved', sha256: f!.sha256, name: vm.name, host: vm.host, user: vm.user, url: vm.url, reason: `${PATH} (${f!.sha256.slice(0, 12)}) is approved: the builder may deploy to ${vm.name}.` };
    }
  }
}

const vmLog = (vm: SandboxVm, l: Partial<SandboxVmLog> & Pick<SandboxVmLog, 'kind'>): SandboxVmLog => ({
  step: null, vm: vm.name, host: vm.host, port: vm.port, user: vm.user, command: null, sha: null, exitCode: 0, timedOut: false, durationMs: 0, output: '', outputTruncated: false, error: null, ...l,
});

function system(log: SandboxVmLog): SystemStep {
  const what = log.kind === 'deploy' ? `deploy ${log.step}` : log.kind === 'reset' ? 'reset' : `\`${(log.command ?? '').split('\n')[0]}\``;
  const outcome = log.exitCode == null ? 'done' : `exit ${log.exitCode}`;
  return { kind: 'system', level: 'info', text: `Sandbox VM [${log.vm}] ${what}: ${outcome} in ${(log.durationMs / 1000).toFixed(1)} s`, sandboxVm: log };
}

/** The builder of an approved run deploys its commit and checks the health endpoint (marks the run's VM `used`). */
export function sandboxVmBuilderSteps(r: Run): Step[] {
  if (r.sandboxVm?.state !== 'approved') return [];
  const vm = files.get(r.repo)?.vm;
  if (!vm) return [];
  r.sandboxVm.used = true;
  const sha = Array.from({ length: 40 }, (_, i) => '0123456789abcdef'[(i * 7 + r.issueNumber) % 16]).join('');
  const steps = [
    vmLog(vm, { kind: 'deploy', step: 'upload', sha, exitCode: null, durationMs: 900, output: '' }),
    vmLog(vm, { kind: 'deploy', step: 'checkout', sha, command: vm.checkout, durationMs: 1300 }),
    vmLog(vm, { kind: 'deploy', step: 'setup', sha, command: vm.setup, durationMs: 14_800, output: 'Stopping shop.service…\nStarting shop.service…\n' }),
    vmLog(vm, { kind: 'deploy', step: 'ready', sha, command: vm.ready!.command, durationMs: 3200, output: 'curl: (7) Failed to connect to 127.0.0.1 port 8080\ncurl: (7) Failed to connect to 127.0.0.1 port 8080\n{"status":"UP"}\n' }),
  ];
  const check = vmLog(vm, { kind: 'exec', command: 'curl -fsS http://127.0.0.1:8080/api/cart/health && systemctl is-active shop', durationMs: 600, output: '{"cart":"ok"}\nactive\n' });
  return [
    { kind: 'think', text: 'The project has a sandbox VM with systemd and PostgreSQL. Deploy the commit there and check that the service comes up.' },
    {
      kind: 'tool',
      name: 'mcp__factory__factory_sandbox_vm_deploy',
      input: {},
      result: JSON.stringify({ handle: 'vm-1', ok: true, sha, dirty: false, url: vm.url, steps: steps.map((s) => ({ step: s.step, ok: true, exitCode: s.exitCode, timedOut: false, durationMs: s.durationMs })), failedStep: null, output: '{"status":"UP"}\n', truncated: false, durationMs: 20_200 }),
      ms: 4000,
      logs: steps.map(system),
    },
    {
      kind: 'tool',
      name: 'mcp__factory__factory_sandbox_vm_exec',
      input: { command: check.command },
      result: JSON.stringify({ handle: 'vm-2', exitCode: 0, output: check.output, truncated: false, durationMs: check.durationMs, timedOut: false }),
      ms: 1200,
      logs: [system(check)],
    },
  ];
}

// ---------------------------------------------------------------- the human preview

function canAct(r: Run, by: User) {
  if (r.owner.username !== by.username && !by.isAdmin) throw new MockError(403, 'forbidden', 'Only the run owner or an admin can do this');
}

/** Deploys the run's latest commit in the background: `deploying` now, `ready` (or `failed` for `unreachable*` hosts) ~1.5 s later. */
export function startSandboxVmPreview(r: Run, by: User): Run {
  canAct(r, by);
  if (r.status !== 'needs_human') throw new MockError(409, 'run_not_waiting', `Run is ${r.status}; a preview needs a run that needs a human`);
  if (r.sandboxVm?.state !== 'approved') throw new MockError(409, 'sandbox_vm_not_approved', 'The run has no approved sandbox VM');
  const vm = approvedVm(r.repo);
  const k = vmKey(vm);
  const held = leases.get(k);
  if (held && (held.runId !== r.id || held.kind !== 'preview' || held.state === 'resetting')) {
    throw new MockError(409, 'sandbox_vm_busy', held.state === 'resetting' ? `The sandbox VM ${vm.name} is being reset.` : `The sandbox VM ${vm.name} is in use by run ${held.runId?.slice(0, 8)} of ${held.repo} since ${held.since}.`);
  }
  if (r.sandboxVm.preview?.state === 'deploying') throw new MockError(409, 'sandbox_vm_busy', `The sandbox VM ${vm.name} is deploying this run already.`);
  const now = new Date().toISOString();
  const lease: MockLease = held ?? { key: k, kind: 'preview', state: 'held', since: now, expiresAt: later(PREVIEW_MINUTES), runId: r.id, repo: r.repo, issueNumber: r.issueNumber };
  leases.set(k, lease);
  const sha = Array.from({ length: 40 }, (_, i) => '0123456789abcdef'[(i * 5 + r.issueNumber + Date.now()) % 16]).join('');
  r.sandboxVm.preview = { state: 'deploying', sha, url: vm.url, startedBy: ownerOf(by), startedAt: now, expiresAt: null, endedAt: null, expired: false, failedStep: null, error: null, output: null };
  touchRun(r);
  setTimeout(() => {
    const p = r.sandboxVm?.preview;
    if (!p || p.state !== 'deploying' || leases.get(k) !== lease) return;
    if (vm.host.startsWith('unreachable')) {
      r.sandboxVm!.preview = { ...p, state: 'failed', failedStep: 'connect', error: `${vm.host}:${vm.port}: connection timed out`, output: '', endedAt: new Date().toISOString() };
      endLease(vm, lease);
    } else {
      lease.expiresAt = later(PREVIEW_MINUTES);
      r.sandboxVm!.preview = { ...p, state: 'ready', expiresAt: lease.expiresAt };
    }
    touchRun(r);
  }, 1500);
  return r;
}

export function releaseSandboxVmPreview(r: Run, by: User): Run {
  canAct(r, by);
  const vm = files.get(r.repo)?.vm;
  const l = vm ? leases.get(vmKey(vm)) : undefined;
  if (!vm || !l || l.runId !== r.id || l.kind !== 'preview' || l.state !== 'held') throw new MockError(404, 'not_found', 'The run has no active preview');
  endLease(vm, l);
  return r;
}

/** Like the server: a preview ends when its run leaves `needs_human`. */
export function runLeftNeedsHuman(r: Run) {
  const vm = files.get(r.repo)?.vm;
  const l = vm ? leases.get(vmKey(vm)) : undefined;
  if (vm && l && l.runId === r.id && l.state === 'held') endLease(vm, l);
}

/** Demo: the escalated web-shop run alice owns has a preview that is ready (so the run page shows it at once). */
export function seedSandboxVmPreview() {
  const r = [...db.runs.values()].find((x) => x.repo === 'web-shop' && x.status === 'needs_human' && x.sandboxVm?.state === 'approved');
  const vm = files.get('web-shop')?.vm;
  if (!r || !vm || !r.sandboxVm) return;
  const since = ago(12);
  const lease: MockLease = { key: vmKey(vm), kind: 'preview', state: 'held', since, expiresAt: later(PREVIEW_MINUTES - 12), runId: r.id, repo: r.repo, issueNumber: r.issueNumber };
  leases.set(lease.key, lease);
  r.sandboxVm.preview = {
    state: 'ready',
    sha: '3f2a1c9e5b7d40a18c6e2f9b0d4a7c1e3b5d9f20',
    url: vm.url,
    startedBy: r.owner,
    startedAt: since,
    expiresAt: lease.expiresAt,
    endedAt: null,
    expired: false,
    failedStep: null,
    error: null,
    output: null,
  };
}
