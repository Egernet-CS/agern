// Deploy targets in the mock (docs/DEPLOY.md): `.agern/deploy.yml` per project (precomputed validation; the mock parses
// no YAML), approvals, the project's key (only a public half exists here) and per-target checks, all in memory.
import type { DeployApproval, DeployCheck, DeployKey, DeployStatus, DeployTarget, Owner, User } from '../api/types';
import { db, MockError, ORG } from './engine';

type MockDeployFile = { content: string; sha256: string; targets: DeployTarget[]; errors: string[] };

const ownerOf = (u: User): Owner => ({ username: u.username, displayName: u.displayName });
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

function b64(bytes: number[]): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

/** An `ssh-ed25519` key line with a random (well-formed) key blob, and an OpenSSH-style made-up fingerprint. */
function randomKey(): { line: string; fingerprint: string } {
  const raw = Array.from(crypto.getRandomValues(new Uint8Array(32)));
  const type = Array.from(new TextEncoder().encode('ssh-ed25519'));
  const blob = [0, 0, 0, type.length, ...type, 0, 0, 0, 32, ...raw];
  const fp = b64(Array.from(crypto.getRandomValues(new Uint8Array(32)))).replace(/=+$/, '');
  return { line: `ssh-ed25519 ${b64(blob)}`, fingerprint: `SHA256:${fp}` };
}

const kioskKey = randomKey();
const stagingKey = randomKey();

const webShopDeploy = `targets:
  kiosk:
    host: kiosk.lobby.example.net
    user: deploy
    hostKey: ${kioskKey.line}
    description: Kiosk in the lobby
`;
const billingDeploy = `targets:
  staging:
    host: 10.0.4.12
    port: 2222
    user: billing
    hostKey: ${stagingKey.line}
`;

const files = new Map<string, MockDeployFile>([
  [
    'web-shop',
    {
      content: webShopDeploy,
      sha256: '9d1e0a6b5c4f3e2d1c0b9a8f7e6d5c4b3a291807f6e5d4c3b2a1908f7e6d5c4b',
      targets: [{ name: 'kiosk', host: 'kiosk.lobby.example.net', port: 22, user: 'deploy', hostKeyType: 'ssh-ed25519', hostKeyFingerprint: kioskKey.fingerprint, description: 'Kiosk in the lobby' }],
      errors: [],
    },
  ],
  [
    'billing-service',
    {
      content: billingDeploy,
      sha256: '1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809',
      targets: [{ name: 'staging', host: '10.0.4.12', port: 2222, user: 'billing', hostKeyType: 'ssh-ed25519', hostKeyFingerprint: stagingKey.fingerprint, description: null }],
      errors: [],
    },
  ],
]);

const approvals = new Map<string, DeployApproval>();
const keys = new Map<string, DeployKey>();

function newKey(repo: string, by: Owner): DeployKey {
  const k = randomKey();
  return { publicKey: `${k.line} agern-deploy:${ORG}/${repo}`, fingerprint: k.fingerprint, createdBy: by, createdAt: new Date().toISOString() };
}

// Demo: web-shop's file is approved and has a key; billing-service's waits for approval.
{
  const alice = db.users.find((u) => u.username === 'alice')!;
  const f = files.get('web-shop')!;
  approvals.set('web-shop', { sha256: f.sha256, content: f.content, approvedBy: ownerOf(alice), approvedAt: ago(60 * 24 * 2) });
  keys.set('web-shop', { ...newKey('web-shop', ownerOf(alice)), createdAt: ago(60 * 24 * 2 + 5) });
}

