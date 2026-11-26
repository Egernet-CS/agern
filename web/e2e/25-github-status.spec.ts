// The GitHub status page and the shell's banner (issue #99). The e2e server runs the fake GitHub, so GitHub's
// official status is switched off and Agern's own calls are not recorded: the page says GitHub is working.
import { expect, test } from 'playwright/test';
import { BASE, userContext } from './support';

const USER = 'alice';

test('the user menu leads to the GitHub status page, which shows the verdict, the table and no banner', async ({ browser }) => {
  const { context, page } = await userContext(browser, USER);
  await page.goto(BASE());
  await page.locator('.avatar-btn').click();
  const item = page.getByRole('menuitem', { name: 'GitHub status' });
  await expect(item).toBeVisible();
  await item.click();
  await expect(page).toHaveURL(/\/github-status$/);

  await expect(page.getByRole('heading', { level: 1, name: 'GitHub status' })).toBeVisible();
  await expect(page.locator('[data-verdict="working"]')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'GitHub is working' })).toBeVisible();
  await expect(page.getByText('The official GitHub status is not checked in this installation.')).toBeVisible();
  const table = page.getByRole('table');
  await expect(table.getByRole('rowheader', { name: 'Issues and labels' })).toBeVisible();
  await expect(table.getByRole('rowheader', { name: 'Git (clone, fetch, push)' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Rate limits' })).toBeVisible();
  await expect(page.getByText('Tokens are shown without their owners.')).toBeVisible();
  await expect(page.locator('.gh-banner')).toHaveCount(0);

  // The page itself never scrolls sideways.
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
  await context.close();
});

test('a degraded verdict shows a dismissible banner that keeps the project page within the viewport', async ({ browser }) => {
  const { context } = await userContext(browser, USER);
  const page = await context.newPage();
  let state = 'degraded';
  await page.route('**/api/github/status?brief=1', async (r) => {
    const real = await (await r.fetch()).json();
    await r.fulfill({ json: { ...real, verdict: { state, reason: '3 of 9 GitHub calls by Agern failed in the last 5 minutes.' } } });
  });

  await page.goto(`${BASE()}/p/demo-app`);
  const banner = page.locator('.gh-banner');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText('GitHub is degraded for Agern.');
  await expect(banner.getByRole('link', { name: 'See GitHub status' })).toHaveAttribute('href', '/github-status');
  // The app shell stays exactly one viewport tall.
  expect(await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight)).toBeLessThanOrEqual(0);

  await page.locator('.avatar-btn').click();
  await expect(page.getByRole('menuitem', { name: 'GitHub status — degraded' })).toBeVisible();
  await page.locator('.avatar-btn').click();

  await banner.getByRole('button', { name: 'Dismiss' }).click();
  await expect(banner).toHaveCount(0);
  const polled = page.waitForResponse((r) => r.url().includes('/api/github/status?brief=1'));
  await page.reload();
  await polled;
  await expect(page.locator('.project-head')).toBeVisible();
  await expect(page.locator('.usermenu')).toBeVisible();
  await expect(page.locator('.gh-banner')).toHaveCount(0);

  // Worse than what was dismissed: shown again.
  state = 'down';
  await page.reload();
  await expect(page.locator('.gh-banner')).toContainText('GitHub is down for Agern.');
  await context.close();
});
