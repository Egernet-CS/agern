import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// Building a release (docs/ARCHITECTURE.md §6 "Releases"): start it, follow its issues as they are built one at a time
// on release/<name>, hold, approve, pause, resume and cancel. One release per project can build, so every test ends
// by cancelling its release.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');
const statusLine = (page: Page) => page.locator('.release-status');
const item = (page: Page, title: string) => page.locator('.release-order li', { hasText: title });
const stateOf = (page: Page, title: string) => item(page, title).locator('.issue-state');
const BUILD = { timeout: 90_000 };

/** Creates an open issue through the API; the body steers the fake CLI (fake:slow, fake:escalate, fake:hang-builder). */
async function newIssue(page: Page, title: string, body: string): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body, kind: 'task' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

/** Creates a planned release with the issues in this order; returns its id. */
async function newRelease(page: Page, name: string, numbers: number[]): Promise<string> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  expect(r.status()).toBe(201);
  const id = (await r.json()).id as string;
  const a = await page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, { headers: mutate, data: { issueNumbers: numbers, releaseId: id } });
  expect(a.status()).toBe(200);
  return id;
}

async function startRelease(page: Page, id: string) {
  const r = await page.request.post(`${BASE()}/api/releases/${id}/start`, { headers: mutate });
  expect(r.status(), await r.text()).toBe(200);
}

async function cancelRelease(page: Page, id: string) {
  const r = await page.request.post(`${BASE()}/api/releases/${id}/cancel`, { headers: mutate });
  expect([200, 409]).toContain(r.status());
  expect((await (await page.request.get(`${BASE()}/api/releases/${id}`)).json()).status).toBe('cancelled');
}

async function openRelease(page: Page, id: string, name: string) {
  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
}

