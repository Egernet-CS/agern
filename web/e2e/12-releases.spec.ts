import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// Release planning (docs/ARCHITECTURE.md §6 "Releases"): create a release, assign issues from the issues panel,
// follow and edit it on the release page, and see other users' changes live.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const issueRows = (page: Page) => page.locator('.panel--issues .issue-list .issue');
const row = (page: Page, title: string) => issueRows(page).filter({ hasText: title });
const releaseRows = (page: Page) => page.locator('.panel--activity .release-list li');

/** Creates an open issue through the API (earlier specs close seeded ones) and returns its number. */
async function newIssue(page: Page, title: string): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: '', kind: 'task' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

async function openProject(page: Page) {
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(issueRows(page).first()).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
}

const name = `rel-${tag}`;
const titles = [`Plan A ${tag}`, `Plan B ${tag}`, `Plan C ${tag}`];
let numbers: number[] = [];

test('create a release and assign issues from the issues panel', async ({ page }) => {
  await login(page, 'alice');
  numbers = [];
  for (const t of titles) numbers.push(await newIssue(page, t));
  await openProject(page);

  await page.getByRole('button', { name: 'New release' }).click();
  const dialog = page.getByRole('dialog', { name: 'New release in demo-app' });
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByLabel(/Description/).fill('Planned in e2e');
  await dialog.getByRole('button', { name: 'Create release' }).click();
  await expect(dialog).toBeHidden();
  const created = releaseRows(page).filter({ hasText: name });
  await expect(created).toHaveCount(1);
  await expect(created).toContainText('in preparation');
  await expect(created).toContainText('0 issues');

  // One issue with the per-issue control.
  await page.getByLabel(`Release for #${numbers[0]}`).selectOption({ label: name });
  await expect(row(page, titles[0]).locator('.release-tag')).toHaveText(name);
  await expect(page.getByLabel(`Release for #${numbers[0]}`)).toBeEnabled();

  // Two at once with the bulk bar.
  await page.getByLabel(`Select #${numbers[1]}`).check();
  await page.getByLabel(`Select #${numbers[2]}`).check();
  const bar = page.getByRole('group', { name: 'Selected issues' });
  await expect(bar).toContainText('2 selected');
  await bar.getByLabel('Assign selected to').selectOption({ label: name });
  await bar.getByRole('button', { name: 'Assign' }).click();
  await expect(bar).toBeHidden();
  for (const t of titles) await expect(row(page, t).locator('.release-tag')).toHaveText(name);
  await expect(created).toContainText('3 issues');
  // Created once, even though the feed frame also arrived.
  await expect(created).toHaveCount(1);

  // Filter by release, and by "No release".
  const filter = page.locator('.panel--issues').getByLabel('Release', { exact: true });
  await filter.selectOption({ label: 'No release' });
  for (const t of titles) await expect(row(page, t)).toHaveCount(0);
  await filter.selectOption({ label: name });
  for (const t of titles) await expect(row(page, t)).toHaveCount(1);
  await expect(issueRows(page)).toHaveCount(3);
  await filter.selectOption({ label: 'All issues' });
  await expect(issueRows(page).filter({ hasNot: page.locator('.release-tag') }).first()).toBeVisible();
});

test("another user's assignment appears without a reload", async ({ page, browser }) => {
  await login(page, 'alice');
  await openProject(page);
  await expect(row(page, titles[0]).locator('.release-tag')).toHaveText(name);

  const bob = await userContext(browser, 'bob');
  const other = `other-${tag}`;
  const r = await bob.page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name: other } });
  expect(r.status()).toBe(201);
  const id = (await r.json()).id as string;
  const moved = await bob.page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, {
    headers: mutate,
    data: { issueNumbers: [numbers[0]], releaseId: id },
  });
  expect(moved.status()).toBe(200);
  await expect(releaseRows(page).filter({ hasText: other })).toHaveCount(1);
  await expect(row(page, titles[0]).locator('.release-tag')).toHaveText(other);
  await expect(releaseRows(page).filter({ hasText: name })).toContainText('2 issues');

  // Back again, for the release page test.
  const back = await bob.page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, {
    headers: mutate,
    data: { issueNumbers: [numbers[0]], releaseId: null },
  });
  expect(back.status()).toBe(200);
  await expect(row(page, titles[0]).locator('.release-tag')).toHaveCount(0);
  await bob.context.close();
});

test('release page: order, add, remove, rename and delete', async ({ page }) => {
  await login(page, 'alice');
  await openProject(page);
  await releaseRows(page).filter({ hasText: name }).getByRole('link').click();
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page).toHaveTitle(`${name} · demo-app · Agern`);
  await expect(page.getByText('This release is a plan. Nothing is built until it is started.')).toBeVisible();
  const items = page.locator('.release-order li');
  await expect(items).toHaveCount(2);
  await expect(items.nth(0)).toContainText(titles[1]);
  await expect(items.nth(1)).toContainText(titles[2]);
  await expect(page.getByRole('button', { name: `Move #${numbers[1]} up` })).toBeDisabled();
  await expect(page.getByRole('button', { name: `Move #${numbers[2]} down` })).toBeDisabled();

  // Add the third issue back (it is last), then move it to the top with the keyboard.
  await page.getByLabel('Add an open issue').selectOption({ label: `#${numbers[0]} ${titles[0]}` });
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(items).toHaveCount(3);
  await expect(items.nth(2)).toContainText(titles[0]);
  await page.getByRole('button', { name: `Move #${numbers[0]} up` }).click();
  await expect(items.nth(1)).toContainText(titles[0]);
  await page.getByRole('button', { name: `Move #${numbers[0]} up` }).focus();
  await page.keyboard.press('Enter');
  await expect(items.nth(0)).toContainText(titles[0]);
  await page.reload();
  await expect(items.nth(0)).toContainText(titles[0]);
  await expect(items.nth(1)).toContainText(titles[1]);
  await expect(items.nth(2)).toContainText(titles[2]);

  // Remove one.
  await page.getByRole('button', { name: `Remove #${numbers[2]} from release` }).click();
  await expect(items).toHaveCount(2);

  // Rename.
  const renamed = `${name}-v2`;
  await page.getByRole('button', { name: 'Edit' }).click();
  const edit = page.getByRole('dialog', { name: 'Edit release' });
  await edit.getByLabel('Name').fill(renamed);
  await edit.getByRole('button', { name: 'Save' }).click();
  await expect(edit).toBeHidden();
  await expect(page.getByRole('heading', { level: 1, name: renamed })).toBeVisible();

  // Delete: back on the project page, the issues are unassigned.
  await page.getByRole('button', { name: 'Delete' }).click();
  const del = page.getByRole('dialog', { name: 'Delete release?' });
  await expect(del).toContainText('Its 2 issues stay open and unassigned.');
  await del.getByRole('button', { name: 'Delete release' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app$/);
  await expect(issueRows(page).first()).toBeVisible();
  await expect(releaseRows(page).filter({ hasText: renamed })).toHaveCount(0);
  for (const t of titles) await expect(row(page, t).locator('.release-tag')).toHaveCount(0);
});
