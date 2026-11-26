// The chat transcript opens at the latest message and stays there while content keeps arriving (slow images, late
// layout), and only the user's own scrolling (wheel, keys) takes it away from the bottom.
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, pngBytes, readState, writeState } from './support';

test.describe.configure({ mode: 'serial' });

const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const log = (page: Page) => page.locator('.chat-log');
const scroller = (page: Page) => page.locator('.chat-scroll');
const jump = (page: Page) => page.getByRole('button', { name: /jump to latest/ });
const thumbs = (page: Page) => log(page).locator('.t-att-img img');
const MESSAGES = 4;

const distance = (page: Page) => scroller(page).evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight);
const lastLines = (page: Page) => log(page).evaluate((el) => (el.textContent?.match(/line 80 of a long answer/g) ?? []).length);
const sessionUrl = () => `${BASE()}/p/demo-app?session=${readState().scrollChat as string}`;

/** Simulates pasting an image into the prompt (as 07-attachments does). */
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

/** Holds the transcript's attachment images until the returned function is called. */
async function holdImages(page: Page): Promise<() => void> {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  await page.route('**/api/sessions/*/attachments/*', async (route) => {
    await gate;
    await route.continue().catch(() => {}); // the page may have navigated meanwhile
  });
  return release;
}

/** Waits until the whole session has been replayed: every long answer is in the transcript. */
async function loaded(page: Page) {
  await expect.poll(() => lastLines(page)).toBe(MESSAGES);
  await expect(log(page).locator('.t-spinner')).toHaveCount(0);
}

/** Grows the transcript without React (as an image loading or a late child render would). */
const growContent = (page: Page) =>
  log(page).evaluate((el) => {
    const filler = document.createElement('div');
    filler.className = 'e2e-filler';
    filler.style.height = '500px';
    el.appendChild(filler);
  });

test('setup: a long session with large images', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  const before = new URL(page.url()).searchParams.get('session');
  await page.getByRole('button', { name: /^(Start chat|\+ New chat)$/ }).first().click();
  await expect.poll(() => new URL(page.url()).searchParams.get('session')).not.toBe(before);
  writeState({ scrollChat: new URL(page.url()).searchParams.get('session')! });
  for (let i = 1; i <= MESSAGES; i++) {
    await pasteFile(page, 'image.png', 'image/png', pngBytes(800, 600));
    await expect(page.locator('.att-chip')).toHaveCount(1);
    await expect(page.locator('.att-chip-bar')).toHaveCount(0);
    await prompt(page).fill(`picture ${i}, and a long answer please`);
    await prompt(page).press('Enter');
    await expect.poll(() => lastLines(page)).toBe(i);
  }
  await expect(thumbs(page)).toHaveCount(MESSAGES);
});

test('opens at the bottom while the images are still loading, and stays there when they arrive', async ({ page }) => {
  await login(page, 'alice');
  const release = await holdImages(page);
  await page.goto(sessionUrl());
  await loaded(page);
  expect(await distance(page)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toHaveCount(0);

  // The thumbnail's box is reserved before the image loads: 800×600 fitted into 220×160.
  const img = thumbs(page).last();
  const box = (await img.boundingBox())!;
  expect(box.width).toBeCloseTo(213, 0);
  expect(box.height).toBeCloseTo(160, 0);
  expect(await img.evaluate((el: HTMLImageElement) => el.naturalWidth)).toBe(0);

  release();
  await expect.poll(() => img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0)).toBe(true);
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toHaveCount(0);
  expect(await img.boundingBox()).toEqual(box);

  // Switching to another chat and back opens at the bottom again.
  const other = readState().aliceChat as string | undefined;
  if (other) {
    await page.goto(`${BASE()}/p/demo-app?session=${other}`);
    await expect(log(page)).toContainText('New chat in agern-e2e/demo-app');
    await page.goto(sessionUrl());
    await loaded(page);
    await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
    await expect(jump(page)).toHaveCount(0);
  }
});

test('a scroll by script does not unstick the view', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(sessionUrl());
  await loaded(page);
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
  // Well past the window in which a scroll counts as the user's after an input event.
  await page.waitForTimeout(1500);
  await scroller(page).evaluate((el) => (el.scrollTop = el.scrollTop - 300));
  await expect.poll(() => distance(page)).toBeGreaterThan(80);
  await page.waitForTimeout(200);
  await expect(jump(page)).toHaveCount(0);
  // The next growth brings the view back to the bottom.
  await growContent(page);
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toHaveCount(0);
});

test('a wheel scroll up is respected while content keeps loading; jump to latest returns', async ({ page }) => {
  await login(page, 'alice');
  const release = await holdImages(page);
  await page.goto(sessionUrl());
  await loaded(page);
  expect(await distance(page)).toBeLessThanOrEqual(1);

  await scroller(page).hover();
  await page.mouse.wheel(0, -600);
  await expect(jump(page)).toBeVisible();
  await expect.poll(() => distance(page)).toBeGreaterThan(80);
  const top = await scroller(page).evaluate((el) => el.scrollTop);

  release();
  await growContent(page);
  await expect.poll(() => thumbs(page).last().evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0)).toBe(true);
  await page.waitForTimeout(300);
  expect(Math.abs((await scroller(page).evaluate((el) => el.scrollTop)) - top)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toBeVisible();

  await jump(page).click();
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toHaveCount(0);
  // Stuck again: later growth is followed.
  await growContent(page);
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
});

test('keyboard: PageUp in the transcript unsticks, End sticks again', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(sessionUrl());
  await loaded(page);
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
  await scroller(page).focus();
  await page.keyboard.press('PageUp');
  await expect(jump(page)).toBeVisible();
  await page.keyboard.press('End');
  await expect(jump(page)).toHaveCount(0);
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
});

test('phone: opens at the bottom and stays there when the keyboard shrinks the viewport', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, baseURL: BASE() });
  const page = await ctx.newPage();
  await login(page, 'alice');
  const release = await holdImages(page);
  await page.goto(sessionUrl());
  await loaded(page);
  expect(await distance(page)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toHaveCount(0);
  // Thumbnails are at most 160 px wide on a phone, with their box reserved.
  const box = (await thumbs(page).last().boundingBox())!;
  expect(box.width).toBeCloseTo(160, 0);
  expect(box.height).toBeCloseTo(120, 0);
  release();
  await expect.poll(() => thumbs(page).last().evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0)).toBe(true);

  await page.setViewportSize({ width: 390, height: 500 });
  await expect.poll(() => distance(page)).toBeLessThanOrEqual(1);
  await expect(jump(page)).toHaveCount(0);
  await ctx.close();
});
