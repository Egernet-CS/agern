import { expect, request, test, type Page } from 'playwright/test';
import { BASE, login } from './support';

test.describe.configure({ mode: 'serial' });

const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const pill = (page: Page) => page.locator('.rh-title .pill');

async function newIssue(page: Page, title: string, body: string) {
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByText('feature', { exact: true }).click();
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel(/Description/).fill(body);
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.issue-list .issue').first()).toContainText(title);
}

async function startRunFor(page: Page, title: string) {
  const row = page.locator('.issue-list .issue', { hasText: title });
  await row.getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await expect(page.locator('.rh-title h1')).toContainText(title);
}

test('the ux and ui-design reviewers’ screenshots show per round on the run page', async ({ page }) => {
  await login(page, 'alice');
  await newIssue(page, 'Screens worth seeing', 'Reviewers take screenshots. fake:screenshots fake:changes');
  await startRunFor(page, 'Screens worth seeing');
  // No reloads from here on: the screenshots arrive with the run over the feed.
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });

  const detail = page.locator('.stage-detail');
  const shots = detail.locator('.shots');

  // ux, latest round (fix round 1) selected by default.
  await node(page, 'ux').click();
  await expect(detail.locator('.sd-role')).toHaveText('ux');
  await expect(detail.getByRole('heading', { name: /Screenshots/ })).toContainText('1');
  await expect(shots.locator('figcaption')).toHaveText(['Fake ux screenshot (fix round 1)']);
  await expect(detail).toContainText('Attached 1 screenshot.');
  // The build round has its own.
  await detail.getByRole('tab', { name: 'build' }).click();
  await expect(shots.locator('figcaption')).toHaveText(['Fake ux screenshot (build)']);
  await expect(shots).not.toContainText('fix round 1');

  // The thumbnail opens a dialog with the full image, its caption and a link to the full size.
  await detail.getByRole('button', { name: 'Open screenshot: Fake ux screenshot (build)' }).click();
  const dialog = page.getByRole('dialog', { name: 'Screenshot' });
  await expect(dialog).toBeVisible();
  const img = dialog.locator('img');
  await expect(img).toHaveAttribute('alt', 'Fake ux screenshot (build)');
  await expect.poll(() => img.evaluate((el) => (el as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  await expect(dialog).toContainText('Fake ux screenshot (build)');
  await expect(dialog).toContainText('image/png');
  const full = dialog.getByRole('link', { name: 'Open full size ↗' });
  await expect(full).toHaveAttribute('target', '_blank');
  const imageUrl = await full.getAttribute('href');
  expect(imageUrl).toMatch(/^\/api\/runs\/[0-9a-f-]+\/screenshots\/[0-9a-f-]+$/);
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();

  // ui-design has its own screenshot.
  await node(page, 'ui-design').click();
  await expect(detail.locator('.sd-role')).toHaveText('ui-design');
  await expect(shots.locator('figcaption')).toHaveText(['Fake ui-design screenshot (fix round 1)']);

  // Other stages show no screenshots section at all.
  for (const role of ['security', 'builder']) {
    await node(page, role).click();
    await expect(detail.locator('.sd-role')).toHaveText(role);
    await expect(detail.getByRole('heading', { name: /Screenshots/ })).toHaveCount(0);
    await expect(shots).toHaveCount(0);
  }

  // The image is served only to a logged-in user who may see the run.
  const anon = await request.newContext();
  expect((await anon.get(`${BASE()}${imageUrl}`)).status()).toBe(401);
  await anon.dispose();
  const own = await page.request.get(`${BASE()}${imageUrl}`);
  expect(own.status()).toBe(200);
  expect(own.headers()['content-type']).toBe('image/png');
});

test('a UI change reviewed without screenshots shows a warning on the run page, but still merges', async ({ page }) => {
  await login(page, 'alice');
  await newIssue(page, 'Banner without screenshots', 'The builder touches a component. fake:ui-change');
  await startRunFor(page, 'Banner without screenshots');
  // No reloads: the warning arrives with the run over the feed.
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });

  const warning = page.locator('.run-warning');
  await expect(warning).toHaveCount(1);
  await expect(warning).toHaveAttribute('role', 'note');
  await expect(warning).toContainText('UI change reviewed without screenshots');
  await expect(warning).toContainText('build');
  await expect(warning).toContainText('web/src/FakeBanner.tsx');

  // It leads to the ux review of that round, which carries the same warning; so does ui-design, and nobody else.
  await warning.getByRole('button', { name: 'See the ux review' }).click();
  const detail = page.locator('.stage-detail');
  await expect(detail.locator('.sd-role')).toHaveText('ux');
  await expect(detail.locator('.sd-warning')).toContainText('UI change reviewed without screenshots: the change touches 1 UI file (web/src/FakeBanner.tsx)');
  await node(page, 'ui-design').click();
  await expect(detail.locator('.sd-role')).toHaveText('ui-design');
  await expect(detail.locator('.sd-warning')).toContainText('UI change reviewed without screenshots');
  await node(page, 'code').click();
  await expect(detail.locator('.sd-role')).toHaveText('code');
  await expect(detail.locator('.sd-warning')).toHaveCount(0);
});

test('a UI change with screenshots, and a change without UI files, show no warning', async ({ page }) => {
  await login(page, 'alice');
  await newIssue(page, 'Banner with screenshots', 'The builder touches a component. fake:ui-change fake:screenshots');
  await startRunFor(page, 'Banner with screenshots');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });
  await node(page, 'ux').click();
  await expect(page.locator('.stage-detail .shots figcaption')).toHaveText(['Fake ux screenshot (build)']);
  await expect(page.locator('.run-warning')).toHaveCount(0);
  await expect(page.locator('.sd-warning')).toHaveCount(0);

  await newIssue(page, 'Docs only', 'Only AGERN_FAKE.md changes.');
  await startRunFor(page, 'Docs only');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });
  await expect(page.locator('.run-warning')).toHaveCount(0);
});
