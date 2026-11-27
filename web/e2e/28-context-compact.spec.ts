import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// The chat's context meter, compaction dividers and /compact (docs/ARCHITECTURE.md §5 and §10), with the fake CLI's
// "fake:context=<n>", "fake:autocompact" and /compact (server/src/test/resources/fake-claude).
test.describe.configure({ mode: 'serial' });

const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const log = (page: Page) => page.locator('.chat-log');
const meter = (page: Page) => page.locator('.chat-footer .cf-context');
const compactButton = (page: Page) => page.getByRole('button', { name: 'Compact conversation' });
const dividers = (page: Page) => log(page).locator('.t-compact');

async function send(page: Page, text: string) {
  await prompt(page).fill(text);
  await prompt(page).press('Enter');
}

async function idle(page: Page) {
  await expect(page.locator('.t-spinner')).toHaveCount(0);
  await expect(prompt(page)).toBeEnabled();
}

test('the context meter, automatic and manual compactions, and the Compact button', async ({ page, browser }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  // A fresh chat: "Start chat" when the project has none yet, "+ New chat" otherwise.
  const newChat = page.getByRole('button', { name: /^(Start chat|\+ New chat)$/ }).first();
  await expect(newChat).toBeVisible();
  const before = new URL(page.url()).searchParams.get('session');
  await newChat.click();
  await expect.poll(() => new URL(page.url()).searchParams.get('session')).not.toBe(before);
  await expect(page).toHaveURL(/\?session=/);
  const sessionId = new URL(page.url()).searchParams.get('session')!;
  await expect(log(page)).toContainText('New chat in agern-e2e/demo-app');
  await expect(log(page)).not.toContainText('You said');

  // A new chat: nothing to compact yet, and the meter does not know anything.
  await expect(compactButton(page)).toBeDisabled();
  await expect(compactButton(page)).toHaveAttribute('title', 'Nothing to compact yet');
  await expect(meter(page)).toContainText('—');

  await send(page, 'hello fake:context=312000');
  await expect(log(page)).toContainText('You said: hello fake:context=312000');
  await expect(meter(page)).toContainText('312k / 1M');
  await expect(meter(page)).toContainText('31%');
  await expect(meter(page)).not.toHaveClass(/cf-context--warn/);
  await expect(meter(page)).toHaveAttribute('title', "312,000 of 1,000,000 tokens in the agent's context (31%)");
  await idle(page);
  await expect(compactButton(page)).toBeEnabled();

  await send(page, 'more fake:context=850000');
  await expect(meter(page)).toContainText('850k / 1M');
  await expect(meter(page)).toHaveClass(/cf-context--warn/);
  await expect(meter(page)).toContainText('nearly full');
  await idle(page);

  // An automatic compaction: a divider, the earlier messages dimmed, the meter down to post_tokens.
  await send(page, 'go fake:autocompact');
  await expect(dividers(page)).toHaveCount(1);
  await expect(dividers(page).first()).toContainText('Conversation compacted here (auto) — 850k → 85k tokens');
  await expect(dividers(page).first()).toContainText('The agent continues from a summary.');
  const compacted = log(page).locator('.t-compacted[role="group"]');
  await expect(compacted).toHaveCount(1);
  await expect(compacted).toContainText('You said: hello fake:context=312000');
  await expect(compacted).not.toContainText('You said: go fake:autocompact');
  await expect(meter(page)).toContainText('85k / 1M');
  await expect(meter(page)).not.toHaveClass(/cf-context--warn/);
  await expect(log(page).locator('.t-unknown', { hasText: 'compact_boundary' })).toHaveCount(0);
  await idle(page);

  // Compact on demand with the button.
  await send(page, 'fill up fake:context=900000');
  await expect(meter(page)).toContainText('900k / 1M');
  await idle(page);
  await compactButton(page).click();
  await expect(log(page).locator('.t-user--cmd').last()).toContainText('/compact');
  await expect(page.locator('.t-spinner')).toContainText('Compacting');
  await expect(compactButton(page)).toBeDisabled();
  await expect(dividers(page).nth(1)).toContainText('Conversation compacted here (manual) — 900k → 90k tokens');
  await expect(meter(page)).toContainText('90k / 1M');
  await idle(page);

  // Typed, with focus instructions: suggested after "/", sent verbatim.
  await prompt(page).fill('/c');
  await expect(page.getByRole('listbox', { name: 'Commands' })).toContainText('/compact [what to keep]');
  await send(page, '/compact keep the deployment decisions');
  await expect(log(page)).toContainText('Compacted. Focus: keep the deployment decisions');
  await expect(dividers(page)).toHaveCount(3);
  await idle(page);

  // Not while the agent works: the button is disabled and the server refuses it.
  await send(page, 'go slow');
  await expect(log(page)).toContainText('1 2 3 ');
  await expect(compactButton(page)).toBeDisabled();
  await expect(compactButton(page)).toHaveAttribute('title', 'Wait for the agent to finish, or interrupt it');
  const busy = await page.request.post(`${BASE()}/api/sessions/${sessionId}/input`, { headers: mutate, data: { text: '/compact' } });
  expect(busy.status()).toBe(409);
  expect((await busy.json()).error.code).toBe('agent_busy');
  await page.keyboard.press('Escape');
  await idle(page);

  // Derived from the stored events: the same after a reload.
  const meterText = await meter(page).innerText();
  await page.reload();
  await expect(dividers(page)).toHaveCount(3);
  await expect(meter(page)).toHaveText(meterText);
  await expect(log(page).locator('.t-compacted')).toHaveCount(1);

  // An observer sees the meter, but cannot compact.
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await expect(bob.page.locator('.chat-banner')).toContainText('observing alice');
  await expect(meter(bob.page)).toHaveText(meterText);
  await expect(compactButton(bob.page)).toHaveCount(0);
  await bob.context.close();
});
