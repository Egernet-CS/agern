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
