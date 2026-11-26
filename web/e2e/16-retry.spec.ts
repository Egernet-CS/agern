import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate } from './support';

// Retrying a failed run (docs/ARCHITECTURE.md §6 "Retrying a run"): the dialog preselects the recommendation, a continued
// run links to the one it continues, and a held release issue is retried the same way. The fake builder with
// "fake:stop-builder" commits and then stops with "Can't reach the API server", except when it is told to continue (or
// resumed after the connection came back). A run that loses the API first waits for the network (§6 "Transient
// failures"): these tests give up waiting, or retry at once.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');
const BUILD = { timeout: 90_000 };

async function newIssue(page: Page, title: string, body: string): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body, kind: 'task' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

test('a run that stopped after committing is continued from its commits', async ({ page }) => {
  await login(page, 'alice');
  const title = `Retry continue ${tag}`;
  const n = await newIssue(page, title, 'The builder loses the network. fake:stop-builder');
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: n } });
  expect(r.status(), await r.text()).toBe(201);
  const failedId = (await r.json()).id as string;

  await page.goto(`${BASE()}/p/demo-app/runs/${failedId}`);
  // It waits for the network first; giving up fails it as it would have failed without waiting.
  const banner = page.getByRole('status').filter({ hasText: 'Waiting for the network' });
  await expect(banner).toBeVisible(BUILD);
  await expect(banner).toContainText("Can't reach the API server");
  await expect(banner).toContainText('Next try at');
  await expect(banner).toContainText('(attempt 2)');
  await expect(page.locator('.rh-title')).toContainText('waiting for network');
  await expect(pill(page)).toHaveText('building');
  await expect(banner.getByRole('button', { name: 'Retry now' })).toBeEnabled();
  await banner.getByRole('button', { name: 'Give up…' }).click();
  const giveUp = page.getByRole('dialog', { name: 'Give up waiting?' });
  await expect(giveUp).toContainText('The run fails now, as it would have without waiting.');
  await expect(giveUp.getByRole('button', { name: 'Keep waiting' })).toBeFocused();
  await giveUp.getByRole('button', { name: 'Give up', exact: true }).click();
  await expect(giveUp).toBeHidden();

  await expect(pill(page)).toHaveText('failed', BUILD);
  await expect(banner).toBeHidden();
  await expect(page.locator('.run-error')).toContainText("Can't reach the API server");
  await expect(page.locator('.run-error')).toContainText('Gave up waiting for the network (by @alice, after 1 attempt).');

  await page.getByRole('button', { name: 'Retry…' }).click();
  const dialog = page.getByRole('dialog', { name: `Retry run ${failedId.slice(0, 8)}?` });
  await expect(dialog.locator('.retry-reason')).toContainText('Recommended: Continue from the commits.');
  await expect(dialog.locator('.retry-reason')).toContainText('the Claude API being unreachable');
  await expect(dialog.getByRole('radio', { name: /Continue from the commits/ })).toBeChecked();
  await expect(dialog.getByRole('radio', { name: /Retry the failed step/ })).toBeDisabled();
  await expect(dialog.getByRole('radio', { name: /Start over/ })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Continue from the commits' }).click();

  await expect(page).not.toHaveURL(new RegExp(failedId));
  const meta = page.locator('.rh-meta');
  await expect(meta).toContainText(`run ${failedId.slice(0, 8)}`);
  await expect(pill(page)).toHaveText('merged', BUILD);
  const newId = page.url().split('/runs/')[1].split(/[?#]/)[0];

  await page.goto(`${BASE()}/p/demo-app/runs/${failedId}`);
  await expect(page.locator('.rh-meta')).toContainText(`continued by`);
  await expect(page.locator('.rh-meta').getByRole('link', { name: `run ${newId.slice(0, 8)}` })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Retry…' })).toBeVisible();
});

test('a run waiting for the network is retried at once and resumes its builder', async ({ page }) => {
  await login(page, 'alice');
  const n = await newIssue(page, `Retry now ${tag}`, 'The builder loses the network. fake:stop-builder');
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: n } });
  expect(r.status(), await r.text()).toBe(201);
  const id = (await r.json()).id as string;

  await page.goto(`${BASE()}/p/demo-app/runs/${id}`);
  const banner = page.getByRole('status').filter({ hasText: 'Waiting for the network' });
  await expect(banner).toBeVisible(BUILD);
  await banner.getByRole('button', { name: 'Retry now' }).click();
  await expect(banner).toBeHidden(BUILD);
  await expect(pill(page)).toHaveText('merged', BUILD);
});

test('a held release issue is retried from its commits in its turn', async ({ page }) => {
  await login(page, 'alice');
  const name = `retry-${tag}`;
  const title = `Release retry ${tag}`;
  const n = await newIssue(page, title, 'fake:stop-builder');
  const created = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  expect((await page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, { headers: mutate, data: { issueNumbers: [n], releaseId: id } })).status()).toBe(200);
  const started = await page.request.post(`${BASE()}/api/releases/${id}/start`, { headers: mutate });
  expect(started.status(), await started.text()).toBe(200);

  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  const row = page.locator('.release-order li', { hasText: title });
  // Its run waits for the network: the release stays building; giving up holds the issue.
  await expect(page.locator('.release-status')).toContainText(`#${n} ${title} is waiting for the network since`, BUILD);
  await expect(row).toContainText('Waiting for the network since');
  await expect(row.locator('.issue-state')).toHaveText('running');
  await page.locator('.rh-actions').getByRole('button', { name: 'Give up…' }).click();
  const giveUp = page.getByRole('dialog', { name: 'Give up waiting?' });
  await giveUp.getByRole('button', { name: 'Give up', exact: true }).click();
  await expect(row.locator('.issue-state')).toHaveText('held', BUILD);

  await page.getByRole('button', { name: `Retry #${n}` }).click();
  const dialog = page.getByRole('dialog', { name: `Retry #${n}?` });
  await expect(dialog.getByRole('radio', { name: /Continue from the commits/ })).toBeChecked();
  await dialog.getByRole('button', { name: 'Continue from the commits' }).click();
  await expect(dialog).toBeHidden();
  await expect(row.locator('.issue-state')).toHaveText('merged', BUILD);

  await row.getByRole('link', { name: 'run' }).click();
  await expect(page.locator('.rh-meta')).toContainText('continues');

  const cancel = await page.request.post(`${BASE()}/api/releases/${id}/cancel`, { headers: mutate });
  expect([200, 409]).toContain(cancel.status());
});
