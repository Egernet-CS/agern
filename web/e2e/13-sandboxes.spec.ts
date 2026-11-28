import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// Sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes", §10) against the real backend with FACTORY_SANDBOX=fake: approving a
// definition, a run's sandbox line and command blocks, a changed definition, and an admin stopping a sandbox.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const v1 = `image: docker.io/library/eclipse-temurin:21-jdk\nsetup: |\n  echo installing the toolchain ${tag}\nservices:\n  postgres:\n    image: docker.io/library/postgres:17\n    env: { POSTGRES_PASSWORD: dev }\ncaches: [ /root/.gradle ]\n`;
const v2 = v1.replace('eclipse-temurin:21-jdk', 'eclipse-temurin:25-jdk') + 'resources: { cpus: 1, memory: 2g }\n';
// Step 4: a platform (inheriting the setup), a non-root user and a readiness check on the service.
const v3 = v2.replace('    env: { POSTGRES_PASSWORD: dev }\n', '    env: { POSTGRES_PASSWORD: dev }\n    ready: { command: "pg_isready -h 127.0.0.1", timeoutSeconds: 30 }\n') +
  'user: builder\nplatforms:\n  jdk17:\n    image: docker.io/library/eclipse-temurin:17-jdk\n';

/** Puts `.agern/sandbox.yml` on demo-app's default branch in the fake GitHub (null removes it). */
function definition(content: string | null) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (the global setup starts the server)');
  const file = join(dir, 'fake-github', 'demo-app', '.agern', 'sandbox.yml');
  if (content === null) rmSync(file, { force: true });
  else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
}

const pill = (page: Page) => page.locator('.sbx-head .pill');
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);

async function startRun(page: Page, title: string, body: string): Promise<string> {
  const issue = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body, kind: 'task' } });
  expect(issue.status()).toBe(201);
  const run = await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: (await issue.json()).number } });
  expect(run.status()).toBe(201);
  return (await run.json()).id as string;
}

test.afterAll(async ({ browser }) => {
  definition(null);
  const { context, page } = await userContext(browser, 'alice');
  await page.request.delete(`${BASE()}/api/projects/demo-app/sandbox/approval`, { headers: mutate });
  await context.close();
});

