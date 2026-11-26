import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials } from './support';

// Checks before a run is merged (docs/ARCHITECTURE.md §6 "Checks before a merge"): the fake GitHub reads the checks of
// a branch's head from <dataDir>/fake-github-checks/<repo>/<branch>.json; the run's branch is read from the API.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');
const mergeNode = (page: Page) => page.locator('.pipeline g[aria-label^="merge to main:"]');

type CheckRun = { name: string; status: string; conclusion?: string; html_url?: string };

function setChecks(branch: string, checkRuns: CheckRun[]) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (run through npm run e2e)');
  const file = join(dir, 'fake-github-checks', 'demo-app', `${branch}.json`);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ checkRuns }));
}

/** Starts a slow run (every stage ~1.5 s) and sets [checks] on its branch as soon as it has one, before the approver. */
async function startRun(page: Page, title: string, checks: CheckRun[]): Promise<{ id: string; branch: string }> {
  const issue = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: 'Gated by checks. fake:slow', kind: 'task' } });
  expect(issue.status()).toBe(201);
  const started = await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: (await issue.json()).number } });
  expect(started.status(), await started.text()).toBe(201);
  const id = (await started.json()).id as string;
  let branch: string | null = null;
  await expect
    .poll(async () => {
      branch = ((await (await page.request.get(`${BASE()}/api/runs/${id}`)).json()) as { branch: string | null }).branch;
      return branch;
    }, { intervals: [100] })
    .not.toBeNull();
  setChecks(branch!, checks);
  await page.goto(`${BASE()}/p/demo-app/runs/${id}`);
  return { id, branch: branch! };
}

test('pending checks: the run waits, says so, and merges by itself once they pass', async ({ page }) => {
  test.setTimeout(150_000);
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  const { branch } = await startRun(page, `Waits for checks ${tag}`, [
    { name: 'build', status: 'in_progress', html_url: 'https://github.com/agern-e2e/demo-app/runs/1' },
  ]);

  await expect(pill(page)).toHaveText('waiting for checks', { timeout: 60_000 });
  const notice = page.getByRole('status').filter({ hasText: 'Waiting for checks on' });
  await expect(notice).toContainText('Pending: build. The pull request is merged automatically when they pass; Agern stops waiting at');
  const link = notice.getByRole('link', { name: 'build' });
  await expect(link).toHaveAttribute('href', 'https://github.com/agern-e2e/demo-app/runs/1');
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(mergeNode(page)).toHaveAttribute('aria-label', /waiting for checks/);
  await expect(page.getByRole('button', { name: 'Merge anyway…' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel run' })).toBeVisible();

  // Green: merged without anyone approving again (the next poll, at most 30 s later).
  setChecks(branch, [{ name: 'build', status: 'completed', conclusion: 'success' }]);
  await expect(pill(page)).toHaveText('merged', { timeout: 60_000 });
  await expect(page.locator('.pipeline g[aria-label^="merge to main: done, verdict MERGED"]')).toBeVisible();
});

test('failed checks: the run needs a human, and Merge anyway names the check and merges on confirmation', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page, 'alice');
  await startRun(page, `Fails its checks ${tag}`, [
    { name: 'e2e', status: 'completed', conclusion: 'failure', html_url: 'https://github.com/agern-e2e/demo-app/runs/2' },
  ]);

  await expect(pill(page)).toHaveText('needs human', { timeout: 60_000 });
  await expect(page.locator('.run-error')).toContainText('Checks failed on');
  await expect(page.locator('.run-error').getByRole('link', { name: 'e2e' })).toHaveAttribute('href', 'https://github.com/agern-e2e/demo-app/runs/2');
  await expect(mergeNode(page)).toHaveAttribute('aria-label', /failed.*checks not passed/);
  await expect(page.getByRole('button', { name: '✓ Approve & merge' })).toBeVisible();

  const trigger = page.getByRole('button', { name: 'Merge anyway…' });
  await expect(trigger).toHaveAttribute('aria-haspopup', 'dialog');
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: 'Merge without passing checks?' });
  const list = dialog.getByRole('note', { name: 'Checks that have not passed' });
  await expect(list).toContainText('e2e — failed (failure)');
  await expect(dialog.getByRole('button', { name: 'Don’t merge' })).toBeFocused();
  await expect(dialog).toContainText('Your decision is recorded on the run and on the pull request.');

  // Escape closes it without merging.
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(pill(page)).toHaveText('needs human');

  await trigger.click();
  await dialog.getByRole('button', { name: 'Merge anyway' }).click();
  await expect(dialog).toBeHidden();
  await expect(pill(page)).toHaveText('merged');
  await expect(page.locator('.run-override')).toHaveText('Merged by alice despite checks: Failed: e2e');
  await expect(mergeNode(page)).toHaveAttribute('aria-label', /despite checks/);
});
