import { expect, test, type Page } from 'playwright/test';
import { BASE, login, userContext } from './support';

test.describe.configure({ mode: 'serial' });

/** A diagram node by its accessible label, e.g. node(page, 'builder') → "builder: running, model opus". */
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const pill = (page: Page) => page.locator('.rh-title .pill');

async function newIssue(page: Page, title: string, body: string, kind: 'feature' | 'bug' | 'task' = 'feature') {
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByText(kind, { exact: true }).click();
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel(/Description/).fill(body);
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.issue-list .issue').first()).toContainText(title);
}

async function startRunFor(page: Page, title: string) {
  const row = page.locator('.issue-list .issue', { hasText: title });
  await row.getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  await expect(page.locator('.rh-title h1')).toContainText(title);
}

test('a run advances live through the pipeline to merged; the agents page lists its agents', async ({ page, context }) => {
  await login(page, 'alice');
  await newIssue(page, 'Happy path through the factory', 'Make it so. fake:slow');
  await startRunFor(page, 'Happy path through the factory');

  // No reloads from here on: everything below arrives over the feed WebSocket.
  await expect(node(page, 'spec-writer')).toHaveAttribute('aria-label', /^spec-writer: running, model opus/);
  // Meanwhile the agents page lists the running pipeline agent (and alice's live chat).
  const agents = await context.newPage();
  await agents.goto(`${BASE()}/agents`);
  const table = agents.locator('.agents-table');
  await expect(table.locator('tr', { hasText: /spec-writer|builder|security|ux|ui-design|code|approver/ }).first()).toBeVisible();
  await expect(table.locator('tr', { has: agents.locator('.role--chat') }).first()).toContainText('you');
  await expect(agents.locator('.band-run', { hasText: 'Happy path through the factory' })).toBeVisible();
  await agents.close();

  await expect(node(page, 'builder')).toHaveAttribute('aria-label', /^builder: (running|done)/);
  await expect(node(page, 'security')).toHaveAttribute('aria-label', /^security: (running|done)/);
  await expect(node(page, 'approver')).toHaveAttribute('aria-label', /^approver: (running|done)/);
  await expect(page.locator('.pipeline g[aria-label^="merge to main: done, verdict MERGED"]')).toBeVisible({ timeout: 45_000 });
  await expect(pill(page)).toHaveText('merged');
  await expect(page.locator('.rh-meta')).toContainText('build');
  await expect(page.locator('.rh-meta')).toContainText('#1001');
  for (const r of ['security', 'ux', 'ui-design', 'code']) await expect(node(page, r)).toHaveAttribute('aria-label', /done, verdict PASS/);

  // Selecting a stage shows that agent's transcript and the model it ran with.
  await node(page, 'builder').click();
  const detail = page.locator('.stage-detail');
  await expect(detail.locator('.sd-role')).toHaveText('builder');
  await expect(detail.locator('.sd-facts')).toContainText('opus');
  await expect(detail.locator('.chat-log')).toContainText('Implemented the change (fake)');
  await node(page, 'ux').click();
  await expect(detail.locator('.sd-role')).toHaveText('ux');
  await expect(detail.locator('.sd-facts')).toContainText('sonnet');
  await expect(detail.locator('.chat-log')).toContainText('Reviewed the diff.');
  await expect(detail).toContainText('Fake ux reviewer is happy.');

  // The issue is closed now.
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(page.locator('.issue-list .issue', { hasText: 'Happy path through the factory' })).toHaveCount(0);
});

test('reviewers asking for changes loop back to the builder for a fix round', async ({ page }) => {
  await login(page, 'alice');
  await newIssue(page, 'Needs a second pass', 'Reviewers will object once. fake:changes');
  await startRunFor(page, 'Needs a second pass');
  await expect(pill(page)).toHaveText('merged', { timeout: 45_000 });

  await expect(node(page, 'builder')).toContainText('fix round 1/2');
  await expect(page.locator('.pe--loop')).toHaveClass(/pe--done/);
  await expect(page.locator('.rh-meta')).toContainText('fix round 1 of 2');
  const rounds = page.locator('.rounds');
  await expect(rounds.locator('.round')).toHaveCount(2);
  await expect(rounds.locator('.round').first()).toContainText('build');
  await expect(rounds.locator('.round').first()).toContainText('CHANGES');
  await expect(rounds.locator('.round').nth(1)).toContainText('fix round 1');

  // The builder has one transcript per round; the fix round resumes the first conversation.
  await node(page, 'builder').click();
  const detail = page.locator('.stage-detail');
  await expect(detail.getByRole('tab', { name: 'build' })).toBeVisible();
  await expect(detail.getByRole('tab', { name: 'fix round 1' })).toHaveAttribute('aria-selected', 'true');
  await expect(detail).toContainText('Continues the builder conversation of build');
  await expect(detail.locator('.chat-log')).toContainText('The reviewers requested changes. This is fix round 1 of at most 2.');
  await detail.getByRole('tab', { name: 'build' }).click();
  await expect(detail.locator('.chat-log')).toContainText('Implement issue #');

  // A reviewer's CHANGES findings from round 0 are shown on its round tab.
  await node(page, 'security').click();
  await detail.getByRole('tab', { name: 'build' }).click();
  await expect(detail.locator('.findings')).toContainText('Fake finding from security.');
});

test('an escalated run waits for a human; only the owner can approve, and that merges it', async ({ page, browser }) => {
  await login(page, 'alice');
  await newIssue(page, 'Risky change', 'The approver will escalate. fake:escalate', 'bug');
  await startRunFor(page, 'Risky change');
  await expect(pill(page)).toHaveText('needs human', { timeout: 45_000 });
  await expect(page.locator('.run-error')).toContainText('Needs a human: The approver escalated: Fake approver says ESCALATE.');
  await expect(page.locator('.pipeline g[aria-label^="needs-human: running"]')).toBeVisible();
  const runUrl = page.url();

  // The project overview shows it among the runs that need a human (GET /api/runs?active=true).
  const bob = await userContext(browser, 'bob');
  await bob.page.goto(`${BASE()}/`);
  await expect(bob.page.locator('.strip-item--warn', { hasText: 'Risky change' })).toBeVisible();
  await bob.page.goto(runUrl);
  await expect(bob.page.getByText('Waiting for alice to approve.')).toBeVisible();
  await expect(bob.page.getByRole('button', { name: '✓ Approve & merge' })).toHaveCount(0);
  const runId = runUrl.split('/').pop()!;
  expect((await bob.page.request.post(`${BASE()}/api/runs/${runId}/approve`, { headers: { 'X-Factory-Request': '1' } })).status()).toBe(403);
  const waiting = await (await bob.page.request.get(`${BASE()}/api/runs?status=needs_human`)).json();
  expect(waiting.map((r: { id: string }) => r.id)).toContain(runId);

  await page.getByRole('button', { name: '✓ Approve & merge' }).click();
  await expect(pill(page)).toHaveText('merged');
  await expect(page.locator('.pipeline g[aria-label^="needs-human: done, verdict APPROVED"]')).toBeVisible();
  await expect(page.locator('.run-error')).toHaveCount(0);
  // Bob's open page follows along live.
  await expect(bob.page.locator('.rh-title .pill')).toHaveText('merged');
  await bob.context.close();
});
