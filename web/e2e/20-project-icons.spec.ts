import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, pngBytes, userContext } from './support';

// Project icons (docs/ARCHITECTURE.md §8 "Project icons", §10) against the real backend: the letter tile, an admin
// uploading, replacing and removing the icon from the project header, a refused upload, and a non-admin's view.
test.describe.configure({ mode: 'serial' });

const headerTile = (page: Page) => page.locator('.ph-title .project-icon');
const cardTile = (page: Page) => page.locator('.project-card', { hasText: 'demo-app' }).locator('.project-icon');
const iconButton = (page: Page) => page.getByRole('button', { name: 'Change project icon' });

/** Opens the icon menu and picks [file] through "Upload image…" or "Replace image…". */
async function pick(page: Page, item: 'Upload image…' | 'Replace image…', file: { name: string; mimeType: string; buffer: Buffer }) {
  await iconButton(page).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('menuitem', { name: item }).click();
  await (await chooser).setFiles(file);
}

async function expectLoadedIcon(page: Page) {
  const img = headerTile(page).locator('img');
  await expect(img).toHaveAttribute('src', /\/api\/projects\/demo-app\/icon\?v=[0-9a-f]{16}$/);
  await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth)).toBeGreaterThan(0);
  return (await img.getAttribute('src'))!;
}

test.afterAll(async ({ browser }) => {
  const { context, page } = await userContext(browser, 'alice');
  await page.request.delete(`${BASE()}/api/projects/demo-app/icon`, { headers: mutate });
  await context.close();
});

test('without an icon the header and the card show the letter tile', async ({ page }) => {
  await login(page, 'alice');
  await page.request.delete(`${BASE()}/api/projects/demo-app/icon`, { headers: mutate });
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(page.getByRole('heading', { name: 'demo-app' })).toBeVisible();
  await expect(headerTile(page)).toHaveText('D');
  await expect(headerTile(page).locator('img')).toHaveCount(0);
  await page.goto(`${BASE()}/`);
  await expect(cardTile(page)).toHaveText('D');
  await expect(cardTile(page).locator('img')).toHaveCount(0);
});

test('an admin uploads, replaces and fails to upload an icon', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(iconButton(page)).toHaveAttribute('aria-haspopup', 'menu');

  // The menu works with the keyboard: Escape closes it and returns focus to the button.
  await iconButton(page).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menu')).toBeVisible();
  await expect(page.getByRole('menuitem', { name: 'Upload image…' })).toBeFocused();
  await expect(page.getByRole('menuitem', { name: 'Remove icon' })).toHaveCount(0);
  await expect(page.getByRole('menu')).toContainText('PNG, JPEG, WebP or GIF, up to 1 MB. Shown as a square.');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toHaveCount(0);
  await expect(iconButton(page)).toBeFocused();

  await pick(page, 'Upload image…', { name: 'icon.png', mimeType: 'image/png', buffer: pngBytes(64, 64) });
  const first = await expectLoadedIcon(page);
  await page.goto(`${BASE()}/`);
  await expect(cardTile(page).locator('img')).toHaveAttribute('src', first);

  await page.goto(`${BASE()}/p/demo-app`);
  await pick(page, 'Replace image…', { name: 'wide.png', mimeType: 'image/png', buffer: pngBytes(48, 32) });
  await expect(headerTile(page).locator('img')).not.toHaveAttribute('src', first);
  const second = await expectLoadedIcon(page);

  // An SVG is refused by the server; the message is shown and the icon stays.
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10"/></svg>';
  await pick(page, 'Replace image…', { name: 'icon.svg', mimeType: 'image/svg+xml', buffer: Buffer.from(svg) });
  const alert = page.getByRole('alert').filter({ hasText: 'The icon must be a PNG, JPEG, WebP or GIF image' });
  await expect(alert).toBeVisible();
  await expect(headerTile(page).locator('img')).toHaveAttribute('src', second);
  await alert.getByRole('button', { name: 'Dismiss' }).click();
  await expect(alert).toHaveCount(0);

  // So is a text file.
  await pick(page, 'Replace image…', { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('not an image') });
  await expect(page.getByRole('alert')).toContainText('The icon must be a PNG, JPEG, WebP or GIF image');
  await expect(headerTile(page).locator('img')).toHaveAttribute('src', second);
});

test('a non-admin sees the icon but cannot change it', async ({ browser }) => {
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app`);
  await expect(headerTile(bob.page).locator('img')).toHaveAttribute('src', /\/api\/projects\/demo-app\/icon\?v=/);
  await expect(iconButton(bob.page)).toHaveCount(0);
  await bob.page.goto(`${BASE()}/`);
  await expect(cardTile(bob.page).locator('img')).toHaveAttribute('src', /\/api\/projects\/demo-app\/icon\?v=/);
  const put = await bob.page.request.put(`${BASE()}/api/projects/demo-app/icon`, {
    headers: { ...mutate, 'Content-Type': 'image/png' },
    data: pngBytes(8, 8),
  });
  expect(put.status()).toBe(403);
  const del = await bob.page.request.delete(`${BASE()}/api/projects/demo-app/icon`, { headers: mutate });
  expect(del.status()).toBe(403);
  await bob.context.close();
});

test('an admin removes the icon', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(headerTile(page).locator('img')).toHaveCount(1);
  await iconButton(page).click();
  await page.getByRole('menuitem', { name: 'Remove icon' }).click();
  await expect(headerTile(page)).toHaveText('D');
  await expect(headerTile(page).locator('img')).toHaveCount(0);
  await page.goto(`${BASE()}/`);
  await expect(cardTile(page)).toHaveText('D');
  await expect(cardTile(page).locator('img')).toHaveCount(0);
});
