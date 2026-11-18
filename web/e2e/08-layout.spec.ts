// The project page is an app shell: top bar, project header and the chat prompt stay where they are; only
// the transcript (and the side panes) scroll, never the document.
import { mkdirSync } from 'node:fs';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, readState } from './support';

test.describe.configure({ mode: 'serial' });

const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const shots = process.env.E2E_SCREENSHOTS;

async function boxes(page: Page) {
  const box = async (sel: string) => {
    const b = await page.locator(sel).first().boundingBox();
    expect(b, sel).not.toBeNull();
    return b!;
  };
  return { topbar: await box('.topbar'), head: await box('.project-head'), prompt: await box('.prompt-box') };
}

async function checkFixed(page: Page, name: string) {
  const vp = page.viewportSize()!;
  const scroller = page.locator('.chat-scroll');
  // Long enough to scroll: stuck to the bottom while it streams in.
  await expect.poll(() => scroller.evaluate((el) => el.scrollHeight - el.clientHeight)).toBeGreaterThan(400);
  await expect.poll(() => scroller.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThan(80);
  const before = await boxes(page);

  // Scroll the transcript to the top, by wheel over it and then all the way.
  await scroller.hover();
  await page.mouse.wheel(0, -3000);
  await scroller.evaluate((el) => (el.scrollTop = 0));
  await expect(page.getByRole('button', { name: /jump to latest/ })).toBeVisible();
  await expect(page.locator('.chat-log')).toContainText('New chat in agern-e2e/demo-app');

  const after = await boxes(page);
  expect(after).toEqual(before);
  for (const b of Object.values(after)) {
    expect(b.y).toBeGreaterThanOrEqual(0);
    expect(b.y + b.height).toBeLessThanOrEqual(vp.height);
  }
  expect(await page.evaluate(() => document.scrollingElement!.scrollTop)).toBe(0);
  if (shots) {
    mkdirSync(shots, { recursive: true });
    await page.screenshot({ path: `${shots}/long-transcript-scrolled-${name}.png` });
  }
  // The document cannot be scrolled at all.
  await page.mouse.wheel(0, 2000);
  await page.evaluate(() => window.scrollTo(0, 500));
  expect(await page.evaluate(() => document.scrollingElement!.scrollTop)).toBe(0);

  // "jump to latest" brings the bottom back, and the view sticks to it again.
  await page.getByRole('button', { name: /jump to latest/ }).click();
  await expect.poll(() => scroller.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThan(80);
  await expect(page.getByRole('button', { name: /jump to latest/ })).toHaveCount(0);
}

test('desktop: only the transcript scrolls', async ({ page }) => {
  const sessionId = readState().aliceChat as string;
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await prompt(page).fill('a long answer please');
  await prompt(page).press('Enter');
  await expect(page.locator('.chat-log')).toContainText('line 80 of a long answer');
  await checkFixed(page, 'desktop');

  // A tall input (many lines) shrinks the transcript; the prompt stays inside the viewport.
  await prompt(page).fill(Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n'));
  const b = (await page.locator('.prompt-box').boundingBox())!;
  expect(b.y + b.height).toBeLessThanOrEqual(page.viewportSize()!.height);
  expect(b.height).toBeLessThan(page.viewportSize()!.height * 0.4);
  expect(await page.evaluate(() => document.scrollingElement!.scrollTop)).toBe(0);
  await prompt(page).fill('');
});

test('phone: top bar, tabs and prompt stay put', async ({ browser }) => {
  const sessionId = readState().aliceChat as string;
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, baseURL: BASE() });
  const page = await ctx.newPage();
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await expect(page.locator('.project-tabs')).toBeVisible();
  const tabs = await page.locator('.project-tabs').boundingBox();
  await checkFixed(page, 'phone');
  expect(await page.locator('.project-tabs').boundingBox()).toEqual(tabs);
  // No double scrollbars: the chat pane itself does not scroll, only its transcript.
  const overflow = await page.locator('.panel--chat').evaluate((pane) => {
    const bottom = pane.getBoundingClientRect().bottom;
    const out = Array.from(pane.querySelectorAll('*'))
      .filter((el) => !el.closest('.chat-scroll') && el.getBoundingClientRect().height > 0 && el.getBoundingClientRect().bottom > bottom + 1)
      .map((el) => `${el.tagName.toLowerCase()}.${String(el.className)} ends at ${Math.round(el.getBoundingClientRect().bottom)} > ${Math.round(bottom)}`);
    return { scroll: pane.scrollHeight, client: pane.clientHeight, out };
  });
  expect(overflow.scroll, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.client + 1);
  // The issues tab scrolls inside its own pane.
  await page.getByRole('tab', { name: 'issues' }).click();
  await expect(page.locator('.issue-list .issue').first()).toBeVisible();
  expect(await page.evaluate(() => document.scrollingElement!.scrollTop)).toBe(0);
  await ctx.close();
});

test('other pages keep the top bar fixed while the content scrolls', async ({ page }) => {
  await login(page, 'alice');
  await page.setViewportSize({ width: 1024, height: 420 });
  await page.goto(`${BASE()}/settings`);
  const top = (await page.locator('.topbar').boundingBox())!;
  await page.mouse.wheel(0, 800);
  await expect.poll(() => page.evaluate(() => document.scrollingElement!.scrollTop)).toBeGreaterThan(100);
  expect((await page.locator('.topbar').boundingBox())!.y).toBe(top.y);
});
