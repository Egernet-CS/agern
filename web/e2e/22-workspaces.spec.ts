import { expect, test, type Page } from 'playwright/test';
import { BASE, login } from './support';

// A workspace project (docs/ARCHITECTURE.md §5 "Workspace projects"): the fake GitHub's demo-workspace has three members,
// demo-ws-app (directory app, base branch dev), demo-ws-tools (tools/cli) and demo-ws-lab (lab, only alice can read it).
test.describe.configure({ mode: 'serial' });

const members = (page: Page) => page.locator('.panel--activity .member-row');
const issueRow = (page: Page, title: string) => page.locator('.issue-list .issue', { hasText: title });
const startButton = (page: Page, title: string) => issueRow(page, title).getByRole('button', { name: '▶ Start run' });

test('the project page shows the members, and each issue its member', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-workspace`);
  await expect(page.locator('.ph-meta')).toContainText('workspace · 3 members');
  await expect(page.getByRole('heading', { name: 'Members' })).toBeVisible();
  await expect(members(page)).toHaveCount(3);
  const app = members(page).filter({ hasText: 'demo-ws-app' });
  await expect(app).toContainText('app');
  await expect(app).toContainText('dev');
  await expect(app.getByRole('link', { name: 'demo-ws-app' })).toHaveAttribute('href', '/p/demo-ws-app');
  await expect(members(page).filter({ hasText: 'demo-ws-tools' })).toContainText('tools/cli');
  await expect(members(page).filter({ hasText: 'demo-ws-tools' })).toContainText('main');
  await expect(members(page).filter({ hasText: 'demo-ws-lab' })).not.toContainText('no access');
  // Sandboxes belong to the member projects; releases span them.
  await expect(page.getByRole('heading', { name: 'Sandbox' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'New release' })).toBeVisible();
  await expect(page.locator('.panel--activity')).not.toContainText('Releases are not available for workspace projects yet.');

  await expect(issueRow(page, 'Show prices with currency').locator('.issue-member')).toHaveText('app');
  await expect(startButton(page, 'Show prices with currency')).toBeEnabled();
  await expect(issueRow(page, 'Document the release process')).toContainText('no member label');
  await expect(startButton(page, 'Document the release process')).toBeDisabled();
  await expect(startButton(page, 'Document the release process')).toHaveAttribute('title', 'Add one member label on GitHub first');
  await expect(issueRow(page, 'Share the price formatter')).toContainText('several member labels');
  await expect(startButton(page, 'Share the price formatter')).toBeDisabled();
  await expect(startButton(page, 'Share the price formatter')).toHaveAttribute('title', 'Keep exactly one member label');
});

test('a member the user cannot read is marked, and its issues cannot be run', async ({ page }) => {
  await login(page, 'bob');
  await page.goto(`${BASE()}/p/demo-workspace`);
  const lab = members(page).filter({ hasText: 'demo-ws-lab' });
  await expect(lab).toContainText('no access');
  await expect(lab.getByRole('link')).toHaveCount(0);
  await expect(issueRow(page, 'Try a faster search index').locator('.issue-member')).toHaveText('lab');
  await expect(startButton(page, 'Try a faster search index')).toBeDisabled();
  await expect(startButton(page, 'Try a faster search index')).toHaveAttribute('title', 'Your GitHub token cannot read demo-ws-lab');
});

test('a run on a labelled issue works in the member repository and merges into its base branch', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-workspace`);
  await startButton(page, 'Show prices with currency').click();
  await expect(page).toHaveURL(/\/p\/demo-workspace\/runs\//);
  const meta = page.locator('.rh-meta');
  await expect(meta.getByRole('link', { name: 'demo-ws-app' })).toHaveAttribute('href', '/p/demo-ws-app');
  await expect(meta).toContainText('directoryapp');
  await expect(meta).toContainText('basedev');
  await expect(meta).toContainText(/branchfactory\/issue-1-show-prices-with-currency-[0-9a-f]{8}/);
  await expect(page.locator('.pipeline g[aria-label^="merge to dev: done, verdict MERGED"]')).toBeVisible({ timeout: 45_000 });
  const pr = meta.getByRole('link', { name: /^demo-ws-app#\d+ ↗$/ });
  await expect(pr).toHaveAttribute('href', /\/demo-ws-app\/pull\/\d+$/);

  // The workspace issue is closed by Agern.
  await page.goto(`${BASE()}/p/demo-workspace`);
  await expect(members(page)).toHaveCount(3);
  await expect(issueRow(page, 'Show prices with currency')).toHaveCount(0);
});
