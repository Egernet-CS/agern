import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// The per-project base branch (docs/ARCHITECTURE.md §6 "Base branch", §10) against the real backend: an admin sets it on
// the project page; a new run starts from it, shows it and merges its pull request into it. The fake GitHub's demo-app
// has the branches main and dev.
test.describe.configure({ mode: 'serial' });

const pill = (page: Page) => page.locator('.rh-title .pill');
const baseRow = (page: Page) => page.locator('.ph-base');
const policyRow = (page: Page) => page.locator('.ph-policy');

test.afterAll(async ({ browser }) => {
  const { context, page } = await userContext(browser, 'alice');
  await page.request.put(`${BASE()}/api/projects/demo-app/settings`, { headers: mutate, data: { mergePolicy: 'automatic' } });
  await context.close();
});

test('an admin sets the base branch; a missing branch is refused in the dialog', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(baseRow(page)).toContainText('Base branch: main (the repository’s default branch)');
  const change = baseRow(page).getByRole('button', { name: 'Change base branch' });
  await expect(change).toHaveAttribute('aria-haspopup', 'dialog');

  await change.click();
  const dialog = page.getByRole('dialog', { name: 'Base branch' });
  const input = dialog.getByLabel('Base branch');
  await expect(input).toBeFocused();
  await expect(input).toHaveValue('');
  await expect(input).toHaveAttribute('placeholder', 'main');
  await expect(dialog).toContainText('Leave empty to use the repository’s default branch (main). The branch must exist on GitHub.');
  await expect(dialog).toContainText('Applies to runs started from now on; running runs keep their base branch.');
  await expect(dialog).toContainText('Releases are still created from and merged into the default branch.');

  await input.fill('nope');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog.getByRole('alert')).toContainText('does not exist');
  await expect(dialog).toBeVisible();
  await expect(input).toHaveValue('nope');

  await input.fill('dev');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(change).toBeFocused();
  await expect(baseRow(page)).toContainText('Base branch: dev');
  await expect(baseRow(page)).not.toContainText('default branch');
  // Saving the base branch leaves the merge policy as it was.
  await expect(policyRow(page)).toContainText('Merge policy: Automatic —');

  // Escape closes the dialog without saving and gives the focus back.
  await change.click();
  await expect(dialog.getByLabel('Base branch')).toHaveValue('dev');
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(change).toBeFocused();
});

test('a new run starts from the base branch, shows it and merges into it', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  const title = 'Base branch: build on dev';
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel(/Description/).fill('Make it so.');
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  const issue = page.locator('.issue-list .issue', { hasText: title });
  await expect(issue).toBeVisible();
  await issue.getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);

  const meta = page.locator('.rh-meta');
  await expect(meta).toContainText('base');
  await expect(meta).toContainText('dev');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });
  await expect(page.locator('.pipeline g[aria-label^="merge to dev: done"]')).toBeVisible();

  const runId = page.url().split('/runs/')[1].split(/[?#]/)[0];
  const run = await (await page.request.get(`${BASE()}/api/runs/${runId}`)).json();
  expect(run.baseBranch).toBe('dev');
  // GitHub would not close the issue for a merge into dev; Agern closes it itself.
  const after = await (await page.request.get(`${BASE()}/api/projects/demo-app/issues/${run.issueNumber}`)).json();
  expect(after.state).toBe('closed');
});

test('saving the merge policy keeps the base branch', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await policyRow(page).getByRole('button', { name: 'Change' }).click();
  const dialog = page.getByRole('dialog', { name: 'Merge policy' });
  await dialog.getByRole('radio', { name: /^Owner approves/ }).check();
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(policyRow(page)).toContainText('Merge policy: Owner approves —');
  await expect(baseRow(page)).toContainText('Base branch: dev');
});

test('a non-admin sees the base branch and cannot change it', async ({ browser }) => {
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app`);
  await expect(baseRow(bob.page)).toContainText('Base branch: dev');
  await expect(baseRow(bob.page).getByRole('button')).toHaveCount(0);
  const r = await bob.page.request.put(`${BASE()}/api/projects/demo-app/settings`, {
    headers: mutate,
    data: { mergePolicy: 'automatic', baseBranch: 'main' },
  });
  expect(r.status()).toBe(403);
  await bob.context.close();
});