test('a pending definition: everyone sees it, only an admin approves it, after a confirmation', async ({ page, browser }) => {
  definition(v1);
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app/sandbox`);
  await expect(pill(bob.page)).toHaveText('needs approval');
  await expect(bob.page.locator('.sbx-dl-grid')).toContainText('docker.io/library/eclipse-temurin:21-jdk');
  await expect(bob.page.locator('.sbx-dl-grid')).toContainText('postgres');
  await expect(bob.page.getByText(/Only an admin can approve or revoke: .*alice/)).toBeVisible();
  await expect(bob.page.getByRole('button', { name: 'Approve…' })).toHaveCount(0);
  // The server enforces it, not only the UI.
  const status = await (await bob.page.request.get(`${BASE()}/api/projects/demo-app/sandbox`)).json();
  const denied = await bob.page.request.post(`${BASE()}/api/projects/demo-app/sandbox/approval`, { headers: mutate, data: { sha256: status.sha256 } });
  expect(denied.status()).toBe(403);

  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  const row = page.locator('.panel--activity .sbx-row');
  await expect(row).toContainText('needs approval');
  await row.click();
  await expect(pill(page)).toHaveText('needs approval');
  await page.getByRole('button', { name: 'Approve…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Approve the sandbox of demo-app?' });
  await expect(dialog).toContainText('will run on the factory host');
  await expect(dialog).toContainText('docker.io/library/postgres:17');
  await expect(dialog).toContainText(`echo installing the toolchain ${tag}`);
  await dialog.getByRole('button', { name: 'Approve this version' }).click();
  await expect(dialog).toBeHidden();
  await expect(pill(page)).toHaveText('approved');
  await expect(page.locator('.notice', { hasText: 'Approved' })).toContainText(status.sha256.slice(0, 12));

  // Bob's open page follows over the feed, without a reload.
  await expect(pill(bob.page)).toHaveText('approved');
  await bob.context.close();
});

test('a run with an approved sandbox shows its image and the commands it ran', async ({ page }) => {
  await login(page, 'alice');
  const runId = await startRun(page, `Sandboxed build ${tag}`, 'Build it in the sandbox. fake:sandbox');
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 45_000 });
  const line = page.locator('.run-sbx');
  await expect(line).toContainText('docker.io/library/eclipse-temurin:21-jdk');
  const status = await (await page.request.get(`${BASE()}/api/projects/demo-app/sandbox`)).json();
  await expect(line).toContainText(status.sha256.slice(0, 12));
  // Sandbox mode: no host marker, and the command the builder ran under a closed disclosure.
  await expect(line).not.toContainText('build on host');
  const cmds = page.locator('details.run-sbx-cmds');
  await expect(cmds.locator('summary')).toHaveText('1 sandbox command');

  await node(page, 'builder').click();
  const log = page.locator('.stage-detail .chat-log');
  const setup = log.locator('.t-sbx', { hasText: 'setup script' });
  await expect(setup).toBeVisible();
  const cmd = log.locator('.t-sbx', { hasText: 'echo built in the sandbox && ls' });
  await expect(cmd).toContainText('exit 0');
  await expect(cmd.locator('.t-sbx-out')).toHaveCount(0);
  await cmd.getByRole('button', { name: /Sandbox command: .*echo built in the sandbox/ }).click();
  await expect(cmd.locator('.t-sbx-out')).toContainText('built in the sandbox');
  await expect(cmd.locator('.t-sbx-out')).toContainText('README.md');
  await setup.getByRole('button', { name: /Sandbox setup script/ }).click();
  await expect(setup.locator('.t-sbx-out')).toContainText(`installing the toolchain ${tag}`);
});

test('the ux and ui-design reviewers run the app in a read-only review sandbox, shown on the run page', async ({ page }) => {
  await login(page, 'alice');
  const runId = await startRun(page, `Review sandbox ${tag}`, 'Look at it in the review sandbox. fake:review-sandbox');
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 45_000 });
  const review = page.locator('.run-sbx .run-sbx-review');
  await expect(review).toHaveText('review sandbox ✓');
  await expect(review).toHaveAttribute('title', 'The ux and ui-design reviewers ran the app in a read-only review sandbox');

  await node(page, 'ux').click();
  const log = page.locator('.stage-detail .chat-log');
  const cmd = log.locator('.t-sbx', { hasText: 'echo reviewed in the sandbox && ls' });
  await expect(cmd).toContainText('exit 0');
  await expect(cmd.locator('.t-sbx-review')).toHaveText('review');
  await cmd.getByRole('button', { name: /Review sandbox command: .*echo reviewed in the sandbox/ }).click();
  await expect(cmd.locator('.t-sbx-out')).toContainText('reviewed in the sandbox');
  await expect(cmd.locator('.t-sbx-out')).toContainText('README.md');
});

test('a changed definition shows the diff and needs a new approval', async ({ page }) => {
  definition(v2);
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/sandbox`);
  await expect(pill(page)).toHaveText('changed');
  const diff = page.locator('.sbx-diff');
  await expect(diff.locator('.sbx-dl--del')).toContainText('eclipse-temurin:21-jdk');
  await expect(diff.locator('.sbx-dl--add').first()).toContainText('eclipse-temurin:25-jdk');
  await expect(diff.locator('.sbx-dl--add')).toHaveCount(2);
  // A run started now does not get the changed version.
  const st = await (await page.request.get(`${BASE()}/api/projects/demo-app/sandbox`)).json();
  expect(st.state).toBe('pending');
  expect(st.approval.sha256).not.toBe(st.sha256);

  await page.getByRole('button', { name: 'Approve…' }).click();
  await page.getByRole('dialog', { name: 'Approve the sandbox of demo-app?' }).getByRole('button', { name: 'Approve this version' }).click();
  await expect(pill(page)).toHaveText('approved');
  await expect(page.locator('.sbx-diff')).toHaveCount(0);
});

