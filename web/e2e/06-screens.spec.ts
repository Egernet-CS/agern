// Screenshots of the real-backend screens at desktop and phone width, for looking at them.
// Only runs when E2E_SCREENSHOTS=<dir> is set:  E2E_SCREENSHOTS=/tmp/shots npm run e2e
import { mkdirSync } from 'node:fs';
import { expect, test, type Browser, type Page } from 'playwright/test';
import { BASE, login, readState } from './support';

const dir = process.env.E2E_SCREENSHOTS;
test.skip(!dir, 'set E2E_SCREENSHOTS=<dir> to capture screenshots');

const sizes = { desktop: { width: 1440, height: 900 }, phone: { width: 390, height: 844 } };

async function shoot(browser: Browser, name: string, path: string, prep?: (p: Page) => Promise<void>) {
  mkdirSync(dir!, { recursive: true });
  for (const [size, viewport] of Object.entries(sizes)) {
    const ctx = await browser.newContext({ viewport, deviceScaleFactor: size === 'phone' ? 2 : 1 });
    const page = await ctx.newPage();
    await login(page, 'alice');
    await page.goto(`${BASE()}${path}`);
    await page.waitForLoadState('networkidle');
    if (prep) await prep(page);
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${dir}/${name}-${size}.png`, fullPage: size === 'phone' || name !== 'project' });
    await ctx.close();
  }
}

test('screens', async ({ browser }) => {
  test.setTimeout(10 * 60_000);
  await shoot(browser, 'projects', '/');
  await shoot(browser, 'project', `/p/demo-app?session=${readState().aliceChat}`);
  const runs = await (await (await browser.newContext()).request.get(`${BASE()}/api/health`)).json();
  expect(runs.name).toBe('agern');
  await shoot(browser, 'agents', '/agents');
  await shoot(browser, 'settings', '/settings');
  await shoot(browser, 'admin', '/admin');
  await shoot(browser, 'run', '/', async (p) => {
    await p.goto(`${BASE()}/p/demo-app`);
    if (p.viewportSize()!.width < 700) await p.getByRole('tab', { name: /agents/ }).click();
    await p.locator('.run-row', { hasText: 'Needs a second pass' }).click();
    await expect(p.locator('.pipeline')).toBeVisible();
  });
});
