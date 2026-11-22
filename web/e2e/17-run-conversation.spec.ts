import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// Talking to the run assistant while a run waits at needs human (docs/ARCHITECTURE.md §6).
test.describe.configure({ mode: 'serial' });

const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const pill = (page: Page) => page.locator('.rh-title .pill');
const approve = (page: Page) => page.getByRole('button', { name: '✓ Approve & merge' });

async function waitingRun(page: Page, title: string): Promise<string> {
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel(/Description/).fill('The approver will escalate. fake:escalate fake:slow');
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await page.locator('.issue-list .issue', { hasText: title }).getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await expect(pill(page)).toHaveText('needs human', { timeout: 60_000 });
  return page.url();
}

test('the owner talks to the run assistant, a change is reviewed again before approval, others only read', async ({ page, browser }) => {
  test.setTimeout(150_000);
  await login(page, 'alice');
  const runUrl = await waitingRun(page, 'Talk before approving');
  const runId = runUrl.split('/').pop()!.split('?')[0];

  // The needs-human node is selected by default and opens the conversation.
  const detail = page.locator('.stage-detail--human');
  await expect(detail.locator('.sd-role')).toHaveText('needs-human');
  await expect(detail).toContainText('Talk to the run assistant about this run. Talking never approves it.');
  await expect(detail.getByRole('status').filter({ hasText: 'The branch head has been reviewed.' })).toBeVisible();
  await expect(detail).toContainText('No conversation yet. Ask why the run needs a human, or ask for a change.');
  await expect(detail).toContainText('Talking never approves the run.');

  // 1. A question: the answer knows the run (issue, findings, reason, real diff); the run keeps waiting.
  const composer = detail.getByLabel('Message the run assistant');
  await composer.fill('Why did the approver escalate?');
  await composer.press('Enter');
  const log = detail.locator('.chat-log');
  await expect(log).toContainText('Run assistant (fake).', { timeout: 20_000 });
  await expect(log).toContainText(/Context: issue #\d+; 0 finding\(s\); reason: The approver escalated: Fake approver says ESCALATE\./);
  await expect(log).toContainText(/Diff: 1 file changed/);
  await expect(log.locator('.t-user', { hasText: 'Why did the approver escalate?' })).toBeVisible();
  await expect(log).not.toContainText('<run_context>');
  await expect(pill(page)).toHaveText('needs human');
  await expect(approve(page)).toBeEnabled();

  // 2. A change: committed, pushed and reviewed again; approval waits for the follow-up round.
  await composer.fill('change: add a note about the escalation');
  await composer.press('Enter');
  await expect(log).toContainText('Committed the change.', { timeout: 20_000 });
  await expect(node(page, 'needs-human')).toContainText('reviewing change');
  await expect(detail.getByRole('status').filter({ hasText: 'The reviewers are checking the latest change…' })).toBeVisible();
  await expect(approve(page)).toBeDisabled();
  await expect(page.getByText('Approval is possible once the latest change has been reviewed.')).toBeVisible();
  await expect(detail).toContainText('The reviewers are checking the latest change. You can continue when they are done.');
  await expect(pill(page)).toHaveText('needs human');
  const followUp = page.locator('.rounds .round', { hasText: 'follow-up 1' });
  await expect(followUp).toBeVisible();
  await expect(followUp.locator('.round-chip')).toHaveCount(4);
  await expect(followUp.locator('.round-chip--pass')).toHaveCount(4, { timeout: 30_000 });
  await expect(node(page, 'needs-human')).toContainText('waiting for owner');
  await expect(approve(page)).toBeEnabled();
  await expect(pill(page)).toHaveText('needs human');

  // 3. Bob reads the conversation without a composer and cannot write to it.
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${runUrl.split('?')[0]}?stage=human`);
  const bobDetail = bob.page.locator('.stage-detail--human');
  await expect(bobDetail.locator('.chat-log')).toContainText('Committed the change.');
  await expect(bobDetail.locator('.chat-banner')).toContainText('observing alice');
  await expect(bobDetail.getByLabel('Message the run assistant')).toHaveCount(0);
  await expect(bobDetail.getByRole('button', { name: 'Review changes' })).toHaveCount(0);
  expect((await bob.page.request.post(`${BASE()}/api/runs/${runId}/messages`, { headers: mutate, data: { text: 'hijack' } })).status()).toBe(403);
  expect((await bob.page.request.post(`${BASE()}/api/runs/${runId}/review`, { headers: mutate })).status()).toBe(403);

  // 4. Alice approves: merged, and the conversation is closed (Bob's page follows live).
  await approve(page).click();
  await expect(pill(page)).toHaveText('merged');
  await expect(log).toContainText('The run was approved by alice; this conversation is closed.');
  await expect(detail).toContainText('This conversation ended when the run was merged.');
  await expect(detail.getByLabel('Message the run assistant')).toHaveCount(0);
  await expect(bob.page.locator('.rh-title .pill')).toHaveText('merged');
  await bob.context.close();
});
