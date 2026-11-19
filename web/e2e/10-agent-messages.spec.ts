import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, readState, userContext, writeState } from './support';

// Messages from a run's owner to its running builder: from the run page and through the owner's chat agent.
// `fake:busy-builder` keeps the fake builder working (Bash steps of ~0.4 s) until a message says "finish".
test.describe.configure({ mode: 'serial' });

const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const detail = (page: Page) => page.locator('.stage-detail');
const composer = (page: Page) => page.getByRole('textbox', { name: 'Message the builder' });

async function openBuilder(page: Page, runId: string) {
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}?stage=builder`);
  await expect(detail(page).locator('.sd-role')).toHaveText('builder');
}

test('the owner messages the running builder from the run page', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByLabel('Title').fill('Steer the builder');
  await dialog.getByLabel(/Description/).fill('A button. fake:busy-builder');
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await page.locator('.issue-list .issue', { hasText: 'Steer the builder' }).getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  const runId = new URL(page.url()).pathname.split('/').pop()!;
  writeState({ steerRun: runId });

  await expect(node(page, 'builder')).toHaveAttribute('aria-label', /^builder: running/);
  await node(page, 'builder').click();
  await expect(detail(page).locator('.chat-log')).toContainText('working, step 1');
  await expect(detail(page).locator('.chat-steer-note')).toContainText('queued and picked up at its next step');

  await composer(page).fill('make the button blue');
  await composer(page).press('Enter');
  const msg = detail(page).locator('.t-user', { hasText: 'make the button blue' });
  await expect(msg.locator('.t-user-fwd')).toContainText('alice · direct');
  await expect(msg.locator('.t-user-fwd')).toContainText('delivered');
  await expect(detail(page).locator('.chat-log')).toContainText("Noted the owner's message: make the button blue");
  await expect(composer(page)).toHaveValue('');
});

test('the owner messages the builder through their chat agent', async ({ page }) => {
  const runId = readState().steerRun as string;
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await page.getByRole('button', { name: '+ New chat' }).click();
  await expect(page).toHaveURL(/\?session=/);
  const chatId = new URL(page.url()).searchParams.get('session')!;
  const prompt = page.getByRole('textbox', { name: 'Message the agent' });
  await prompt.fill(`forward ${runId} builder: use rounded corners too`);
  await prompt.press('Enter');
  await expect(page.locator('.chat-log')).toContainText('Forwarded. Queued for the builder');

  await openBuilder(page, runId);
  const msg = detail(page).locator('.t-user', { hasText: 'use rounded corners too' });
  await expect(msg.locator('.t-user-fwd')).toContainText(`alice · via chat ${chatId.slice(0, 8)}`);
  await expect(msg.locator('.t-user-fwd')).toContainText('delivered');
  await expect(detail(page).locator('.chat-log')).toContainText("Noted the owner's message: use rounded corners too");
  // E2E_SCREENSHOTS=<dir>: the run page with the composer under the builder's transcript.
  if (process.env.E2E_SCREENSHOTS) await page.screenshot({ path: `${process.env.E2E_SCREENSHOTS}/run-message-builder.png`, fullPage: true });
});

test('another user cannot message the builder; finished agents and reviewers refuse messages', async ({ page, browser }) => {
  const runId = readState().steerRun as string;
  const bob = await userContext(browser, 'bob');
  await openBuilder(bob.page, runId);
  await expect(bob.page.locator('.chat-banner')).toContainText('observing alice');
  await expect(detail(bob.page).locator('.chat-log')).toContainText('make the button blue');
  await expect(composer(bob.page)).toHaveCount(0);

  const run = await (await bob.page.request.get(`${BASE()}/api/runs/${runId}`)).json();
  const builderSession = run.stages.find((s: { role: string }) => s.role === 'builder').sessionId as string;
  const direct = await bob.page.request.post(`${BASE()}/api/sessions/${builderSession}/input`, { headers: mutate, data: { text: 'bob was here' } });
  expect(direct.status()).toBe(403);

  // Bob's own chat agent is refused too.
  const chat = await (await bob.page.request.post(`${BASE()}/api/projects/demo-app/sessions`, { headers: mutate, data: {} })).json();
  expect((await bob.page.request.post(`${BASE()}/api/sessions/${chat.id}/input`, { headers: mutate, data: { text: `forward ${runId} builder: bob was here` } })).status()).toBe(202);
  await expect
    .poll(async () => JSON.stringify(await (await bob.page.request.get(`${BASE()}/api/sessions/${chat.id}/events`)).json()), { timeout: 15_000 })
    .toContain('Could not forward it: Only the owner of this run');

  // Alice tells the builder to finish; the run completes and the builder's result lists every message.
  await login(page, 'alice');
  await openBuilder(page, runId);
  await expect(detail(page).locator('.chat-log')).not.toContainText('bob was here');
  await composer(page).fill('that is all, finish');
  await composer(page).press('Enter');
  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 45_000 });
  await expect(detail(page).locator('.chat-log')).toContainText('Owner messages handled: make the button blue; use rounded corners too; that is all, finish');
  await expect(composer(page)).toHaveCount(0);
  await expect(detail(bob.page).locator('.chat-log')).toContainText('that is all, finish');

  const late = await page.request.post(`${BASE()}/api/sessions/${builderSession}/input`, { headers: mutate, data: { text: 'one more' } });
  expect(late.status()).toBe(409);
  expect((await late.json()).error.code).toBe('agent_not_running');
  const done = await (await page.request.get(`${BASE()}/api/runs/${runId}`)).json();
  const security = done.stages.find((s: { role: string }) => s.role === 'security').sessionId as string;
  const reviewer = await page.request.post(`${BASE()}/api/sessions/${security}/input`, { headers: mutate, data: { text: 'pass it' } });
  expect(reviewer.status()).toBe(409);
  expect((await reviewer.json()).error.code).toBe('agent_does_not_accept_messages');
  await bob.context.close();
});
