import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, readState, userContext, writeState } from './support';

test.describe.configure({ mode: 'serial' });

const prompt = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const log = (page: Page) => page.locator('.chat-log');

async function send(page: Page, text: string) {
  await prompt(page).fill(text);
  await prompt(page).press('Enter');
}

test('project page lists and filters issues', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  const issues = page.locator('.issue-list .issue');
  await expect(issues).toHaveCount(3);
  await expect(issues.first()).toContainText('#3');

  await page.getByRole('radio', { name: /^bug/ }).click();
  await expect(issues).toHaveCount(1);
  await expect(issues).toContainText('Crash when the list is empty');
  await page.getByRole('radio', { name: /^feature/ }).click();
  await expect(issues).toHaveCount(1);
  await expect(issues).toContainText('Add a dark mode toggle');
  await page.getByRole('radio', { name: /^task/ }).click();
  await expect(issues).toContainText('Update dependencies');
  await page.getByRole('radio', { name: 'closed' }).click();
  await expect(page.getByText('No closed tasks.')).toBeVisible();
});

test('chat: new chat, streamed reply, tool call, interrupt with Esc, /factory status', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await page.getByRole('button', { name: 'Start chat' }).click();
  await expect(page).toHaveURL(/\?session=/);
  const sessionId = new URL(page.url()).searchParams.get('session')!;
  writeState({ aliceChat: sessionId });
  await expect(log(page)).toContainText('New chat in agern-e2e/demo-app');

  // A plain message: the reply is streamed in and then completed (result footer with cost).
  await send(page, 'hello agern');
  await expect(log(page).locator('.t-user').last()).toContainText('hello agern');
  await expect(log(page)).toContainText('You said: hello agern');
  await expect(page.locator('.chat-footer')).toContainText('$0.01');
  await expect(prompt(page)).toBeEnabled();
  // The header shows the commit of the checkout the agent works in (fake mode: real local git, always current).
  await expect(page.locator('.chat-head-title')).toHaveText(/~\/demo-app · [0-9a-f]{7}/);
  await expect(page.locator('.chat-head-behind')).toHaveCount(0);

  // `tool` makes the fake CLI run a Bash tool: rendered as ⏺ Bash(command) with its result.
  await send(page, 'use a tool please');
  const tool = log(page).locator('.t-tool').last();
  await expect(tool.locator('.t-tool-name')).toHaveText('Bash');
  await expect(tool.locator('.t-tool-args')).toHaveText('(echo hello from fake-claude && pwd)');
  await expect(tool.locator('.t-result')).toContainText('hello from fake-claude');
  await expect(log(page)).toContainText('You said: use a tool please');

  // `slow` streams for ~10 s; partial text shows up while the agent is busy, and Esc interrupts it.
  await send(page, 'go slow');
  const spinner = page.locator('.t-spinner');
  await expect(spinner).toContainText('esc to interrupt');
  await expect(log(page)).toContainText('1 2 3 ');
  await page.keyboard.press('Escape');
  await expect(log(page)).toContainText('Interrupted by alice');
  await expect(spinner).toHaveCount(0);
  await expect(log(page)).not.toContainText('38 39 40');
  const session = await (await page.request.get(`${BASE()}/api/sessions/${sessionId}`)).json();
  expect(session.status).toBe('idle');
  expect(session.live).toBe(true);

  // /factory commands are answered by the server.
  await send(page, '/factory status');
  await expect(log(page)).toContainText('No runs in demo-app yet.');
});

test('another user observes the chat live and read-only, and cannot send input', async ({ browser }) => {
  const sessionId = readState().aliceChat as string;
  const alice = await userContext(browser, 'alice');
  const bob = await userContext(browser, 'bob');
  await alice.page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);
  await bob.page.goto(`${BASE()}/p/demo-app?session=${sessionId}`);

  await expect(bob.page.locator('.chat-banner')).toContainText('observing alice');
  await expect(bob.page.locator('.chat-banner')).toContainText('read-only');
  await expect(prompt(bob.page)).toHaveCount(0);
  await expect(log(bob.page)).toContainText('You said: hello agern');
  await expect(bob.page.locator('.chat-footer')).toContainText('live');

  // Alice types; Bob sees it arrive without reloading.
  await send(alice.page, 'hello bob, are you watching');
  await expect(log(bob.page)).toContainText('You said: hello bob, are you watching');

  // The API refuses Bob's input, interrupt and stop.
  const api = bob.page.request;
  expect((await api.post(`${BASE()}/api/sessions/${sessionId}/input`, { headers: mutate, data: { text: 'let me drive' } })).status()).toBe(403);
  expect((await api.post(`${BASE()}/api/sessions/${sessionId}/interrupt`, { headers: mutate })).status()).toBe(403);
  expect((await api.post(`${BASE()}/api/sessions/${sessionId}/stop`, { headers: mutate })).status()).toBe(403);
  await expect(log(alice.page)).not.toContainText('let me drive');

  await alice.context.close();
  await bob.context.close();
});

