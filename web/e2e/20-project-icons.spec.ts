import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Locator, type Page } from 'playwright/test';
import { BASE, login, mutate, pngBytes, userContext } from './support';

// Project icons (docs/ARCHITECTURE.md §8 "Project icons", §10) against the real backend: the letter tile, an admin
// uploading (raster and SVG), replacing and removing the icon from the project header, taking it from a file in the
// repository, refused uploads, and a non-admin's view.
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

/** The tile of [size] px is filled by its loaded image (the image covers the tile's whole inner box). */
async function expectFilledTile(tile: Locator, size: number) {
  const img = tile.locator('img');
  await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth)).toBeGreaterThan(0);
  const box = await img.evaluate((i: HTMLImageElement) => {
    const r = i.getBoundingClientRect();
    const t = i.parentElement!;
    return { w: r.width, h: r.height, innerW: t.clientWidth, innerH: t.clientHeight, outer: t.getBoundingClientRect().width };
  });
  expect(box.outer).toBe(size);
  expect(box.w).toBe(box.innerW);
  expect(box.h).toBe(box.innerH);
  expect(box.w).toBeGreaterThan(size - 4);
}

/** Puts a file on demo-app's default branch in the fake GitHub (null removes it). */
function repositoryFile(path: string, content: Buffer | string | null) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (the global setup starts the server)');
  const file = join(dir, 'fake-github', 'demo-app', ...path.split('/'));
  if (content === null) rmSync(file, { force: true });
  else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
}

const svgFile = (name: string, svg: string) => ({ name, mimeType: 'image/svg+xml', buffer: Buffer.from(svg) });
const viewBoxOnly =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#1f6feb"/><circle cx="5" cy="5" r="3" fill="#fff"/></svg>';
const sizeOnly = '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><rect width="16" height="16" fill="#2f9e6b"/></svg>';

test.afterAll(async ({ browser }) => {
  repositoryFile('art/logo.svg', null);
  const { context, page } = await userContext(browser, 'alice');
  await page.request.delete(`${BASE()}/api/projects/demo-app/icon`, { headers: mutate });
  await context.close();
});

test('without an icon the header and the card show the letter tile', async ({ page }) => {
  await login(page, 'alice');
  await page.request.delete(`${BASE()}/api/projects/demo-app/icon`, { headers: mutate });
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(page.getByRole('heading', { name: 'demo-app', exact: true })).toBeVisible();
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
  await expect(page.getByRole('menu')).toContainText('PNG, JPEG, WebP, GIF or SVG, up to 1 MB. Shown as a square.');
  await expect(page.locator('.ph-icon-control input[type="file"]')).toHaveAttribute('accept', /(^|,)\.svg(,|$)/);
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

  // SVG icons: one with only a viewBox and one with only width/height fill the header and the card tile.
  for (const [name, svg] of [['viewbox.svg', viewBoxOnly], ['sized.svg', sizeOnly]] as const) {
    const before = (await headerTile(page).locator('img').getAttribute('src'))!;
    await pick(page, 'Replace image…', svgFile(name, svg));
    await expect(headerTile(page).locator('img')).not.toHaveAttribute('src', before);
    await expectLoadedIcon(page);
    await expectFilledTile(headerTile(page), 28);
    await page.goto(`${BASE()}/`);
    await expectFilledTile(cardTile(page), 32);
    await page.goto(`${BASE()}/p/demo-app`);
  }
  const third = await expectLoadedIcon(page);

  // An SVG with a script is refused by the server; the message is shown and the icon stays.
  await pick(page, 'Replace image…', svgFile('evil.svg', '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><script>alert(1)</script></svg>'));
  const alert = page.getByRole('alert').filter({ hasText: 'The SVG cannot be used' });
  await expect(alert).toBeVisible();
  await expect(alert).toContainText('it contains a <script> element');
  await expect(headerTile(page).locator('img')).toHaveAttribute('src', third);
  await alert.getByRole('button', { name: 'Dismiss' }).click();
  await expect(alert).toHaveCount(0);

  // So is a text file, as not an image at all.
  await pick(page, 'Replace image…', { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('not an image') });
  await expect(page.getByRole('alert')).toContainText('The icon must be a PNG, JPEG, WebP, GIF or SVG image');
  await expect(headerTile(page).locator('img')).toHaveAttribute('src', third);
  expect(second).not.toBe(third);
});

test('an admin takes the icon from a file in the repository', async ({ page }) => {
  repositoryFile('art/logo.svg', viewBoxOnly.replace('#1f6feb', '#d73a4a'));
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  const before = await expectLoadedIcon(page);

  // The menu item sits between Replace and Remove and is reached with the keyboard.
  await iconButton(page).focus();
  await page.keyboard.press('Enter');
  const items = page.getByRole('menuitem');
  await expect(items).toHaveText(['Replace image…', 'Use a file from the repository…', 'Remove icon']);
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitem', { name: 'Use a file from the repository…' })).toBeFocused();
  await page.keyboard.press('Enter');

  const dialog = page.getByRole('dialog', { name: 'Icon from the repository' });
  await expect(dialog).toBeVisible();
  const path = dialog.getByRole('textbox', { name: 'Path in the repository' });
  await expect(path).toBeFocused();
  await expect(dialog).toContainText('Read once from the default branch (main). PNG, JPEG, WebP, GIF or SVG, up to 1 MB.');
  const submit = dialog.getByRole('button', { name: 'Use as icon' });
  await expect(submit).toBeDisabled();

  // Escape closes without a request; focus returns to the icon button and the next opening starts empty.
  await path.fill('art/logo.svg');
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(iconButton(page)).toBeFocused();
  await expect(headerTile(page).locator('img')).toHaveAttribute('src', before);

  await iconButton(page).click();
  await page.getByRole('menuitem', { name: 'Use a file from the repository…' }).click();
  await expect(path).toHaveValue('');

  // An unknown path: the server's message inside the dialog, which stays open with the path kept.
  await path.fill('art/missing.svg');
  await path.press('Enter');
  const error = dialog.getByRole('alert');
  await expect(error).toHaveText('No file `art/missing.svg` on the default branch');
  await expect(path).toHaveValue('art/missing.svg');
  await expect(headerTile(page).locator('img')).toHaveAttribute('src', before);

  // The real file: the dialog closes, the icon changes and focus returns to the icon button.
  await path.fill('art/logo.svg');
  await submit.click();
  await expect(dialog).toBeHidden();
  await expect(headerTile(page).locator('img')).not.toHaveAttribute('src', before);
  await expectLoadedIcon(page);
  await expectFilledTile(headerTile(page), 28);
  await expect(iconButton(page)).toBeFocused();
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
