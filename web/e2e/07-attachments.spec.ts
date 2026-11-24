// Chat attachments end to end: paste, pick and drop files, chips with progress and per-file errors, send,
// and proof from the fake Claude CLI that the agent got the image blocks and the files on disk.
import { mkdirSync } from 'node:fs';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, pngBytes, readState, userContext } from './support';

test.describe.configure({ mode: 'serial' });

const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const log = (page: Page) => page.locator('.chat-log');
const chips = (page: Page) => page.locator('.att-chip');
const shots = process.env.E2E_SCREENSHOTS;

/** Simulates pasting a file (e.g. a screenshot) into the prompt. */
async function pasteFile(page: Page, name: string, type: string, data: Buffer) {
  await prompt(page).evaluate(
    (el, f) => {
      const file = new File([Uint8Array.from(atob(f.b64), (c) => c.charCodeAt(0))], f.name, { type: f.type });
      const dt = new DataTransfer();
      dt.items.add(file);
      el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
    },
    { name, type, b64: data.toString('base64') },
  );
}

/** Drags a file over the chat pane, checks the drop target shows, and drops it. */
async function dropFile(page: Page, name: string, type: string, text: string) {
  const dt = await page.evaluateHandle(
    (f) => {
      const d = new DataTransfer();
      d.items.add(new File([f.text], f.name, { type: f.type }));
      return d;
    },
    { name, type, text },
  );
  const pane = page.locator('section.chat');
  await pane.dispatchEvent('dragenter', { dataTransfer: dt });
  await pane.dispatchEvent('dragover', { dataTransfer: dt });
  await expect(page.locator('.chat-drop')).toContainText('Drop files to attach');
  await pane.dispatchEvent('drop', { dataTransfer: dt });
  await expect(page.locator('.chat-drop')).toHaveCount(0);
}