test('start a release: its issues are built one at a time and merged into the release branch', async ({ page }) => {
  await login(page, 'alice');
  const name = `build-${tag}`;
  const titles = [`Release first ${tag}`, `Release second ${tag}`];
  const numbers = [await newIssue(page, titles[0], 'First of two. fake:slow'), await newIssue(page, titles[1], 'Second of two. fake:slow')];
  const id = await newRelease(page, name, numbers);
  await openRelease(page, id, name);
  await expect(pill(page)).toHaveText('in preparation');
  await expect(page.getByText('This release is a plan. Nothing is built until it is started.')).toBeVisible();

  await page.getByRole('button', { name: '▶ Start release' }).click();
  const dialog = page.getByRole('dialog', { name: 'Start release?' });
  await expect(dialog).toContainText(`Agern creates the branch release/${name} from`);
  await expect(dialog).toContainText('builds its 2 issues one at a time');
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await dialog.getByRole('button', { name: 'Start release' }).click();
  await expect(dialog).toBeHidden();

  await expect(pill(page)).toHaveText('building');
  await expect(page.getByText('This release is a plan. Nothing is built until it is started.')).toHaveCount(0);
  await expect(page.locator('.release-about')).toContainText('started by alice');
  const branch = page.getByRole('link', { name: `release/${name}` });
  await expect(branch).toHaveAttribute('href', new RegExp(`/tree/release/${name}$`));
  await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();

  // No reloads from here on: the release feed frames carry every change.
  await expect(stateOf(page, titles[0])).toHaveText('running', BUILD);
  await expect(stateOf(page, titles[1])).toHaveText('waiting');
  await expect(statusLine(page)).toHaveText(`Building #${numbers[0]} ${titles[0]}.`);
  await expect(stateOf(page, titles[0])).toHaveText('merged', BUILD);
  await expect(stateOf(page, titles[1])).toHaveText('merged', BUILD);
  await expect(page.locator('.release-progress')).toHaveText('2 of 2 merged');
  await expect(statusLine(page)).toHaveText('Nothing left to build.');
  await expect(pill(page)).toHaveText('building');

  // The run merged into the release branch.
  await item(page, titles[0]).getByRole('link', { name: 'run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await expect(page.locator('.pipeline g[aria-label^="merge to release: done, verdict MERGED"]')).toBeVisible();
  await expect(page.locator('.rh-meta').getByRole('link', { name: `release/${name}` })).toHaveAttribute('href', `/p/demo-app/releases/${id}`);

  // Both issues stay open until the release is merged.
  await page.goto(`${BASE()}/p/demo-app`);
  const rows = page.locator('.panel--issues .issue-list .issue');
  await expect(rows.first()).toBeVisible();
  for (const t of titles) await expect(rows.filter({ hasText: t })).toHaveCount(1);
  await expect(page.locator('.panel--activity .release-list li', { hasText: name })).toContainText('2 of 2 merged');

  await cancelRelease(page, id);
});

test('a failing issue is held and the build goes on; approving its run merges it', async ({ page }) => {
  await login(page, 'alice');
  const name = `held-${tag}`;
  const titles = [`Release escalates ${tag}`, `Release passes ${tag}`];
  const numbers = [await newIssue(page, titles[0], 'The approver will escalate. fake:escalate'), await newIssue(page, titles[1], 'Plain.')];
  const id = await newRelease(page, name, numbers);
  await startRelease(page, id);
  await openRelease(page, id, name);

  await expect(stateOf(page, titles[0])).toHaveText('held', BUILD);
  await expect(item(page, titles[0]).locator('.ro-note')).toContainText('The approver escalated');
  await expect(stateOf(page, titles[1])).toHaveText('merged', BUILD);
  await expect(page.locator('.release-progress')).toHaveText('1 of 2 merged · 1 held');
  await expect(statusLine(page)).toHaveText('Nothing left to build. 1 held issue needs attention.');
  await expect(page.getByRole('button', { name: `Retry #${numbers[0]}` })).toBeVisible();

  await item(page, titles[0]).getByRole('link', { name: 'run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await page.getByRole('button', { name: '✓ Approve & merge' }).click();
  await expect(pill(page)).toHaveText('merged');

  await openRelease(page, id, name);
  await expect(stateOf(page, titles[0])).toHaveText('merged');
  await expect(page.locator('.release-progress')).toHaveText('2 of 2 merged');

  await cancelRelease(page, id);
});

test('pause lets the running issue finish and starts no other; resume goes on', async ({ page }) => {
  await login(page, 'alice');
  const name = `pause-${tag}`;
  const titles = [`Release paused first ${tag}`, `Release paused second ${tag}`];
  const numbers = [await newIssue(page, titles[0], 'fake:slow'), await newIssue(page, titles[1], 'fake:slow')];
  const id = await newRelease(page, name, numbers);
  await startRelease(page, id);
  await openRelease(page, id, name);

  await expect(stateOf(page, titles[0])).toHaveText('running', BUILD);
  await page.getByRole('button', { name: 'Pause' }).click();
  await expect(pill(page)).toHaveText('paused');
  await expect(statusLine(page)).toContainText('Paused by alice');
  await expect(page.getByRole('button', { name: 'Pause' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Resume' })).toBeVisible();

  await expect(stateOf(page, titles[0])).toHaveText('merged', BUILD);
  await expect(stateOf(page, titles[1])).toHaveText('waiting');
  await expect(pill(page)).toHaveText('paused');
  // Still waiting a little later: nothing starts while paused.
  await page.waitForTimeout(2_000);
  await expect(stateOf(page, titles[1])).toHaveText('waiting');

  await page.getByRole('button', { name: 'Resume' }).click();
  await expect(pill(page)).toHaveText('building');
  await expect(stateOf(page, titles[1])).toHaveText('merged', BUILD);
  await expect(page.locator('.release-progress')).toHaveText('2 of 2 merged');

  await cancelRelease(page, id);
});

test('cancel stops the running issue; another release can then be started', async ({ page }) => {
  await login(page, 'alice');
  const name = `cancel-${tag}`;
  const title = `Release hangs ${tag}`;
  const number = await newIssue(page, title, 'The builder never finishes. fake:hang-builder');
  const id = await newRelease(page, name, [number]);
  await startRelease(page, id);
  await openRelease(page, id, name);
  await expect(stateOf(page, title)).toHaveText('running', BUILD);

  await page.getByRole('button', { name: 'Cancel release' }).click();
  const dialog = page.getByRole('dialog', { name: 'Cancel release?' });
  await expect(dialog).toContainText(`Cancel release “${name}”? The running issue is stopped and no more are built.`);
  await expect(dialog.getByRole('button', { name: 'Keep building' })).toBeFocused();
  await dialog.getByRole('button', { name: 'Cancel release' }).click();
  await expect(dialog).toBeHidden();

  await expect(pill(page)).toHaveText('cancelled');
  await expect(stateOf(page, title)).toHaveText('held');
  await expect(item(page, title).locator('.ro-note')).toHaveText('Cancelled with the release');
  await expect(statusLine(page)).toContainText(`What was merged stays on release/${name}.`);
  await expect(page.getByRole('button', { name: 'Delete' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Pause' })).toHaveCount(0);
  await expect(page.getByLabel('Add an open issue')).toHaveCount(0);

  // The project is free for another release.
  const next = `after-${tag}`;
  const nextId = await newRelease(page, next, [await newIssue(page, `Release after cancel ${tag}`, 'fake:slow')]);
  await startRelease(page, nextId);
  await cancelRelease(page, nextId);
});

test("only the owner and admins can change a started release", async ({ page, browser }) => {
  await login(page, 'alice');
  const name = `perm-${tag}`;
  const title = `Release of alice ${tag}`;
  const id = await newRelease(page, name, [await newIssue(page, title, 'fake:hang-builder')]);
  await startRelease(page, id);

  const bob = await userContext(browser, 'bob');
  await openRelease(bob.page, id, name);
  await expect(pill(bob.page)).toHaveText('building');
  await expect(bob.page.getByText('Only alice, who started this release, and admins can change it.')).toBeVisible();
  for (const label of ['Pause', 'Resume', 'Cancel release', 'Edit', '▶ Start release']) {
    await expect(bob.page.getByRole('button', { name: label })).toHaveCount(0);
  }
  await expect(bob.page.getByLabel('Add an open issue')).toHaveCount(0);
  const r = await bob.page.request.post(`${BASE()}/api/releases/${id}/pause`, { headers: mutate });
  expect(r.status()).toBe(403);
  expect((await r.json()).error.code).toBe('forbidden');
  await bob.context.close();

  await cancelRelease(page, id);
});
