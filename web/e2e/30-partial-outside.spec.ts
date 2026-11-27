import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials } from './support';

// Partial delivery and pull requests merged outside Agern (docs/ARCHITECTURE.md §6 "Partial delivery", "Pull requests
// merged or closed on GitHub"). The fake GitHub merges an open pull request on its next read when
// <dataDir>/fake-github-checks/<repo>/<branch>.json says `"pull": "merged"`; the run's branch is read from the API.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');

function setFakePull(branch: string, content: Record<string, unknown>) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (run through npm run e2e)');
  const file = join(dir, 'fake-github-checks', 'demo-app', `${branch}.json`);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(content));
}

/** Creates an issue whose approver escalates, starts a run for it and opens the run page once it needs a human. */
async function escalatedRun(page: Page, title: string): Promise<{ id: string; number: number; branch: string }> {
  const issue = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: 'fake:escalate', kind: 'task' } });
  expect(issue.status()).toBe(201);
  const number = (await issue.json()).number as number;
  const started = await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: number } });
  expect(started.status(), await started.text()).toBe(201);
  const id = (await started.json()).id as string;
  await page.goto(`${BASE()}/p/demo-app/runs/${id}`);
  await expect(pill(page)).toHaveText('needs human', { timeout: 60_000 });
  const branch = ((await (await page.request.get(`${BASE()}/api/runs/${id}`)).json()) as { branch: string }).branch;
  return { id, number, branch };
}

test('approve a needs-human run with "keep the issue open"', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  const { number } = await escalatedRun(page, `Partial approve ${tag}`);

  // Not partial yet: merging would close the issue; both choices are offered.
  await expect(page.locator('.run-delivery')).toContainText(`Merging closes issue #${number}.`);
  await expect(page.getByRole('button', { name: 'Mark as partial delivery' })).toBeVisible();
  await page.getByRole('button', { name: 'Merge & keep issue open' }).click();
  const confirm = page.getByRole('dialog', { name: 'Merge against the approver\'s advice?' });
  await expect(confirm).toContainText(`Issue #${number} stays open (partial delivery).`);
  await confirm.getByRole('button', { name: 'Merge anyway' }).click();

  await expect(pill(page)).toHaveText('merged');
  await expect(page.locator('.run-delivery')).toContainText(`Issue #${number} was left open.`);
  await expect(page.locator('.rh-meta')).toContainText('partial — issue stays open');
  const open = (await (await page.request.get(`${BASE()}/api/projects/demo-app/issues`)).json()) as { number: number }[];
  expect(open.map((i) => i.number)).toContain(number);
});

test('a run whose pull request was merged on GitHub shows as merged, and approve does not merge again', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page, 'alice');
  const { id, branch } = await escalatedRun(page, `Merged outside ${tag}`);

  setFakePull(branch, { pull: 'merged', mergedBy: 'carol' });
  // A run's pull request is read at most every 15 s on page loads (the run was just read): reload until it was.
  await expect
    .poll(async () => {
      await page.reload();
      return pill(page).textContent();
    }, { timeout: 45_000, intervals: [3_000] })
    .toBe('merged');
  await expect(page.locator('.run-policy')).toContainText('Merged on GitHub by @carol');
  await expect(page.getByRole('button', { name: '✓ Approve & merge' })).toHaveCount(0);

  const approve = await page.request.post(`${BASE()}/api/runs/${id}/approve`, { headers: mutate });
  expect(approve.status()).toBe(409);
  expect((await approve.json()).error.code).toBe('pull_request_merged');
});
