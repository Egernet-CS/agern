import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, readState, userContext, writeState } from './support';

// The conversation of a run that needs a human (docs/ARCHITECTURE.md §6 "Conversation"). `fake:escalate` makes the fake
// approver escalate. The fake advisor answers "Advisor: <text>" with the reason from its run context and the output of
// `git diff --stat`; a message containing "please change" makes it commit, after which the server reviews the change again.
test.describe.configure({ mode: 'serial' });

const pill = (page: Page) => page.locator('.rh-title .pill');
const panel = (page: Page) => page.locator('.conv-panel');
const composer = (page: Page) => page.getByRole('textbox', { name: 'Message the agent' });
const approve = (page: Page) => page.getByRole('button', { name: '✓ Approve & merge' });

test('the owner asks the agent why the run needs a human', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByLabel('Title').fill('Talk it through');
  await dialog.getByLabel(/Description/).fill('The approver will escalate. fake:escalate fake:slow');
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await page.locator('.issue-list .issue', { hasText: 'Talk it through' }).getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  const runId = new URL(page.url()).pathname.split('/').pop()!;
  writeState({ conversationRun: runId });
  await expect(pill(page)).toHaveText('needs human', { timeout: 45_000 });

  await page.getByRole('button', { name: 'Talk to the agent' }).click();
  await expect(page).toHaveURL(/stage=human/);
  await expect(panel(page).getByRole('heading', { name: 'Conversation' })).toBeVisible();
  await expect(panel(page)).toContainText('No conversation yet. Ask why the run needs a human, or ask for a change.');
  await expect(composer(page)).toBeFocused();
  await composer(page).fill('Why did the approver escalate?');
  await composer(page).press('Enter');

  const log = panel(page).locator('.chat-log');
  await expect(log).toContainText('Advisor: Why did the approver escalate?', { timeout: 30_000 });
  await expect(log).toContainText('The approver escalated');
  await expect(log).toContainText('AGERN_FAKE.md');
  // The transcript shows the owner's words, not the run context the agent was given.
  await expect(log.locator('.t-user')).toHaveCount(1);
  await expect(log).not.toContainText('<run_context>');
  await expect(panel(page).getByRole('status').first()).toContainText('No changes since the last review.');
  await expect(pill(page)).toHaveText('needs human');
  await expect(approve(page)).toBeEnabled();
  if (process.env.E2E_SCREENSHOTS) await page.screenshot({ path: `${process.env.E2E_SCREENSHOTS}/run-conversation.png`, fullPage: true });
});

test('another user sees the conversation read-only and cannot talk to the agent', async ({ browser }) => {
  const runId = readState().conversationRun as string;
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app/runs/${runId}?stage=human`);
  await expect(panel(bob.page).locator('.chat-log')).toContainText('Advisor: Why did the approver escalate?');
  await expect(bob.page.locator('.chat-banner')).toContainText('observing alice');
  await expect(composer(bob.page)).toHaveCount(0);
  await expect(bob.page.getByRole('button', { name: 'Talk to the agent' })).toHaveCount(0);
  const res = await bob.page.request.post(`${BASE()}/api/runs/${runId}/conversation`, { headers: mutate, data: { text: 'bob was here' } });
  expect(res.status()).toBe(403);
  const review = await bob.page.request.post(`${BASE()}/api/runs/${runId}/review`, { headers: mutate });
  expect(review.status()).toBe(403);
  await bob.context.close();
});

test('a requested change is reviewed again before the run can be approved', async ({ page }) => {
  const runId = readState().conversationRun as string;
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  // With a conversation, the human node is the default selection.
  await expect(panel(page).getByRole('heading', { name: 'Conversation' })).toBeVisible();
  await page.getByRole('button', { name: 'Talk to the agent' }).click();
  await expect(page).toHaveURL(/stage=human/);
  await composer(page).fill('please change the wording of the note');
  await composer(page).press('Enter');

  const strip = page.locator('.rounds');
  const followUp = strip.locator('.round', { hasText: 'follow-up 1' });
  await expect(followUp).toBeVisible({ timeout: 30_000 });
  for (const role of ['security', 'ux', 'ui-design', 'code']) await expect(followUp.locator('.round-chip', { hasText: role })).toBeVisible();
  // While the follow-up review runs (or until the server has picked up the commit), approval is not possible.
  await expect(approve(page)).toBeDisabled();
  await expect(page.locator('#approve-hint')).toBeVisible();
  await expect(approve(page)).toHaveAttribute('aria-describedby', 'approve-hint');

  await expect(page.locator('.run-error')).toContainText('Follow-up review 1', { timeout: 45_000 });
  await expect(page.locator('.run-error')).toContainText('all four reviewers pass');
  await expect(panel(page).getByRole('status').first()).toContainText('No changes since the last review.');
  await expect(page.locator('.rh-meta')).toContainText('follow-up 1');
  await expect(pill(page)).toHaveText('needs human');
  await expect(approve(page)).toBeEnabled();
  // A passing follow-up review does not undo the approver's escalation: the merge is still confirmed.
  await approve(page).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Merge anyway' }).click();
  await expect(pill(page)).toHaveText('merged', { timeout: 30_000 });
  await expect(panel(page)).toContainText('This run is merged; the conversation is closed.');
});
