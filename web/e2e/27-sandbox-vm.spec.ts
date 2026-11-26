import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, readState, userContext, writeState } from './support';

// Sandbox VMs (docs/SANDBOX-VMS.md) against the real backend with FACTORY_SANDBOX_VM=fake: a pending file that only an
// admin approves (after the project's key exists), a run whose builder deploys to the VM and runs a command there
// (`fake:sandbox-vm`), and the owner of an escalated run trying it on the VM and releasing it again.
test.describe.configure({ mode: 'serial' });

/** An `ssh-ed25519 <base64>` host key line whose key blob is well formed (32 key bytes from [seed]). */
function hostKey(seed: number): string {
  const type = Buffer.from('ssh-ed25519');
  const len = (n: number) => Buffer.from([0, 0, 0, n]);
  const raw = Buffer.alloc(32, seed);
  return `ssh-ed25519 ${Buffer.concat([len(type.length), type, len(32), raw]).toString('base64')}`;
}

const tag = Date.now().toString(36);
const VM = `name: e2e-vm
host: vm.example.net
port: 22
user: agern
hostKey: ${hostKey(11)}
description: E2E VM ${tag}
url: https://vm.example.net/
checkout: echo checkout
setup: echo setup
ready: { command: echo ready }
`;

/** Puts `.agern/sandbox-vm.yml` on demo-app's default branch in the fake GitHub (null removes it). */
function vmFile(content: string | null) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (the global setup starts the server)');
  const file = join(dir, 'fake-github', 'demo-app', '.agern', 'sandbox-vm.yml');
  if (content === null) rmSync(file, { force: true });
  else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
}

const pill = (page: Page) => page.locator('.sbx-head .pill');
const runPill = (page: Page) => page.locator('.rh-title .pill');
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const panel = (page: Page) => page.locator('section.svm-pv');

async function startRun(page: Page, title: string, body: string): Promise<string> {
  const issue = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body, kind: 'task' } });
  expect(issue.status()).toBe(201);
  const run = await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: (await issue.json()).number } });
  expect(run.status()).toBe(201);
  return (await run.json()).id as string;
}

test.afterAll(async ({ browser }) => {
  vmFile(null);
  const { context, page } = await userContext(browser, 'alice');
  const previewRun = readState().sandboxVmPreviewRun as string | undefined;
  if (previewRun) {
    await page.request.delete(`${BASE()}/api/runs/${previewRun}/sandbox-vm/preview`, { headers: mutate });
    await page.request.post(`${BASE()}/api/runs/${previewRun}/cancel`, { headers: mutate });
  }
  await page.request.delete(`${BASE()}/api/projects/demo-app/sandbox-vm/approval`, { headers: mutate });
  await context.close();
});

