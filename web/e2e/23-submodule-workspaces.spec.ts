import { expect, test, type Page } from 'playwright/test';
import { BASE, login } from './support';

// A workspace built from git submodules (docs/ARCHITECTURE.md §5 "Workspace projects"): the fake GitHub's demo-shop has
// no .agern/workspace.yml, only a .gitmodules with the members services/api (demo-shop-api) and web (demo-shop-web),
// and a submodule of another organization (vendor/icons, context only).
test.describe.configure({ mode: 'serial' });

const members = (page: Page) => page.locator('.panel--activity .member-row');
const issueRow = (page: Page, title: string) => page.locator('.issue-list .issue', { hasText: title });
const startButton = (page: Page, title: string) => issueRow(page, title).getByRole('button', { name: '▶ Start run' });

test('the members come from the submodules', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-shop`);
  const tag = page.locator('.ph-meta .tag', { hasText: 'workspace · 2 members' });
  await expect(tag).toBeVisible();
  await expect(tag).toHaveAttribute('title', 'Several repositories checked out side by side (.gitmodules)');
  await expect(members(page)).toHaveCount(2);
  const api = members(page).filter({ hasText: 'demo-shop-api' });
  await expect(api).toContainText('services/api');
  await expect(api.locator('.member-submodule')).toHaveText('submodule');
  await expect(api.getByRole('link', { name: 'demo-shop-api' })).toHaveAttribute('href', '/p/demo-shop-api');
  await expect(members(page).filter({ hasText: 'demo-shop-web' })).toContainText('main');
  await expect(page.locator('.member-context')).toContainText('vendor/icons');

  // The label is the member repository's name.
  await expect(issueRow(page, 'Add a health endpoint').locator('.issue-member')).toHaveText('services/api');
  await expect(startButton(page, 'Add a health endpoint')).toBeEnabled();
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
  await expect(members(page)).toHaveCount(2);
  await expect(issueRow(page, 'Add a health endpoint')).toHaveCount(0);
  await expect(issueRow(page, 'Show the cart total in the header')).toHaveCount(1);
});
