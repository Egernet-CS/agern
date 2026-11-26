import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// The per-project merge policy (docs/ARCHITECTURE.md §6 "Merge policy", §10) against the real backend: an admin sets each
// policy on the project page, and a passing run (the fake approver says APPROVE) behaves accordingly.
test.describe.configure({ mode: 'serial' });

const pill = (page: Page) => page.locator('.rh-title .pill');
const policyRow = (page: Page) => page.locator('.ph-policy');

async function newIssue(page: Page, title: string) {
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel(/Description/).fill('Make it so.');
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.issue-list .issue').first()).toContainText(title);
}

async function startRunFor(page: Page, title: string) {
  await page.locator('.issue-list .issue', { hasText: title }).getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await expect(page.locator('.rh-title h1')).toContainText(title);
}

/** Sets the policy through the project page's dialog, as an admin. */
async function setPolicy(page: Page, label: 'Automatic' | 'Owner approves' | 'Merge on GitHub', reviewers?: string) {
  await page.goto(`${BASE()}/p/demo-app`);
  const change = policyRow(page).getByRole('button', { name: 'Change' });
  await change.click();
  const dialog = page.getByRole('dialog', { name: 'Merge policy' });
  await dialog.getByRole('radio', { name: new RegExp(`^${label}`) }).check();
  if (reviewers !== undefined) await dialog.getByLabel('Reviewers (GitHub logins, comma-separated)').fill(reviewers);
  await expect(dialog).toContainText('Applies to runs started from now on; running runs keep their policy.');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(change).toBeFocused();
  await expect(policyRow(page)).toContainText(`Merge policy: ${label} —`);
}

test.afterAll(async ({ browser }) => {
  const { context, page } = await userContext(browser, 'alice');
  await page.request.put(`${BASE()}/api/projects/demo-app/settings`, { headers: mutate, data: { mergePolicy: 'automatic' } });
  await context.close();
});

test('owner approves: a passing run waits for its owner, who merges it; others cannot', async ({ page, browser }) => {
  await login(page, 'alice');
  await setPolicy(page, 'Owner approves');
  await expect(policyRow(page)).toContainText('every merge waits for the run owner’s approval in Agern.');

  // A bad reviewer entry is refused in the dialog, which stays open.
  await policyRow(page).getByRole('button', { name: 'Change' }).click();
  const dialog = page.getByRole('dialog', { name: 'Merge policy' });
  await dialog.getByRole('radio', { name: /^Merge on GitHub/ }).check();
  await dialog.getByLabel('Reviewers (GitHub logins, comma-separated)').fill('not a login');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog.getByRole('alert')).toContainText('is not a valid GitHub login');
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(policyRow(page)).toContainText('Merge policy: Owner approves —');

  await newIssue(page, 'Merge policy: owner approves');
  await startRunFor(page, 'Merge policy: owner approves');
  await expect(pill(page)).toHaveText('needs human', { timeout: 45_000 });
  await expect(page.locator('.rh-meta')).toContainText('Owner approves');
  await expect(page.locator('.run-error')).toContainText('The approver recommends merging');
  await expect(page.locator('.run-policy')).toContainText('This project requires the run owner’s approval before every merge.');
  const runUrl = page.url();

  const bob = await userContext(browser, 'bob');
  await bob.page.goto(runUrl);
  await expect(pill(bob.page)).toHaveText('needs human');
  await expect(bob.page.getByRole('button', { name: /Approve/ })).toHaveCount(0);
  await expect(bob.page.locator('.rh-actions')).toContainText('Waiting for alice to approve.');
  await bob.context.close();

  // The approver recommended merging: one click merges (no confirmation against its advice).
  await page.getByRole('button', { name: '✓ Approve & merge' }).click();
  await expect(pill(page)).toHaveText('merged');
  await expect(page.locator('.pipeline g[aria-label^="needs-human: done"]')).toBeVisible();
});

test('merge on GitHub: a passing run ends ready, Agern refuses to merge it', async ({ page }) => {
  await login(page, 'alice');
  await setPolicy(page, 'Merge on GitHub', 'carol');
  await expect(policyRow(page)).toContainText('Reviewers requested: @carol');

  await newIssue(page, 'Merge policy: on GitHub');
  await startRunFor(page, 'Merge policy: on GitHub');
  await expect(pill(page)).toHaveText('ready', { timeout: 45_000 });
  await expect(page.locator('.rh-meta')).toContainText('Merge on GitHub');
  const notice = page.locator('.run-policy');
  await expect(notice).toContainText('Ready for review on GitHub.');
  await expect(notice).toContainText('this page updates when it is merged or closed');
  await expect(notice.getByRole('link')).toHaveAttribute('href', /\/pull\/\d+$/);
  await expect(page.locator('.run-error')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Approve/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Cancel run|Retry/ })).toHaveCount(0);
  await expect(page.locator('.pipeline g[aria-label^="merge on GitHub: pending"]')).toBeVisible();

  const runId = page.url().split('/runs/')[1].split(/[?#]/)[0];
  const r = await page.request.post(`${BASE()}/api/runs/${runId}/approve`, { headers: mutate });
  expect(r.status()).toBe(409);
  expect((await r.json()).error.code).toBe('merge_on_github');
});

test('automatic again: a passing run merges by itself', async ({ page }) => {
  await login(page, 'alice');
  await setPolicy(page, 'Automatic');
  await expect(policyRow(page)).toContainText('the approver agent may merge.');
  await newIssue(page, 'Merge policy: automatic');
  await startRunFor(page, 'Merge policy: automatic');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });
  await expect(page.locator('.rh-meta')).not.toContainText('merge policy');
});

test('a non-admin sees the policy and cannot change it', async ({ browser }) => {
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app`);
  await expect(policyRow(bob.page)).toContainText('Merge policy: Automatic —');
  await expect(policyRow(bob.page).getByRole('button', { name: 'Change' })).toHaveCount(0);
  const r = await bob.page.request.put(`${BASE()}/api/projects/demo-app/settings`, { headers: mutate, data: { mergePolicy: 'github' } });
  expect(r.status()).toBe(403);
  await bob.context.close();
});
