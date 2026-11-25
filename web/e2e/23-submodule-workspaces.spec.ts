import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login } from './support';

// A workspace built from git submodules (docs/ARCHITECTURE.md §5 "Workspace projects"): the fake GitHub's demo-shop has
// no .agern/workspace.yml, only a .gitmodules with the members services/api (demo-shop-api), web (demo-shop-web) and
// tools/cli (demo-ws-tools, whose label does not exist yet), and a submodule of another organization (vendor/icons).
test.describe.configure({ mode: 'serial' });

const members = (page: Page) => page.locator('.panel--activity .member-row');
const issueRow = (page: Page, title: string) => page.locator('.issue-list .issue', { hasText: title });
const startButton = (page: Page, title: string) => issueRow(page, title).getByRole('button', { name: '▶ Start run' });

/** While it exists, the fake GitHub refuses to create labels in demo-shop (403), as for a token without push access. */
function labelsReadOnly(on: boolean) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (the global setup starts the server)');
  const file = join(dir, 'fake-github', 'demo-shop', '.agern-fake', 'labels-readonly');
  if (on) {
    mkdirSync(join(file, '..'), { recursive: true });
    writeFileSync(file, '');
  } else rmSync(file, { force: true });
}

// First in this file: the first page load of demo-shop would create the missing label by itself.
test('missing member labels are shown and can be created', async ({ page }) => {
  labelsReadOnly(true);
  try {
    await login(page, 'alice');
    await page.goto(`${BASE()}/p/demo-shop`);
    const warning = page.locator('.member-labels-missing');
    await expect(warning).toContainText('Member labels missing on GitHub');
    await expect(warning).toContainText('demo-ws-tools');
    const button = warning.getByRole('button', { name: 'Create missing labels' });
    await button.click();
    await expect(page.locator('.member-labels-error')).toContainText('cannot create labels');
    labelsReadOnly(false);
    await button.click();
    await expect(warning).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Members' })).toBeVisible();
    await expect(page.locator('.member-labels-missing')).toHaveCount(0);
  } finally {
    labelsReadOnly(false);
  }
});

test('the members come from the submodules', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-shop`);
  const tag = page.locator('.ph-meta .tag', { hasText: 'workspace · 3 members' });
  await expect(tag).toBeVisible();
  await expect(tag).toHaveAttribute('title', 'Several repositories checked out side by side (.gitmodules)');
  await expect(members(page)).toHaveCount(3);
  const api = members(page).filter({ hasText: 'demo-shop-api' });
  await expect(api).toContainText('services/api');
  await expect(api.locator('.member-submodule')).toHaveText('submodule');
  await expect(api.getByRole('link', { name: 'demo-shop-api' })).toHaveAttribute('href', '/p/demo-shop-api');
  await expect(members(page).filter({ hasText: 'demo-shop-web' })).toContainText('main');
  await expect(page.locator('.member-context')).toContainText('vendor/icons');

  // The label is the member repository's name; an issue without one is the workspace repository's own.
  await expect(issueRow(page, 'Add a health endpoint').locator('.issue-member')).toHaveText('services/api');
  await expect(startButton(page, 'Add a health endpoint')).toBeEnabled();
  await expect(issueRow(page, 'Update the architecture notes').locator('.issue-member')).toHaveText('workspace repository');
  await expect(startButton(page, 'Update the architecture notes')).toBeEnabled();
  // Releases and its sandbox are available.
  await expect(page.getByRole('button', { name: 'New release' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sandbox' })).toBeVisible();
});

test('an issue without a member label is a run of the workspace repository itself', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-shop`);
  await startButton(page, 'Update the architecture notes').click();
  await expect(page).toHaveURL(/\/p\/demo-shop\/runs\//);
  const meta = page.locator('.rh-meta');
  await expect(meta).toContainText(/branchfactory\/issue-3-update-the-architecture-notes-[0-9a-f]{8}/);
  await expect(meta).not.toContainText('directory');
  await expect(page.locator('.pipeline g[aria-label^="merge to main: done, verdict MERGED"]')).toBeVisible({ timeout: 45_000 });
  await expect(meta.locator('a[href*="/demo-shop/pull/"]')).toHaveCount(1);
});

test('a run on a labelled issue changes the submodule in its own repository', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-shop`);
  await startButton(page, 'Add a health endpoint').click();
  await expect(page).toHaveURL(/\/p\/demo-shop\/runs\//);
  const meta = page.locator('.rh-meta');
  await expect(meta.getByRole('link', { name: 'demo-shop-api' })).toHaveAttribute('href', '/p/demo-shop-api');
  await expect(meta).toContainText('directoryservices/api');
  await expect(meta).toContainText(/branchfactory\/issue-1-add-a-health-endpoint-[0-9a-f]{8}/);
  await expect(page.locator('.pipeline g[aria-label^="merge to main: done, verdict MERGED"]')).toBeVisible({ timeout: 45_000 });
  await expect(meta.getByRole('link', { name: /^demo-shop-api#\d+ ↗$/ })).toHaveAttribute('href', /\/demo-shop-api\/pull\/\d+$/);

  // The workspace issue is closed by Agern.
  await page.goto(`${BASE()}/p/demo-shop`);
  await expect(members(page)).toHaveCount(3);
  await expect(issueRow(page, 'Add a health endpoint')).toHaveCount(0);
  await expect(issueRow(page, 'Show the cart total in the header')).toHaveCount(1);
});
