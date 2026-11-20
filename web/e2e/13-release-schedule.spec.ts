import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials, userContext } from './support';

// Scheduled start of a release (docs/ARCHITECTURE.md §6 "Releases", "Scheduled start"): set, change and cancel it
// from the release page, see it on the project page and live for another user, who may not change it.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const name = `sched-${tag}`;
let id = '';

const releaseRows = (page: Page) => page.locator('.panel--activity .release-list li');
const pad = (n: number) => String(n).padStart(2, '0');
/** A `datetime-local` value for tomorrow at hh:mm local time. */
function tomorrowAt(h: number, m: number): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(h)}:${pad(m)}`;
}

async function openRelease(page: Page) {
  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
}

test('schedule a release from its page', async ({ page }) => {
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  expect(r.status()).toBe(201);
  id = (await r.json()).id as string;
  await openRelease(page);
  await expect(page.getByText('This release is a plan. Nothing is built until it is started.')).toBeVisible();

  await page.getByRole('button', { name: 'Schedule start…' }).click();
  const dialog = page.getByRole('dialog', { name: `Schedule release ${name}` });
  const field = dialog.getByLabel('Start at');
  await expect(field).toHaveValue(/T22:00$/);
  await expect(field).toHaveAccessibleDescription(/^Your local time \(.+\)\. The release is built with your Claude and GitHub credentials\./);
  const value = tomorrowAt(21, 30);
  await field.fill(value);
  await dialog.getByRole('button', { name: 'Schedule' }).click();
  await expect(dialog).toBeHidden();

  await expect(page.locator('.release-head .pill')).toHaveText('scheduled');
  await expect(page.getByText(/^Scheduled to start .+ by alice\.$/)).toBeVisible();
  await expect(page.locator('.release-schedule time')).toHaveAttribute('datetime', new Date(value).toISOString());
  await expect(page.getByRole('button', { name: 'Delete' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Delete' })).toHaveAccessibleDescription('Cancel the scheduled start to delete this release.');
  await page.reload();
  await expect(page.locator('.release-head .pill')).toHaveText('scheduled');
  await expect(page.getByText(/^Scheduled to start .+ by alice\.$/)).toBeVisible();

  await page.goto(`${BASE()}/p/demo-app`);
  const row = releaseRows(page).filter({ hasText: name });
  await expect(row).toContainText('scheduled');
  await expect(row).toContainText('· starts');
});

test('the owner changes the time; another user sees it live and may not change it', async ({ page, browser }) => {
  await login(page, 'alice');
  const bob = await userContext(browser, 'bob');
  await registerCredentials(bob.page, 'bob');
  await openRelease(bob.page);
  await expect(bob.page.getByText(/^Scheduled to start .+ by alice\.$/)).toBeVisible();
  await expect(bob.page.getByText('Only alice or an admin can change the schedule.')).toBeVisible();
  await expect(bob.page.getByRole('button', { name: 'Change time' })).toHaveCount(0);
  await expect(bob.page.getByRole('button', { name: 'Cancel schedule' })).toHaveCount(0);

  await openRelease(page);
  await page.getByRole('button', { name: 'Change time' }).click();
  const dialog = page.getByRole('dialog', { name: 'Change scheduled start' });
  await expect(dialog.getByLabel('Start at')).toHaveValue(tomorrowAt(21, 30));
  const value = tomorrowAt(23, 15);
  await dialog.getByLabel('Start at').fill(value);
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  const iso = new Date(value).toISOString();
  await expect(page.locator('.release-schedule time')).toHaveAttribute('datetime', iso);
  // Live on bob's page, without a reload.
  await expect(bob.page.locator('.release-schedule time')).toHaveAttribute('datetime', iso);

  // The server refuses bob, whatever the UI shows.
  const put = await bob.page.request.put(`${BASE()}/api/releases/${id}/schedule`, { headers: mutate, data: { startAt: iso } });
  expect(put.status()).toBe(403);
  const del = await bob.page.request.delete(`${BASE()}/api/releases/${id}/schedule`, { headers: mutate });
  expect(del.status()).toBe(403);

  // A time in the past is refused.
  const past = await page.request.put(`${BASE()}/api/releases/${id}/schedule`, {
    headers: mutate,
    data: { startAt: new Date(Date.now() - 60_000).toISOString() },
  });
  expect(past.status()).toBe(400);

  // Alice cancels; bob's page follows live.
  await page.getByRole('button', { name: 'Cancel schedule' }).click();
  await expect(page.locator('.release-head .pill')).toHaveText('in preparation');
  await expect(page.getByText('This release is a plan. Nothing is built until it is started.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Delete' })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Schedule start…' })).toBeVisible();
  await expect(bob.page.locator('.release-head .pill')).toHaveText('in preparation');
  await bob.context.close();

  await page.goto(`${BASE()}/p/demo-app`);
  const row = releaseRows(page).filter({ hasText: name });
  await expect(row).toContainText('in preparation');
  await expect(row).not.toContainText('starts');
});
