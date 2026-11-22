import { expect, test, type Page } from 'playwright/test';
import { BASE, login, readState, userContext, writeState } from './support';

// Generated images (docs/ARCHITECTURE.md §6 "Generated images") against the real server with the fake image provider.
test.describe.configure({ mode: 'serial' });

const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const log = (page: Page) => page.locator('.chat-log');
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const pill = (page: Page) => page.locator('.rh-title .pill');

async function newIssue(page: Page, title: string, body: string): Promise<number> {
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByText('feature', { exact: true }).click();
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel(/Description/).fill(body);
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  const row = page.locator('.issue-list .issue', { hasText: title });
  const num = await row.locator('.issue-num').innerText();
  return Number(num.replace('#', ''));
}

async function startRunFor(page: Page, title: string) {
  await page.goto(`${BASE()}/p/demo-app`);
  const row = page.locator('.issue-list .issue', { hasText: title });
  await row.getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await expect(page.locator('.rh-title h1')).toContainText(title);
}

test('Settings: a rejected key shows the error; save, remove and save again', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/settings#images`);
  const section = page.locator('#images');
  await expect(section.getByRole('heading', { name: 'Image generation' })).toBeVisible();
  await expect(section).toContainText('not set — agents cannot generate images');
  await expect(section).toContainText('Limits: up to 4 images per request, 8 per chat message and 12 per run stage.');
  await section.getByText('Fake (development)', { exact: true }).click();
  await expect(section.getByLabel('Model')).toHaveValue('fake-image-1');

  await section.getByLabel('API key').fill('not-a-fake-key');
  await section.getByRole('button', { name: 'Save' }).click();
  await expect(section.getByRole('alert')).toHaveText('Fake (development) rejected the API key. Check that it is complete and active.');
  await expect(section).toContainText('not set');

  await section.getByLabel('API key').fill('fake-image-key');
  await section.getByRole('button', { name: 'Save' }).click();
  await expect(section.getByRole('status')).toHaveText('Saved. New image requests will use it.');
  await expect(section).toContainText('Fake (development) · fake-image-1');
  // The key is never shown again.
  await expect(section.getByLabel('API key')).toHaveValue('');

  await section.getByRole('button', { name: 'Remove' }).click();
  await expect(section.getByRole('status')).toHaveText('Removed.');
  await expect(section).toContainText('not set — agents cannot generate images');

  await section.getByText('Fake (development)', { exact: true }).click();
  await section.getByLabel('API key').fill('fake-image-key');
  await section.getByRole('button', { name: 'Save' }).click();
  await expect(section).toContainText('Fake (development) · fake-image-1');
});

test('chat: images show inline, open full size, and one is selected for an issue', async ({ page }) => {
  await login(page, 'alice');
  const issue = await newIssue(page, 'Our app needs an icon', 'Make a cool icon for the app.');
  writeState({ imageIssue: issue });
  // A fresh chat: "Start chat" when the project has none yet, "+ New chat" otherwise.
  const before = new URL(page.url()).searchParams.get('session');
  await page.getByRole('button', { name: /^(Start chat|\+ New chat)$/ }).first().click();
  await expect.poll(() => new URL(page.url()).searchParams.get('session')).not.toBe(before);
  await expect(page).toHaveURL(/\?session=/);
  writeState({ imageChat: new URL(page.url()).searchParams.get('session')! });

  await prompt(page).fill(`fake:image=2 #${issue}`);
  await prompt(page).press('Enter');
  const block = log(page).locator('.t-gen');
  await expect(block).toContainText('Generated 2 images with Fake (development) · fake-image-1', { timeout: 20_000 });
  await expect(block).toContainText('app icon');
  await block.getByText('Prompt', { exact: true }).click();
  await expect(block).toContainText('A friendly squirrel app icon');
  const tiles = block.locator('.t-gen-tile');
  await expect(tiles).toHaveCount(2);

  // Full size in a dialog.
  await block.getByRole('button', { name: 'Open image app icon 1' }).click();
  const lightbox = page.getByRole('dialog', { name: 'app icon' });
  const img = lightbox.locator('img');
  await expect.poll(() => img.evaluate((el) => (el as HTMLImageElement).naturalWidth)).toBe(256);
  await expect(lightbox).toContainText('image/png · 256×256');
  await expect(lightbox.getByRole('link', { name: 'Open full size ↗' })).toHaveAttribute('target', '_blank');
  await page.keyboard.press('Escape');
  await expect(lightbox).toBeHidden();

  // Use the first for the issue, discard the second.
  await tiles.nth(0).getByRole('button', { name: 'Use for issue…' }).click();
  const dialog = page.getByRole('dialog', { name: 'Use image for an issue' });
  await expect(dialog.getByLabel('Issue number')).toHaveValue(String(issue));
  await expect(dialog.getByLabel('Name')).toHaveValue('app icon');
  await dialog.getByLabel('Name').fill('App icon');
  await dialog.getByRole('button', { name: 'Use for issue' }).click();
  await expect(dialog).toBeHidden();
  await expect(tiles.nth(0)).toContainText(`selected for #${issue} as “App icon”`);
  await expect(tiles.nth(0).getByRole('button', { name: `Remove from #${issue}` })).toBeVisible();
  await tiles.nth(1).getByRole('button', { name: 'Discard' }).click();
  await expect(tiles.nth(1)).toContainText('discarded');

  // The issue list shows the selection after a reload.
  await page.goto(`${BASE()}/p/demo-app`);
  const row = page.locator('.issue-list .issue', { hasText: 'Our app needs an icon' });
  await row.getByRole('button', { name: '1 image' }).click();
  const list = page.getByRole('dialog', { name: `Images for #${issue}` });
  await expect(list).toContainText('App icon');
  await expect(list).toContainText('purpose: app icon');
  await expect(list).toContainText('selected by alice');
  await expect(list.getByRole('button', { name: 'Remove' })).toBeVisible();
  await page.keyboard.press('Escape');
});

