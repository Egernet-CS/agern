import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// Deploy targets (docs/DEPLOY.md) against the real backend with FACTORY_DEPLOY=fake: a pending file, the project's key,
// approval, per-target checks, a chat agent deploying through the MCP tools, a changed file and key rotation.
test.describe.configure({ mode: 'serial' });

/** An `ssh-ed25519 <base64>` host key line whose key blob is well formed (32 key bytes from [seed]). */
function hostKey(seed: number): string {
  const type = Buffer.from('ssh-ed25519');
  const len = (n: number) => Buffer.from([0, 0, 0, n]);
  const raw = Buffer.alloc(32, seed);
  return `ssh-ed25519 ${Buffer.concat([len(type.length), type, len(32), raw]).toString('base64')}`;
}

const tag = Date.now().toString(36);
// The fake transport fails hosts starting with `badkey` at the host key check.
const v1 = `targets:\n  kiosk:\n    host: kiosk.example.net\n    user: deploy\n    hostKey: ${hostKey(7)}\n    description: Kiosk in the lobby ${tag}\n  broken:\n    host: badkey.example.net\n    port: 2222\n    user: deploy\n    hostKey: ${hostKey(9)}\n`;
const v2 = v1.replace('user: deploy\n    hostKey', 'user: app\n    hostKey');

/** Puts `.agern/deploy.yml` on demo-app's default branch in the fake GitHub (null removes it). */
function deployFile(content: string | null) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (the global setup starts the server)');
  const file = join(dir, 'fake-github', 'demo-app', '.agern', 'deploy.yml');
  if (content === null) rmSync(file, { force: true });
  else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
}

const pill = (page: Page) => page.locator('.sbx-head .pill');
const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const log = (page: Page) => page.locator('.chat-log');
let chatId: string | null = null;

test.afterAll(async ({ browser }) => {
  deployFile(null);
  const { context, page } = await userContext(browser, 'alice');
  await page.request.delete(`${BASE()}/api/projects/demo-app/deploy/approval`, { headers: mutate });
  if (chatId) await page.request.delete(`${BASE()}/api/sessions/${chatId}`, { headers: mutate });
  await context.close();
});