test('a pending file: everyone sees it, a non-admin gets no admin controls', async ({ browser }) => {
  vmFile(VM);
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app`);
  const row = bob.page.locator('.panel--activity .svm-row');
  await expect(row).toContainText('.agern/sandbox-vm.yml');
  await expect(row).toContainText('needs approval');
  await row.click();
  await expect(bob.page).toHaveURL(/\/p\/demo-app\/sandbox-vm$/);
  await expect(bob.page).toHaveTitle('Sandbox VM · demo-app · Agern');
  await expect(pill(bob.page)).toHaveText('needs approval');
  await expect(bob.page.getByText('commands only pretend to run')).toBeVisible();
  const vm = bob.page.locator('.svm-vm');
  await expect(vm).toContainText('e2e-vm');
  await expect(vm).toContainText(`E2E VM ${tag}`);
  await expect(vm).toContainText('agern@vm.example.net:22');
  await expect(vm).toContainText('SHA256:');
  await expect(vm).toContainText('echo checkout');
  await expect(vm).toContainText('echo setup');
  await expect(vm).toContainText('echo ready');
  await expect(bob.page.getByText(/Only an admin can approve, revoke, check or release the VM: .*alice/)).toBeVisible();
  await expect(bob.page.locator('.svm-setup summary')).toHaveText('Recommended VM setup');
  for (const name of ['Approve…', 'Revoke…', 'Generate key', 'Check', 'Release…']) {
    await expect(bob.page.getByRole('button', { name, exact: true })).toHaveCount(0);
  }
  // The server enforces it, not only the UI.
  const status = await (await bob.page.request.get(`${BASE()}/api/projects/demo-app/sandbox-vm`)).json();
  expect(status.state).toBe('pending');
  expect((await bob.page.request.post(`${BASE()}/api/projects/demo-app/sandbox-vm/approval`, { headers: mutate, data: { sha256: status.sha256 } })).status()).toBe(403);
  expect((await bob.page.request.post(`${BASE()}/api/projects/demo-app/sandbox-vm/check`, { headers: mutate })).status()).toBe(403);
  await bob.context.close();
});

test('an admin generates the key, approves the file after a confirmation and checks the VM', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/sandbox-vm`);
  await expect(pill(page)).toHaveText('needs approval');
  // The VM uses the project's deploy key: generate it here unless the deploy spec already did.
  const before = await (await page.request.get(`${BASE()}/api/projects/demo-app/sandbox-vm`)).json();
  if (!before.key) {
    await expect(page.getByText(/^No key yet\./)).toBeVisible();
    await page.getByRole('button', { name: 'Generate key' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Key generated' })).toBeVisible();
  }
  await expect(page.getByLabel('Public key')).toHaveValue(/^ssh-ed25519 \S+ agern-deploy:\S+\/demo-app$/);
  await expect(page.locator('.svm-fp')).toHaveText(/^SHA256:/);
  await expect(page.getByRole('link', { name: 'Deploy page' })).toHaveAttribute('href', '/p/demo-app/deploy');

  // No check before the file is approved.
  await expect(page.getByRole('button', { name: 'Check', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Approve…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Approve the sandbox VM of demo-app?' });
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await expect(dialog).toContainText('agern@vm.example.net:22');
  await expect(dialog).toContainText('SHA256:');
  await expect(dialog).toContainText('echo checkout');
  await expect(dialog).toContainText('echo setup');
  await expect(dialog).toContainText('echo ready');
  await expect(dialog).toContainText('can then deploy its commit to this machine and run any command there as agern');
  await dialog.getByRole('button', { name: 'Approve this version' }).click();
  await expect(dialog).toBeHidden();
  await expect(pill(page)).toHaveText('approved');
  await expect(page.locator('.notice', { hasText: 'Approved' })).toContainText('alice');
  await expect(page.locator('.notice', { hasText: 'Approved' })).toContainText(before.sha256.slice(0, 12));

  await page.getByRole('button', { name: 'Check', exact: true }).click();
  await expect(page.locator('.svm-check-result')).toContainText('Reachable, host key matches, key accepted');
  await expect(page.locator('.svm-lease-text')).toContainText('Free.');

  // The project page's row says the same.
  await page.goto(`${BASE()}/p/demo-app`);
  const row = page.locator('.panel--activity .svm-row');
  await expect(row).toContainText('approved');
  await expect(row).toContainText('e2e-vm (vm.example.net)');
});

test('a run whose builder deploys to the VM shows the deploy and exec blocks and "used ✓"', async ({ page }) => {
  await login(page, 'alice');
  const runId = await startRun(page, `VM build ${tag}`, 'Check it on the VM. fake:sandbox-vm');
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  await expect(runPill(page)).toHaveText('merged', { timeout: 45_000 });
  const status = await (await page.request.get(`${BASE()}/api/projects/demo-app/sandbox-vm`)).json();
  const line = page.locator('.run-svm');
  await expect(line).toContainText('sandbox VM');
  await expect(line).toContainText('e2e-vm');
  await expect(line).toContainText(status.sha256.slice(0, 12));
  await expect(line).toContainText('used ✓');
  await expect(line.getByRole('link', { name: 'Sandbox VM settings of demo-app' })).toHaveAttribute('href', '/p/demo-app/sandbox-vm');
  // The merged run shows no preview panel.
  await expect(panel(page)).toHaveCount(0);

  await node(page, 'builder').click();
  const log = page.locator('.stage-detail .chat-log');
  await expect(log).toContainText('Sandbox VM deploy: {');
  await expect(log).toContainText('Sandbox VM exec: {');
  await expect(log).not.toContainText('Sandbox VM deploy (error)');
  await expect(log).not.toContainText('Sandbox VM exec (error)');
  const checkout = log.locator('.t-svm', { hasText: 'deploy checkout' });
  await expect(checkout).toContainText('sandbox VM');
  await expect(checkout).toContainText('echo checkout');
  await expect(checkout).toContainText('exit 0');
  await expect(log.locator('.t-svm', { hasText: 'deploy setup' })).toContainText('echo setup');
  const cmd = log.locator('.t-svm', { hasText: 'echo checked on the vm' });
  await expect(cmd).toContainText('exit 0');
  await expect(cmd.locator('.t-sbx-out')).toHaveCount(0);
  await cmd.getByRole('button', { name: 'Sandbox VM command on e2e-vm: echo checked on the vm' }).click();
  await expect(cmd.locator('.t-sbx-out')).toContainText('checked on the vm');
  await expect(cmd).toContainText('agern@vm.example.net:22');
});

test('the owner of an escalated run tries it on the VM, others see it, and the owner releases it', async ({ page, browser }) => {
  await login(page, 'alice');
  const runId = await startRun(page, `VM preview ${tag}`, 'The approver will escalate. fake:escalate');
  writeState({ sandboxVmPreviewRun: runId });
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  await expect(runPill(page)).toHaveText('needs human', { timeout: 45_000 });
  await expect(page.locator('.run-svm')).toContainText('e2e-vm');
  await expect(page.locator('.run-svm')).not.toContainText('used ✓');

  const pv = panel(page);
  await expect(pv).toContainText("Deploys this run’s latest commit to e2e-vm (vm.example.net). The VM is shared");
  await pv.getByRole('button', { name: 'Try it on the sandbox VM' }).click();
  await expect(pv.getByRole('status')).toContainText(/Deploying|Deployed/);
  await expect(pv).toContainText(/Deployed [0-9a-f]{7} to e2e-vm\./, { timeout: 20_000 });
  const open = pv.getByRole('link', { name: 'Open https://vm.example.net/ ↗' });
  await expect(open).toHaveAttribute('href', 'https://vm.example.net/');
  await expect(open).toHaveAttribute('target', '_blank');
  await expect(open).toHaveAttribute('rel', 'noreferrer');
  await expect(pv).toContainText('Held until');
  await expect(pv.getByRole('button', { name: 'Deploy again' })).toBeEnabled();

  // Someone who is neither owner nor admin sees the state and the link, but no buttons; the server refuses them too.
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  const bobPv = panel(bob.page);
  await expect(bobPv).toContainText('Deployed');
  await expect(bobPv.getByRole('link', { name: 'Open https://vm.example.net/ ↗' })).toBeVisible();
  await expect(bobPv.getByRole('button')).toHaveCount(0);
  expect((await bob.page.request.delete(`${BASE()}/api/runs/${runId}/sandbox-vm/preview`, { headers: mutate })).status()).toBe(403);
  // The VM page names the holder; only an admin may force-release it.
  await bob.page.goto(`${BASE()}/p/demo-app/sandbox-vm`);
  await expect(bob.page.locator('.svm-lease-text')).toContainText('In use by');
  await expect(bob.page.getByRole('button', { name: 'Release…' })).toHaveCount(0);
  await bob.context.close();

  await pv.getByRole('button', { name: 'Release the VM' }).click();
  await expect(pv).toContainText(/Released (just now|\d+s ago)/);
  await expect(pv.getByRole('button', { name: 'Try it on the sandbox VM' })).toBeVisible();
  await expect(pv.getByRole('link', { name: /^Open / })).toHaveCount(0);
  const run = await (await page.request.get(`${BASE()}/api/runs/${runId}`)).json();
  expect(run.sandboxVm.preview.state).toBe('released');
  await expect
    .poll(async () => (await (await page.request.get(`${BASE()}/api/projects/demo-app/sandbox-vm`)).json()).lease, { timeout: 10_000 })
    .toBeNull();
});