test('another user sees the chat images read-only', async ({ browser }) => {
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app?session=${readState().imageChat as string}`);
  const block = log(bob.page).locator('.t-gen');
  await expect(block).toContainText('Generated 2 images');
  await expect(block.locator('.t-gen-tile img')).toHaveCount(1);
  await expect(block).toContainText('selected for #');
  await expect(block.getByRole('button', { name: /Use for issue|Discard|Remove from/ })).toHaveCount(0);
  await bob.context.close();
});

test('runs: prepared images reach the builder, and run images show under their stage', async ({ page }) => {
  await login(page, 'alice');
  await startRunFor(page, 'Our app needs an icon');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });
  await node(page, 'builder').click();
  await expect(page.getByText(/PREPARED ok \.agern-run\/images\/prepared-01-app-icon\.png \d+/).first()).toBeVisible();

  await newIssue(page, 'Empty state art', 'We need some nice graphics for this page. fake:image');
  await startRunFor(page, 'Empty state art');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });
  const detail = page.locator('.stage-detail');
  await node(page, 'builder').click();
  await expect(detail.getByRole('heading', { name: /Generated images/ })).toContainText('1');
  await expect(detail.locator('.shots figcaption')).toHaveText(['app icon']);
  await detail.locator('.shots').getByText('Prompt', { exact: true }).click();
  await expect(detail.locator('.shots')).toContainText('A friendly squirrel app icon');
  await detail.getByRole('button', { name: 'Open image: app icon' }).click();
  const dialog = page.getByRole('dialog', { name: 'Generated image' });
  await expect(dialog).toContainText('image/png');
  await page.keyboard.press('Escape');
  // Stages that generated nothing have no such section.
  await node(page, 'security').click();
  await expect(detail.getByRole('heading', { name: /Generated images/ })).toHaveCount(0);
});

test('Settings: Codex is connected with a ChatGPT device login and removed again', async ({ page }) => {
  // Bob, so that alice's fake provider stays as the tests above left it.
  await login(page, 'bob');
  await page.goto(`${BASE()}/settings#images`);
  const section = page.locator('#images');
  await expect(section).toContainText('not set — agents cannot generate images');
  await section.getByText('OpenAI Codex', { exact: true }).click();
  await expect(section.getByLabel('API key')).toHaveCount(0);
  await expect(section.getByLabel('Model')).toHaveCount(0);
  await expect(section).toContainText('Codex generates images on your own ChatGPT account.');

  await section.getByRole('button', { name: 'Connect ChatGPT' }).click();
  await expect(section.getByRole('link', { name: 'https://auth.openai.com/codex/device' })).toHaveAttribute('target', '_blank');
  await expect(section.locator('code', { hasText: 'FAKE-12345' })).toBeVisible();
  await expect(section.getByRole('status')).toHaveText('Waiting for you to finish signing in…');

  // The fake CLI finishes the login after a second; the page polls every 3 s.
  await expect(section.getByRole('status')).toHaveText('Connected. New image requests will use it.', { timeout: 15_000 });
  await expect(section).toContainText('OpenAI Codex · ChatGPT login');
  await expect(section.getByRole('button', { name: 'Reconnect' })).toBeVisible();

  await section.getByRole('button', { name: 'Remove' }).click();
  await expect(section.getByRole('status')).toHaveText('Removed.');
  await expect(section).toContainText('not set — agents cannot generate images');
});