test('closing a chat from its header removes it for the owner; a watcher sees that it was closed', async ({ browser }) => {
  const aliceChat = readState().aliceChat as string;
  const alice = await userContext(browser, 'alice');
  const bob = await userContext(browser, 'bob');
  await alice.page.goto(`${BASE()}/p/demo-app?session=${aliceChat}`);
  await alice.page.locator('.chat-head').getByRole('button', { name: '+ New chat' }).click();
  await expect(alice.page).not.toHaveURL(new RegExp(`session=${aliceChat}`));
  await expect(alice.page).toHaveURL(/\?session=/);
  const doomed = new URL(alice.page.url()).searchParams.get('session')!;
  await send(alice.page, 'hello before closing');
  await expect(log(alice.page)).toContainText('You said: hello before closing');
  expect((await (await alice.page.request.get(`${BASE()}/api/sessions/${doomed}`)).json()).live).toBe(true);

  // Bob may watch, but neither the header nor the list offers him a close action.
  await bob.page.goto(`${BASE()}/p/demo-app?session=${doomed}`);
  await expect(log(bob.page)).toContainText('You said: hello before closing');
  await expect(bob.page.locator('.chat-head').getByRole('button', { name: 'Close chat' })).toHaveCount(0);
  const bobRow = bob.page.locator(`.chat-list li[data-session-id="${doomed}"]`);
  await expect(bobRow).toBeVisible();
  await expect(bobRow.getByRole('button', { name: /^Close chat:/ })).toHaveCount(0);

  // Cancel does nothing; confirming closes it.
  await alice.page.locator('.chat-head').getByRole('button', { name: 'Close chat' }).click();
  const dialog = alice.page.locator('dialog[open]');
  await expect(dialog).toContainText('permanently deletes this chat with its transcript and attachments');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(alice.page.locator('dialog[open]')).toHaveCount(0);
  await expect(alice.page).toHaveURL(new RegExp(`session=${doomed}`));
  await alice.page.locator('.chat-head').getByRole('button', { name: 'Close chat' }).click();
  await alice.page.locator('dialog[open]').getByRole('button', { name: 'Close chat' }).click();

  await expect(alice.page.locator('dialog[open]')).toHaveCount(0);
  await expect(alice.page).not.toHaveURL(/session=/);
  await expect(alice.page.locator(`.chat-list li[data-session-id="${doomed}"]`)).toHaveCount(0);
  await expect(alice.page.locator(`.chat-pick option[value="${doomed}"]`)).toHaveCount(0);
  await expect(alice.page.locator('.chat-pick select')).toHaveValue(aliceChat);
  await expect(alice.page.getByText('This chat was closed')).toHaveCount(0);
  expect((await alice.page.request.get(`${BASE()}/api/sessions/${doomed}`)).status()).toBe(404);

  // Bob is told, without an error, and can dismiss it.
  const notice = bob.page.getByRole('status').filter({ hasText: 'This chat was closed' });
  await expect(notice).toBeVisible();
  await expect(notice).toContainText('The transcript has been deleted.');
  await expect(bob.page.locator(`.chat-list li[data-session-id="${doomed}"]`)).toHaveCount(0);
  await notice.getByRole('button', { name: 'Dismiss' }).click();
  await expect(bob.page.getByText('This chat was closed')).toHaveCount(0);
  await expect(bob.page).not.toHaveURL(/session=/);

  // The server refuses Bob closing Alice's chat.
  expect((await bob.page.request.delete(`${BASE()}/api/sessions/${aliceChat}`, { headers: mutate })).status()).toBe(403);

  await alice.context.close();
  await bob.context.close();
});

test('a chat can be closed from the chat list', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.chat-head').getByRole('button', { name: '+ New chat' }).click();
  await expect(page).toHaveURL(/\?session=/);
  const id = new URL(page.url()).searchParams.get('session')!;
  const row = page.locator(`.chat-list li[data-session-id="${id}"]`);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: /^Close chat:/ }).click();
  await page.locator('dialog[open]').getByRole('button', { name: 'Close chat' }).click();
  await expect(row).toHaveCount(0);
  await expect(page.locator('dialog[open]')).toHaveCount(0);
  expect((await page.request.get(`${BASE()}/api/sessions/${id}`)).status()).toBe(404);
});