test('a pending file: everyone sees it, a non-admin gets no controls', async ({ browser }) => {
  deployFile(v1);
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app`);
  const row = bob.page.locator('.panel--activity .dpl-row');
  await expect(row).toContainText('Waiting for approval');
  await row.click();
  await expect(bob.page).toHaveURL(/\/p\/demo-app\/deploy$/);
  await expect(bob.page).toHaveTitle('Deploy · demo-app · Agern');
  await expect(pill(bob.page)).toHaveText('needs approval');
  await expect(bob.page.getByText('commands only pretend to run')).toBeVisible();
  const targets = bob.page.locator('.dpl-targets');
  await expect(targets).toContainText('deploy@kiosk.example.net:22');
  await expect(targets).toContainText(`Kiosk in the lobby ${tag}`);
  await expect(targets).toContainText('deploy@badkey.example.net:2222');
  await expect(targets).toContainText('SHA256:');
  await expect(bob.page.getByText('No deploy key yet')).toBeVisible();
  await expect(bob.page.getByText('Only an admin can generate the key.')).toBeVisible();
  for (const name of ['Approve…', 'Revoke…', 'Generate key', 'Rotate key…', /^Check/]) {
    await expect(bob.page.getByRole('button', { name })).toHaveCount(0);
  }
  // The server enforces it, not only the UI.
  const status = await (await bob.page.request.get(`${BASE()}/api/projects/demo-app/deploy`)).json();
  expect(status.state).toBe('pending');
  expect((await bob.page.request.post(`${BASE()}/api/projects/demo-app/deploy/approval`, { headers: mutate, data: { sha256: status.sha256 } })).status()).toBe(403);
  expect((await bob.page.request.post(`${BASE()}/api/projects/demo-app/deploy/key`, { headers: mutate })).status()).toBe(403);
  await bob.context.close();
});

test('an admin generates the key, approves the file and checks the targets', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/deploy`);
  await page.getByRole('button', { name: 'Generate key' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Key generated' })).toBeVisible();
  const pub = page.getByLabel('Public key');
  await expect(pub).toHaveValue(/^ssh-ed25519 \S+ agern-deploy:\S+\/demo-app$/);
  await expect(page.getByRole('button', { name: 'Copy public key' })).toBeVisible();
  await expect(page.locator('.dpl-fp')).toHaveText(/^SHA256:/);
  // The private key is never part of the status.
  const raw = await (await page.request.get(`${BASE()}/api/projects/demo-app/deploy`)).text();
  expect(raw).not.toMatch(/PRIVATE KEY|privateKey/i);

  // No checks before the file is approved.
  await expect(page.getByRole('button', { name: /^Check/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Approve…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Approve the deploy targets of demo-app?' });
  await expect(dialog).toContainText('deploy@kiosk.example.net:22');
  await expect(dialog).toContainText('deploy@badkey.example.net:2222');
  await expect(dialog).toContainText('can then have its agent run any command on these machines');
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await dialog.getByRole('button', { name: 'Approve this version' }).click();
  await expect(dialog).toBeHidden();
  await expect(pill(page)).toHaveText('approved');
  await expect(page.locator('.notice', { hasText: 'Approved' })).toContainText('alice');

  const kiosk = page.locator('.dpl-target', { hasText: 'kiosk.example.net' });
  await kiosk.getByRole('button', { name: 'Check kiosk' }).click();
  await expect(kiosk.getByRole('status')).toContainText('Reachable, host key matches, key accepted');
  const broken = page.locator('.dpl-target', { hasText: 'badkey.example.net' });
  await broken.getByRole('button', { name: 'Check broken' }).click();
  await expect(broken.getByRole('status')).toContainText('Host key does not match');

  // The project page's row says the same.
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(page.locator('.panel--activity .dpl-row')).toContainText('2 targets approved');
});

test('a chat agent deploys through the tools; the transcript shows the command and its output', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await page.getByRole('button', { name: /^(Start chat|\+ New chat)$/ }).first().click();
  await expect(page).toHaveURL(/\?session=/);
  chatId = new URL(page.url()).searchParams.get('session');
  await prompt(page).fill('please fake:deploy it');
  await prompt(page).press('Enter');
  await expect(log(page)).toContainText('Deploy exec: {');
  const block = log(page).locator('.t-deploy').last();
  await expect(block).toContainText('kiosk');
  await expect(block).toContainText('echo deployed');
  await expect(block).toContainText('exit 0');
  await expect(block.locator('.t-sbx-out')).toHaveCount(0);
  await block.getByRole('button', { name: 'Deploy command on kiosk: echo deployed' }).click();
  await expect(block.locator('.t-sbx-out')).toContainText('deployed');
  await expect(block).toContainText('deploy@kiosk.example.net:22');
});

test('a changed file shows the diff, and the tools refuse until it is approved again', async ({ page }) => {
  deployFile(v2);
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/deploy`);
  await expect(pill(page)).toHaveText('changed');
  const diff = page.locator('.sbx-diff');
  await expect(diff.locator('.sbx-dl--del').first()).toContainText('user: deploy');
  await expect(diff.locator('.sbx-dl--add').first()).toContainText('user: app');
  await expect(page.getByRole('button', { name: /^Check/ })).toHaveCount(0);

  // The agent's exec is refused while the change is not approved.
  await page.goto(`${BASE()}/p/demo-app?session=${chatId}`);
  await prompt(page).fill('again fake:deploy please');
  await prompt(page).press('Enter');
  await expect(log(page)).toContainText(/Deploy exec \(error\): .*approv/i);

  await page.goto(`${BASE()}/p/demo-app/deploy`);
  await page.getByRole('button', { name: 'Approve…' }).click();
  await page.getByRole('dialog', { name: 'Approve the deploy targets of demo-app?' }).getByRole('button', { name: 'Approve this version' }).click();
  await expect(pill(page)).toHaveText('approved');
  await expect(page.locator('.sbx-diff')).toHaveCount(0);
});

test('rotating the key changes the fingerprint after a confirmation', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/deploy`);
  const fp = page.locator('.dpl-fp');
  await expect(fp).toHaveText(/^SHA256:/);
  const before = await fp.innerText();
  await page.getByRole('button', { name: 'Rotate key…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Rotate the deploy key of demo-app?' });
  await expect(dialog).toContainText('the old one is deleted at once');
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await dialog.getByRole('button', { name: 'Rotate key' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('status').filter({ hasText: 'Key rotated' })).toBeVisible();
  await expect(fp).not.toHaveText(before);
  await expect(fp).toHaveText(/^SHA256:/);
});
