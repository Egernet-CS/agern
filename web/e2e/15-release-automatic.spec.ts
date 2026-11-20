import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials, userContext } from './support';

// Release mode (docs/ARCHITECTURE.md §6 "Releasing", "Release mode", "Checks"): a release set to automatic is merged by
// Agern, as its owner, once it is ready, complete, clean and green; a manual one waits for the owner's approval. The
// fake GitHub reads the checks of a branch's head from <dataDir>/fake-github-checks/<repo>/<branch>.json.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');
const BUILD = { timeout: 90_000 };

function setChecks(branch: string, statuses: Record<string, string>) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (run through npm run e2e)');
  const file = join(dir, 'fake-github-checks', 'demo-app', `${branch}.json`);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ statuses }));
}

async function newIssue(page: Page, title: string): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: 'Built for a release.', kind: 'task' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

async function assignAndStart(page: Page, id: string, numbers: number[]) {
  expect((await page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, { headers: mutate, data: { issueNumbers: numbers, releaseId: id } })).status()).toBe(200);
  const started = await page.request.post(`${BASE()}/api/releases/${id}/start`, { headers: mutate });
  expect(started.status(), await started.text()).toBe(200);
}

async function openRelease(page: Page, id: string, name: string) {
  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
}

test('automatic release: chosen when it is created, then released by Agern without anyone approving it', async ({ page }) => {
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  const name = `auto-${tag}`;
  const number = await newIssue(page, `Released automatically ${tag}`);

  await page.goto(`${BASE()}/p/demo-app`);
  await page.getByRole('button', { name: 'New release' }).click();
  const dialog = page.getByRole('dialog', { name: 'New release in demo-app' });
  await expect(dialog.getByRole('radio', { name: /Manual approval/ })).toBeChecked();
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('radio', { name: /Release automatically/ }).check();
  await dialog.getByRole('button', { name: 'Create release' }).click();
  await expect(dialog).toBeHidden();
  const row = page.locator('.panel--activity').locator('li', { hasText: name });
  await expect(row).toContainText('0 issues · automatic');

  const releases = (await (await page.request.get(`${BASE()}/api/projects/demo-app/releases`)).json()) as { id: string; name: string; mode: string }[];
  const created = releases.find((r) => r.name === name)!;
  expect(created.mode).toBe('automatic');
  await assignAndStart(page, created.id, [number]);

  // Nobody approves: built, clean, no checks (green) — Agern merges it for its owner.
  await openRelease(page, created.id, name);
  await expect(pill(page)).toHaveText('released', BUILD);
  const facts = page.locator('.release-released');
  await expect(facts).toContainText('Released automatically');
  await expect(facts).toContainText('for alice');
  await expect(facts).toContainText('Rebased onto main, one commit per issue');
  const issue = await (await page.request.get(`${BASE()}/api/projects/demo-app/issues/${number}`)).json();
  expect(issue.state).toBe('closed');
  const after = await (await page.request.get(`${BASE()}/api/releases/${created.id}`)).json();
  expect(after.releasedAutomatically).toBe(true);
  expect(after.releasedBy.username).toBe('alice');
  // A released release's mode cannot change.
  const late = await page.request.put(`${BASE()}/api/releases/${created.id}/mode`, { headers: mutate, data: { mode: 'manual' } });
  expect(late.status()).toBe(409);
});

test('checks gate the release: pending waits, a failure holds it, green releases it; only the owner switches it to automatic', async ({ page, browser }) => {
  test.setTimeout(180_000);
  await login(page, 'alice');
  const name = `gate-${tag}`;
  setChecks(`release/${name}`, { 'ci/e2e': 'pending' });
  const created = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  await assignAndStart(page, id, [await newIssue(page, `Gated by checks ${tag}`)]);

  await openRelease(page, id, name);
  await expect(pill(page)).toHaveText('ready to release', BUILD);
  const section = page.getByRole('region', { name: 'Release pull request' });
  const checks = section.locator('.release-checks');
  await expect(checks).toContainText('checks pending');
  await expect(checks).toContainText('Pending: ci/e2e');
  await expect(page.getByLabel('Release mode')).toHaveValue('manual');

  // Manual: approval is not blocked by the checks, but the dialog says what it would skip.
  await section.getByRole('button', { name: 'Approve and release' }).click();
  const confirm = page.getByRole('dialog', { name: `Release ${name}?` });
  await expect(confirm).toContainText('Some checks have not finished yet (Pending: ci/e2e). Approving merges it without waiting for them.');
  await confirm.getByRole('button', { name: 'Cancel' }).click();
  await expect(confirm).toBeHidden();

  // Bob sees the mode but may not change it, on the page or through the API.
  const bob = await userContext(browser, 'bob');
  await openRelease(bob.page, id, name);
  await expect(bob.page.getByText('Release mode: Manual approval')).toBeVisible();
  await expect(bob.page.getByLabel('Release mode')).toHaveCount(0);
  const refused = await bob.page.request.put(`${BASE()}/api/releases/${id}/mode`, { headers: mutate, data: { mode: 'automatic' } });
  expect(refused.status()).toBe(403);
  await bob.context.close();

  // Alice switches it to automatic: it waits for the checks.
  await page.getByLabel('Release mode').selectOption('automatic');
  await expect(page.locator('#release-mode-hint')).toContainText("Agern merges it with alice's GitHub token");
  await expect(section.locator('.release-auto')).toHaveText('Set to release automatically: Agern merges it once all checks have passed.');
  await expect(pill(page)).toHaveText('ready to release');

  // A failed check holds it, with a notice.
  setChecks(`release/${name}`, { 'ci/e2e': 'failure' });
  await section.getByRole('button', { name: 'Check again' }).click();
  await expect(checks).toContainText('checks failed');
  await expect(page.locator('.release-notice')).toContainText('Checks failed on pull request #');
  await expect(pill(page)).toHaveText('ready to release');

  // Green: released by Agern for alice.
  setChecks(`release/${name}`, { 'ci/e2e': 'success' });
  await section.getByRole('button', { name: 'Check again' }).click();
  await expect(pill(page)).toHaveText('released');
  await expect(page.locator('.release-released')).toContainText('Released automatically');
});
