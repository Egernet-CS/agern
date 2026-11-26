import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials, userContext } from './support';

// "Bring up to date" (docs/ARCHITECTURE.md §6 "Bringing a release up to date", §10 "Releases"): a ready release whose
// pull request conflicts offers its owner and admins a run that merges main into the release branch. The fake GitHub
// reports a conflict while <dataDir>/fake-github-checks/<repo>/<branch>.json says `"conflict": true`.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');
const BUILD = { timeout: 90_000 };
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);

function conflictFile(branch: string) {
  const dir = process.env.E2E_DATA_DIR;
  if (!dir) throw new Error('E2E_DATA_DIR is not set (run through npm run e2e)');
  return join(dir, 'fake-github-checks', 'demo-app', `${branch}.json`);
}

function setConflict(branch: string, conflict: boolean) {
  const file = conflictFile(branch);
  if (!conflict) {
    rmSync(file, { force: true });
    return;
  }
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ conflict: true }));
}

async function openRelease(page: Page, id: string, name: string) {
  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
}

test('bring up to date: owner and admins see the button, others do not; the run merges main into the release branch', async ({ page, browser }) => {
  test.setTimeout(240_000);
  // Bob (not an admin) owns the release.
  await login(page, 'bob');
  await registerCredentials(page, 'bob');
  const name = `sync-${tag}`;
  const branch = `release/${name}`;
  const issue = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title: `Sync me ${tag}`, body: 'x', kind: 'task' } });
  expect(issue.status()).toBe(201);
  const created = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  const id = (await created.json()).id as string;
  expect((await page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, { headers: mutate, data: { issueNumbers: [(await issue.json()).number], releaseId: id } })).status()).toBe(200);
  expect((await page.request.post(`${BASE()}/api/releases/${id}/start`, { headers: mutate })).status()).toBe(200);

  await openRelease(page, id, name);
  await expect(pill(page)).toHaveText('ready to release', BUILD);
  const section = page.getByRole('region', { name: 'Release pull request' });
  await expect(section.locator('#release-ready-status')).toHaveText('Ready to release. Merges cleanly into main.', BUILD);
  await expect(section.getByRole('button', { name: 'Bring up to date' })).toHaveCount(0);

  // main moved on and the release pull request conflicts now.
  setConflict(branch, true);
  await section.getByRole('button', { name: 'Check again' }).click();
  await expect(section.locator('#release-ready-status')).toHaveText(
    `${branch} no longer merges cleanly into main. Agern will not merge it. Bring it up to date, or resolve the conflicts on the release branch and check again.`,
  );
  const button = section.getByRole('button', { name: 'Bring up to date' });
  await expect(button).toBeVisible();

  // Alice (an admin, not the owner) sees the button too; carol sees none, and the API refuses her.
  const alice = await userContext(browser, 'alice');
  await registerCredentials(alice.page, 'alice');
  await openRelease(alice.page, id, name);
  await expect(alice.page.getByRole('region', { name: 'Release pull request' }).getByRole('button', { name: 'Bring up to date' })).toBeVisible();
  await alice.context.close();
  const carol = await userContext(browser, 'carol');
  await registerCredentials(carol.page, 'carol');
  await openRelease(carol.page, id, name);
  await expect(carol.page.getByText('Only bob or an admin can bring it up to date.')).toBeVisible();
  await expect(carol.page.getByRole('button', { name: 'Bring up to date' })).toHaveCount(0);
  const refused = await carol.page.request.post(`${BASE()}/api/releases/${id}/sync`, { headers: mutate });
  expect(refused.status()).toBe(403);
  await carol.context.close();

  // Bob confirms in the dialog.
  await button.click();
  const dialog = page.getByRole('dialog', { name: `Bring ${branch} up to date?` });
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await expect(dialog).toContainText(`An agent run merges main into ${branch} with a merge commit (no rebase, no force-push)`);
  await expect(dialog).toContainText('Nothing is merged into main. It runs with your Claude credential and GitHub token.');
  await expect(dialog).toContainText('Afterwards the release is merged with a merge commit instead of by rebasing.');
  await dialog.getByRole('button', { name: 'Bring up to date' }).click();
  await expect(dialog).toBeHidden();

  // Running: the status line, the run link, approval disabled.
  const running = section.locator('.release-sync--running');
  await expect(running).toContainText(`Bringing ${branch} up to date with main (started by bob).`);
  await expect(section.getByRole('button', { name: 'Approve and release' })).toBeDisabled();
  await expect(section.getByRole('button', { name: 'Check again' })).toBeVisible();
  const href = await running.getByRole('link', { name: 'Open the run' }).getAttribute('href');
  expect(href).toMatch(/^\/p\/demo-app\/runs\/[0-9a-f-]+$/);

  // The run page: no issue number, builder and reviewers, "up to date" at the end.
  await page.goto(`${BASE()}${href}`);
  const title = page.locator('.rh-title h1');
  await expect(title).toContainText(`Bring ${branch} up to date with main`);
  await expect(title).not.toContainText('#0');
  await expect(node(page, 'builder')).toBeVisible();
  for (const r of ['security', 'ux', 'ui-design', 'code']) await expect(node(page, r)).toBeVisible();
  await expect(node(page, 'spec-writer')).toHaveAttribute('aria-label', /^spec-writer: skipped/);
  await expect(pill(page)).toHaveText('up to date', BUILD);
  await expect(page.getByRole('button', { name: 'Retry…' })).toHaveCount(0);

  // Back on the release page: done; GitHub no longer reports the conflict.
  await openRelease(page, id, name);
  await expect(section.locator('.release-sync--done')).toContainText('Brought up to date with main', BUILD);
  await expect(section.locator('.release-sync--done')).toContainText('by bob');
  setConflict(branch, false);
  await section.getByRole('button', { name: 'Check again' }).click();
  await expect(section.locator('#release-ready-status')).toHaveText('Ready to release. Merges cleanly into main.');
  await expect(section.getByRole('button', { name: 'Bring up to date' })).toHaveCount(0);
});