test('compose with pasted, picked and dropped files; per-file errors; send; the agent receives them', async ({ page }) => {
  const sessionId = readState().aliceChat as string;
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await expect(prompt(page)).toBeVisible();

  // Paste a screenshot: it is uploaded at once and shown as a thumbnail chip with a generated name.
  await pasteFile(page, 'image.png', 'image/png', pngBytes(32, 16));
  await expect(chips(page)).toHaveCount(1);
  await expect(chips(page).first()).toContainText(/screenshot-\d{8}-\d{6}\.png/);
  await expect(chips(page).first().locator('img')).toBeVisible();

  // Pick a file with the attach button.
  await page.getByRole('region', { name: 'Chat' }).locator('input[type=file]').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('hello agent\n') });
  // Drop one on the chat pane.
  await dropFile(page, 'dropped.csv', 'text/csv', 'a,b\n1,2\n');
  await expect(chips(page)).toHaveCount(3);
  await expect(chips(page).filter({ hasText: 'dropped.csv' })).toContainText('8 B');

  // A file over the limit fails on its own chip and blocks sending until it is removed.
  await page.getByRole('region', { name: 'Chat' }).locator('input[type=file]').setInputFiles({ name: 'huge.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(21 * 1024 * 1024) });
  const huge = chips(page).filter({ hasText: 'huge.bin' });
  await expect(huge).toContainText('Too large');
  await expect(page.locator('.prompt-blocked')).toContainText('Remove the attachments that failed');
  await expect(page.getByRole('button', { name: 'Send (Enter)' })).toBeDisabled();
  await huge.getByRole('button', { name: 'Remove huge.bin' }).click();

  // Removing works from the keyboard too.
  const dropped = chips(page).filter({ hasText: 'dropped.csv' });
  await dropped.getByRole('button', { name: 'Remove dropped.csv' }).focus();
  await page.keyboard.press('Enter');
  await expect(chips(page)).toHaveCount(2);
  await expect(chips(page).locator('.att-chip-bar')).toHaveCount(0); // uploads finished

  if (shots) {
    mkdirSync(shots, { recursive: true });
    await prompt(page).fill('What is in this screenshot?\nAnd read the notes, please.');
    await page.screenshot({ path: `${shots}/composer-attachments-desktop.png` });
    await prompt(page).fill('');
  }

  // /factory commands do not take attachments: refused with a clear message, chips stay.
  await prompt(page).fill('/factory status');
  await prompt(page).press('Escape');
  await prompt(page).press('Enter');
  await expect(page.locator('.chat-error')).toContainText('/factory commands do not take attachments');
  await expect(chips(page)).toHaveCount(2);
  await page.locator('.chat-error').getByRole('button', { name: 'dismiss' }).click();

  await prompt(page).fill('what do you see');
  await prompt(page).press('Enter');
  await expect(chips(page)).toHaveCount(0);
  const msg = log(page).locator('.t-user').filter({ hasText: 'what do you see' });
  await expect(msg.locator('.t-att-img img')).toBeVisible();
  await expect(msg.locator('.t-att-file')).toContainText('notes.txt');

  // The fake CLI decoded the image block and found the file on disk, outside the repository.
  await expect(log(page)).toContainText('IMAGE 1: image/png 32x16');
  await expect(log(page)).toContainText('FILE ok notes.txt 12 bytes');
  await expect(log(page)).toContainText(/FILE ok screenshot-\d{8}-\d{6}\.png \d+ bytes/);
  await expect(log(page)).not.toContainText('INSIDE THE REPO');
  await expect(log(page)).not.toContainText('FILE missing');

  // A message of attachments only (no text) is fine too.
  await page.getByRole('region', { name: 'Chat' }).locator('input[type=file]').setInputFiles({ name: 'only.txt', mimeType: 'text/plain', buffer: Buffer.from('just a file') });
  await expect(chips(page).locator('.att-chip-bar')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Send (Enter)' })).toBeEnabled();
  await page.getByRole('button', { name: 'Send (Enter)' }).click();
  await expect(log(page)).toContainText('FILE ok only.txt 11 bytes');

  // The thumbnail opens larger.
  await msg.locator('.t-att-img').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('.att-lightbox img')).toBeVisible();
  await expect(dialog).toContainText('32×16');
  await page.keyboard.press('Escape');

  // Replay after reload shows the same attachments.
  await page.reload();
  const again = log(page).locator('.t-user').filter({ hasText: 'what do you see' });
  await expect(again.locator('.t-att-img img')).toBeVisible();
  expect(await again.locator('.t-att-img img').evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(32);
  const href = await again.locator('.t-att-file').getAttribute('href');
  const file = await page.request.get(`${BASE()}${href}`);
  expect(file.status()).toBe(200);
  expect(file.headers()['content-disposition']).toMatch(/^attachment;/);
  expect(file.headers()['x-content-type-options']).toBe('nosniff');
  expect(await file.text()).toBe('hello agent\n');
});

test('observers see the attachments, read-only; others cannot upload', async ({ browser }) => {
  const sessionId = readState().aliceChat as string;
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await expect(bob.page.locator('.chat-banner')).toContainText('observing alice');
  const msg = log(bob.page).locator('.t-user').filter({ hasText: 'what do you see' });
  await expect(msg.locator('.t-att-img img')).toBeVisible();
  await expect(msg.locator('.t-att-file')).toContainText('notes.txt');
  // No attach affordances for observers.
  await expect(bob.page.getByRole('button', { name: 'Attach files' })).toHaveCount(0);
  await expect(bob.page.locator('input[type=file]')).toHaveCount(0);
  const r = await bob.page.request.post(`${BASE()}/api/sessions/${sessionId}/attachments?name=x.txt`, { headers: mutate, data: Buffer.from('x') });
  expect(r.status()).toBe(403);
  if (shots) {
    await bob.page.setViewportSize({ width: 390, height: 844 });
    await msg.scrollIntoViewIfNeeded();
    await bob.page.screenshot({ path: `${shots}/transcript-attachments-phone-observer.png` });
  }
  await bob.context.close();
});

test('phone width: composer with attachments', async ({ browser }) => {
  test.skip(!shots, 'screenshots only');
  const sessionId = readState().aliceChat as string;
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, baseURL: BASE() });
  const page = await ctx.newPage();
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await pasteFile(page, 'image.png', 'image/png', pngBytes(64, 48));
  await page.getByRole('region', { name: 'Chat' }).locator('input[type=file]').setInputFiles([
    { name: 'a-rather-long-file-name-for-a-phone.log', mimeType: 'text/plain', buffer: Buffer.from('x') },
    { name: 'huge.bin', mimeType: 'application/octet-stream', buffer: Buffer.alloc(21 * 1024 * 1024) },
  ]);
  await expect(chips(page)).toHaveCount(3);
  await prompt(page).fill('Look at these');
  await page.screenshot({ path: `${shots}/composer-attachments-phone.png` });
  await ctx.close();
});
