// The running Agern version from GET /api/health, shown as the last line of the user menu on every shell page,
// and simply left out when the version cannot be fetched (issue #50).
import { expect, test, type Page } from 'playwright/test';
import { apiOf, BASE, userContext } from './support';

const USER = 'alice';
const versionLine = (page: Page) => page.locator('.usermenu-pop .usermenu-version');

test('the user menu shows the server version on every shell page', async ({ browser }) => {
  const { context, page } = await userContext(browser, USER);
  const version = (await (await apiOf(page).get(`${BASE()}/api/health`)).json()).version;
  expect(typeof version).toBe('string');
  expect(version.trim()).not.toBe('');

  await page.locator('.avatar-btn').click();
  await expect(versionLine(page)).toBeVisible();
  await expect(versionLine(page)).toHaveText(`Agern ${version}`);
  // Static text in the menu: not a menu item, not focusable, not hidden from screen readers.
  await expect(versionLine(page)).not.toHaveAttribute('role', /.*/);
  await expect(versionLine(page)).not.toHaveAttribute('tabindex', /.*/);
  await expect(versionLine(page)).not.toHaveAttribute('aria-hidden', /.*/);
  await expect(page.locator('.usermenu-pop > :last-child')).toHaveClass('usermenu-version');

  let healthRequests = 0;
  page.on('request', (r) => {
    if (new URL(r.url()).pathname === '/api/health') healthRequests++;
  });

  // In-app navigation keeps the shell mounted: same line, no new request.
  await page.locator('.topnav').getByRole('link', { name: 'Agents' }).click();
  await expect(page).toHaveURL(/\/agents$/);
  await page.locator('.avatar-btn').click();
  await expect(versionLine(page)).toHaveText(`Agern ${version}`);
  expect(healthRequests).toBe(0);

  // Also on the not-found page.
  await page.goto(`${BASE()}/no-such-page`);
  await expect(page.getByRole('heading', { name: 'Nothing here' })).toBeVisible();
  await page.locator('.avatar-btn').click();
  await expect(versionLine(page)).toHaveText(`Agern ${version}`);

  await context.close();
});

test('a failing health request hides the version and leaves the menu working', async ({ browser }) => {
  const { context } = await userContext(browser, USER);
  const page = await context.newPage();
  await page.route('**/api/health', (r) => r.fulfill({ status: 500, body: '{}' }));

  const failed = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/health');
  await page.goto(BASE());
  expect((await failed).status()).toBe(500);
  await page.locator('.avatar-btn').click();
  await expect(page.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: 'Credentials & security' })).toBeVisible();
  await expect(page.locator('.usermenu-version')).toHaveCount(0);

  await context.close();
});
