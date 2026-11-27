import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login } from './support';

// Runs keep their branch up to date with the base branch (#35, docs/ARCHITECTURE.md §6 "Retrying a run", "Update and
// merge"). With `.agern-fake/land-merges` the fake GitHub's merges also land on alice's local `main`, so the second of two
// runs that both append to AGERN_FAKE.md sees its base move, gets an automatic update round, and the fake builder
// resolves the conflict.
test.describe.configure({ mode: 'serial' });

const REPO = 'demo-app';
const mutate = { 'X-Factory-Request': '1' };

function landMerges(on: boolean) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (the global setup starts the server)');
  const file = join(dir, 'fake-github', REPO, '.agern-fake', 'land-merges');
  if (on) {
    mkdirSync(join(file, '..'), { recursive: true });
    writeFileSync(file, '');
  } else rmSync(file, { force: true });
}

test.afterAll(() => landMerges(false));

type RunJson = {
  id: string;
  status: string;
  updates?: { trigger: string }[];
  overlaps?: { runId: string }[];
  stages: { role: string; status: string; sessionId: string | null }[];
};

const getRun = async (page: Page, id: string): Promise<RunJson> => (await page.request.get(`${BASE()}/api/runs/${id}`)).json();

async function newIssue(page: Page, title: string, body: string): Promise<number> {
  const res = await page.request.post(`${BASE()}/api/projects/${REPO}/issues`, { headers: mutate, data: { title, body, kind: 'task' } });
  expect(res.ok()).toBeTruthy();
  return (await res.json()).number;
}

async function startRun(page: Page, issueNumber: number): Promise<RunJson> {
  const res = await page.request.post(`${BASE()}/api/projects/${REPO}/runs`, { headers: mutate, data: { issueNumber } });
  expect(res.status()).toBe(201);
  return res.json();
}

test('two runs that touch the same file both merge; the second is updated with the first', async ({ page }) => {
  landMerges(true);
  await login(page, 'alice');
  const first = await newIssue(page, 'Base moved: first change', 'Appends to the shared file.');
  // The second builder keeps working until it is told to finish, so the first run merges (and lands) before it is reviewed.
  const second = await newIssue(page, 'Base moved: second change', 'Appends to the shared file too. fake:busy-builder');
  const a = await startRun(page, first);
  const b = await startRun(page, second);
  // Warned early: the first run was active when the second started.
  expect((b.overlaps ?? []).map((o) => o.runId)).toContain(a.id);

  await page.goto(`${BASE()}/p/${REPO}/runs/${a.id}`);
  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 60_000 });

  // The second run is still building and shows the other run as a warning.
  await page.goto(`${BASE()}/p/${REPO}/runs/${b.id}`);
  const notice = page.getByRole('note', { name: 'Other active runs' });
  await expect(notice).toContainText('Other runs were active in this project when this run started:');
  await expect(notice).toContainText('Base moved: first change');
  await expect(notice).toContainText('A warning only');

  // Let the second builder finish.
  await expect.poll(async () => (await getRun(page, b.id)).stages.find((s) => s.role === 'builder')?.sessionId ?? null, { timeout: 30_000 }).not.toBeNull();
  const builder = (await getRun(page, b.id)).stages.find((s) => s.role === 'builder')!;
  const sent = await page.request.post(`${BASE()}/api/sessions/${builder.sessionId}/input`, { headers: mutate, data: { text: 'finish' } });
  expect(sent.ok()).toBeTruthy();

  await expect(page.locator('.rh-title .pill')).toHaveText('merged', { timeout: 90_000 });
  const [doneA, doneB] = [await getRun(page, a.id), await getRun(page, b.id)];
  expect(doneA.status).toBe('merged');
  expect(doneB.status).toBe('merged');
  expect(doneA.updates ?? []).toHaveLength(0);
  expect((doneB.updates ?? []).map((u) => u.trigger)).toEqual(['base_moved']);

  // The second run's page shows its update round.
  const updates = page.getByRole('region', { name: 'Updates' });
  await expect(updates).toContainText('Update round 1 of 2');
  await expect(updates).toContainText('automatic');
  await expect(updates).toContainText('moved on');
  await expect(updates).toContainText('AGERN_FAKE.md');
  await expect(updates).toContainText('The builder resolved the conflicts');
  await expect(page.getByRole('note', { name: 'Other active runs' })).toHaveCount(0);

  // The first run had nothing to update.
  await page.goto(`${BASE()}/p/${REPO}/runs/${a.id}`);
  await expect(page.locator('.rh-title .pill')).toHaveText('merged');
  await expect(page.getByRole('region', { name: 'Updates' })).toHaveCount(0);
});