export function deployStatus(repo: string, branch = 'main'): DeployStatus {
  const f = files.get(repo);
  const approval = approvals.get(repo) ?? null;
  const approvers = db.users.filter((u) => u.isAdmin).map(ownerOf);
  const enabled = db.sandboxEnabled;
  const base = { repo, enabled, mode: enabled ? ('ssh' as const) : ('off' as const), path: '.agern/deploy.yml', branch, approval, approvers, key: keys.get(repo) ?? null };
  if (!f) return { ...base, state: 'none', sha256: null, content: null, errors: [], targets: [] };
  const valid = f.errors.length === 0;
  const state = !valid ? 'invalid' : approval?.sha256 === f.sha256 ? 'approved' : 'pending';
  return { ...base, state, sha256: f.sha256, content: f.content, errors: f.errors, targets: valid ? f.targets : [] };
}

function needAdmin(by: User, what: string) {
  if (!by.isAdmin) throw new MockError(403, 'forbidden', `Only an admin can ${what}`);
}

export function approveDeploy(repo: string, sha256: string, by: User): DeployStatus {
  needAdmin(by, 'approve deploy targets');
  const f = files.get(repo);
  if (!f) throw new MockError(404, 'not_found', `${repo} has no .agern/deploy.yml on main`);
  if (f.sha256 !== sha256.trim().toLowerCase()) throw new MockError(409, 'deploy_definition_changed', `.agern/deploy.yml on main has changed (it is now ${f.sha256}); review it again`);
  if (f.errors.length) throw new MockError(422, 'deploy_definition_invalid', `The file is invalid: ${f.errors.join('; ')}`);
  approvals.set(repo, { sha256: f.sha256, content: f.content, approvedBy: ownerOf(by), approvedAt: new Date().toISOString() });
  return deployStatus(repo);
}

export function revokeDeployApproval(repo: string, by: User): DeployStatus {
  needAdmin(by, 'revoke a deploy approval');
  approvals.delete(repo);
  return deployStatus(repo);
}

export function generateDeployKey(repo: string, by: User): DeployStatus {
  needAdmin(by, 'generate the deploy key');
  if (keys.has(repo)) throw new MockError(409, 'deploy_key_exists', `${repo} already has a deploy key; rotate it instead`);
  keys.set(repo, newKey(repo, ownerOf(by)));
  return deployStatus(repo);
}

export function rotateDeployKey(repo: string, by: User): DeployStatus {
  needAdmin(by, 'rotate the deploy key');
  if (!keys.has(repo)) throw new MockError(404, 'not_found', `${repo} has no deploy key`);
  keys.set(repo, newKey(repo, ownerOf(by)));
  return deployStatus(repo);
}

/** Like DevFakeDeployTransport: hosts starting with `unreachable`, `badkey` or `noauth` fail at that step. */
export function checkDeployTarget(repo: string, target: string, by: User): DeployCheck {
  needAdmin(by, 'check a deploy target');
  const st = deployStatus(repo);
  if (!st.enabled) throw new MockError(409, 'deploy_disabled', 'Deploy targets are switched off on this server (FACTORY_DEPLOY=off)');
  if (st.state !== 'approved') throw new MockError(409, 'deploy_not_approved', '.agern/deploy.yml is not approved in this version');
  if (!st.key) throw new MockError(409, 'deploy_key_missing', 'The project has no deploy key; an admin must generate it on the Deploy page');
  const t = st.targets.find((x) => x.name === target);
  if (!t) throw new MockError(404, 'not_found', `Unknown target '${target}'`);
  const durationMs = 300 + Math.round(Math.random() * 300);
  if (t.host.startsWith('unreachable')) return { target, ok: false, stage: 'connect', message: `${t.host}:${t.port}: connection timed out`, durationMs };
  if (t.host.startsWith('badkey')) return { target, ok: false, stage: 'host_key', message: `Host key of ${target} does not match the pinned key (presented SHA256:Zm9vYmFy)`, durationMs };
  if (t.host.startsWith('noauth')) return { target, ok: false, stage: 'auth', message: `${t.user}@${t.host} refused the key ${st.key.fingerprint}`, durationMs };
  return { target, ok: true, stage: 'ok', message: 'Reachable, host key matches, key accepted', durationMs };
}