test('an admin stops a running sandbox; the run goes on', async ({ page }) => {
  await login(page, 'alice');
  const title = `Sandbox to stop ${tag}`;
  const runId = await startRun(page, title, 'Hang in review so the sandbox stays. fake:sandbox fake:hang-security');
  await expect
    .poll(async () => ((await (await page.request.get(`${BASE()}/api/admin/sandboxes`)).json()).sandboxes as { runId: string }[]).map((s) => s.runId), { timeout: 45_000 })
    .toContain(runId);

  await page.goto(`${BASE()}/admin`);
  const card = page.locator('section', { has: page.locator('#sb-h') });
  const item = card.locator('.sbx-run', { hasText: title });
  await expect(item).toContainText('eclipse-temurin:25-jdk');
  await expect(item).toContainText('postgres');
  await expect(card).toContainText(/[12] of 2 containers in use/);
  await item.getByRole('button', { name: /^Stop the sandbox of run #\d+ in demo-app$/ }).click();
  const dialog = page.getByRole('dialog', { name: /Stop the sandbox of run #\d+\?/ });
  await expect(dialog).toContainText('The run itself continues');
  await dialog.getByRole('button', { name: 'Stop sandbox' }).click();
  await expect(dialog).toBeHidden();
  await expect(item).toHaveCount(0);

  // The run is still active; its builder's transcript says what happened.
  const run = await (await page.request.get(`${BASE()}/api/runs/${runId}`)).json();
  expect(run.status).toBe('reviewing');
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}?stage=builder`);
  await expect(page.locator('.stage-detail .chat-log')).toContainText("An admin (alice) stopped this run's sandbox");
  expect((await page.request.post(`${BASE()}/api/runs/${runId}/cancel`, { headers: mutate })).status()).toBe(200);
});

test('platforms: on the sandbox page, in the approval, on the run and live in the admin overview', async ({ page }) => {
  definition(v3);
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/sandbox`);
  await expect(pill(page)).toHaveText('changed');
  const grid = page.locator('.sbx-dl-grid');
  await expect(grid).toContainText('jdk17');
  await expect(grid).toContainText('docker.io/library/eclipse-temurin:17-jdk');
  await expect(grid).toContainText('setup: the top-level script');
  await expect(grid).toContainText(/commands run as builder \(uid 1000\); setup runs as root/);
  await expect(grid).toContainText('ready when pg_isready -h 127.0.0.1 succeeds (waits up to 30 s)');
  await expect(page.locator('.sbx-diff .sbx-dl--add', { hasText: 'jdk17:' })).toHaveCount(1);
  await page.getByRole('button', { name: 'Approve…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Approve the sandbox of demo-app?' });
  await expect(dialog.locator('.sbx-images li')).toHaveText([
    'default docker.io/library/eclipse-temurin:25-jdk',
    'platform jdk17 docker.io/library/eclipse-temurin:17-jdk',
    'service postgres docker.io/library/postgres:17',
  ]);
  await expect(dialog).toContainText('Setup script of the default image and jdk17');
  await dialog.getByRole('button', { name: 'Approve this version' }).click();
  await expect(pill(page)).toHaveText('approved');

  // The admin overview is open before the run starts: the sandbox appears and goes without a reload (the poll is slow).
  await page.goto(`${BASE()}/admin`);
  const card = page.locator('section', { has: page.locator('#sb-h') });
  await expect(card).toContainText('0 of 2 containers in use');
  const title = `Platform build ${tag}`;
  const runId = await startRun(page, title, 'Test on every platform. fake:sandbox fake:platform=jdk17 fake:hang-security');
  const item = card.locator('.sbx-run', { hasText: title });
  await expect(item).toBeVisible({ timeout: 45_000 });
  await expect(item.locator('.sbx-run-containers li')).toHaveText(['default eclipse-temurin:25-jdk', 'jdk17 eclipse-temurin:17-jdk'], { timeout: 20_000 });
  await expect(item).toContainText('2 slots · 1 CPU · 2g per container · up to 6g in all');
  await expect(card).toContainText('2 of 2 containers in use');

  // The run page: the sandbox line names the platform used; the command block says where it ran.
  const run = await page.context().newPage();
  await run.goto(`${BASE()}/p/demo-app/runs/${runId}?stage=builder`);
  await expect(run.locator('.run-sbx')).toContainText('platforms');
  await expect(run.locator('.run-sbx .run-sbx-plat--used')).toHaveText('✓ jdk17');
  const blocks = run.locator('.stage-detail .chat-log .t-sbx', { hasText: 'echo built in the sandbox && ls' });
  await expect(blocks).toHaveCount(2);
  await expect(blocks.nth(0).locator('.t-sbx-plat')).toHaveText('default');
  await expect(blocks.nth(1).locator('.t-sbx-plat')).toHaveText('jdk17');
  await expect(run.locator('.stage-detail .chat-log .t-sbx', { hasText: 'setup script' }).locator('.t-sbx-plat')).toHaveText(['default', 'jdk17']);
  await run.close();

  // Cancelling the run removes the sandbox; the overview follows live.
  expect((await page.request.post(`${BASE()}/api/runs/${runId}/cancel`, { headers: mutate })).status()).toBe(200);
  await expect(item).toHaveCount(0, { timeout: 10_000 });
  await expect(card).toContainText('0 of 2 containers in use');
});

// Last, so the earlier definitions are undisturbed; afterAll removes the definition and the approval.
const host = v1 + 'build: host\n';

test('build: host — the sandbox page, a run that needs no sandbox and one that uses it', async ({ page }) => {
  definition(host);
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/sandbox`);
  await expect(pill(page)).toHaveText('changed');
  const grid = page.locator('.sbx-dl-grid');
  await expect(grid).toContainText('on the host');
  await page.getByRole('button', { name: 'Approve…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Approve the sandbox of demo-app?' });
  await expect(dialog).toContainText('Build mode host: builds and plain tests run on the factory host');
  await dialog.getByRole('button', { name: 'Approve this version' }).click();
  await expect(pill(page)).toHaveText('approved');

  // A run whose builder runs nothing in the sandbox.
  const plain = await startRun(page, `Host build ${tag}`, 'Build it on the host.');
  await page.goto(`${BASE()}/p/demo-app/runs/${plain}`);
  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 45_000 });
  const line = page.locator('.run-sbx');
  await expect(line.locator('.run-sbx-host')).toHaveText('build on host');
  await expect(line).toContainText('sandbox not used');
  await expect(page.locator('details.run-sbx-cmds')).toHaveCount(0);
  const run = await (await page.request.get(`${BASE()}/api/runs/${plain}`)).json();
  expect(run.sandbox).toMatchObject({ state: 'approved', build: 'host', commandCount: 0 });

  // A run whose builder needs the sandbox for one command.
  const used = await startRun(page, `Host build with a sandbox command ${tag}`, 'Run the service tests in the sandbox. fake:sandbox');
  await page.goto(`${BASE()}/p/demo-app/runs/${used}`);
  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 45_000 });
  await expect(page.locator('.run-sbx')).toContainText('build on host');
  await expect(page.locator('.run-sbx')).not.toContainText('sandbox not used');
  const cmds = page.locator('details.run-sbx-cmds');
  const summary = cmds.locator('summary');
  await expect(summary).toHaveText('sandbox used for 1 command');
  await expect(cmds.locator('li')).toBeHidden();
  await summary.focus();
  await page.keyboard.press('Enter');
  await expect(cmds.locator('li code')).toHaveText(['echo built in the sandbox && ls']);
});
