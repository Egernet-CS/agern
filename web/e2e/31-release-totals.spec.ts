import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate } from './support';

// Time and cost of a release and its runs (docs/ARCHITECTURE.md §6 "Time and cost"): the card on the release page, the
// figures per issue, the breakdown and the run header. One release per project can build, so the test ends by
// cancelling its release.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const BUILD = { timeout: 90_000 };

async function newIssue(page: Page, title: string): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: 'Totals.', kind: 'task' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

test('a built release shows its time and cost, per issue and broken down; the run header shows the run cost', async ({ page }) => {
  await login(page, 'alice');
  const name = `totals-${tag}`;
  const title = `Totals issue ${tag}`;
  const number = await newIssue(page, title);
  const created = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  expect((await page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, { headers: mutate, data: { issueNumbers: [number], releaseId: id } })).status()).toBe(200);

  // Planned: no card.
  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Time and cost' })).toHaveCount(0);

  const started = await page.request.post(`${BASE()}/api/releases/${id}/start`, { headers: mutate });
  expect(started.status(), await started.text()).toBe(200);
  const item = page.locator('.release-order li', { hasText: title });
  await expect(item.locator('.issue-state')).toHaveText('merged', BUILD);

  const card = page.locator('section.release-totals');
  await expect(card.getByRole('heading', { name: 'Time and cost' })).toBeVisible();
  await expect(card).toContainText('agents working');
  // The fake CLI reports a cost: once the release is ready, the card shows it with one run.
  const costLine = card.locator('.totals-figures').nth(1).locator('> span');
  await expect(costLine.nth(1)).toHaveText('1 run', BUILD);
  await expect(costLine.nth(0)).toHaveText(/^\$\d+\.\d+$/);
  await expect(costLine.nth(0)).not.toHaveText('$0.00');
  await expect(card).toContainText('API-equivalent cost reported by Claude Code (subscription), not what was billed.');

  // The issue row: elapsed time, cost and a bar (the only issue: the full width).
  await expect(item.locator('.ro-totals-text')).toContainText(/\$\d/);
  await expect(item.locator('.ro-totals .sr-only')).toContainText(`Issue #${number}:`);
  await expect(item.locator('.ro-bar-fill')).toHaveAttribute('style', /width: 100%/);

  // The breakdown opens from the keyboard.
  const summary = card.getByText('Breakdown by role and kind of work');
  await summary.focus();
  await page.keyboard.press('Enter');
  const byRole = card.getByRole('table', { name: 'By role' });
  await expect(byRole).toBeVisible();
  await expect(byRole.getByRole('rowheader', { name: 'builder' })).toBeVisible();
  await expect(card.getByRole('table', { name: 'By kind of work' }).getByRole('rowheader', { name: 'First build' })).toBeVisible();

  // The run header: agents working and cost.
  await item.getByRole('link', { name: 'run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  const meta = page.locator('.rh-meta');
  await expect(meta.locator('div', { has: page.locator('dt', { hasText: /^cost$/ }) }).locator('dd')).toContainText(/\$\d+\.\d+ API-equivalent/);
  await expect(meta.locator('div', { has: page.locator('dt', { hasText: 'agents working' }) }).locator('dd')).not.toHaveText('—');

  const cancel = await page.request.post(`${BASE()}/api/releases/${id}/cancel`, { headers: mutate });
  expect([200, 409]).toContain(cancel.status());
});
