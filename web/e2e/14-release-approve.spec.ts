import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials, userContext } from './support';

// Releasing (docs/ARCHITECTURE.md §6 "Releases", "Releasing"): a release whose issues are all built becomes ready to
// release with a pull request into main; only its owner approves it; it then merges, closes its issues and is kept as
// history. In fake mode the fake GitHub reports the pull request as mergeable and allows rebase merges.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const pill = (page: Page) => page.locator('.rh-title .pill');
const BUILD = { timeout: 90_000 };

async function newIssue(page: Page, title: string): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: 'Built for a release.', kind: 'task' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

async function openRelease(page: Page, id: string, name: string) {
  await page.goto(`${BASE()}/p/demo-app/releases/${id}`);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
}

test('approve and release: the owner merges a ready release; others cannot; it is kept as history', async ({ page, browser }) => {
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  const name = `ship-${tag}`;
  const numbers = [await newIssue(page, `Release approve one ${tag}`), await newIssue(page, `Release approve two ${tag}`)];
  const created = await page.request.post(`${BASE()}/api/projects/demo-app/releases`, { headers: mutate, data: { name } });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  expect((await page.request.post(`${BASE()}/api/projects/demo-app/releases/assign`, { headers: mutate, data: { issueNumbers: numbers, releaseId: id } })).status()).toBe(200);
  const started = await page.request.post(`${BASE()}/api/releases/${id}/start`, { headers: mutate });
  expect(started.status(), await started.text()).toBe(200);

  // Both issues are built; the release is ready with its pull request.
  await openRelease(page, id, name);
  await expect(pill(page)).toHaveText('ready to release', BUILD);
  const section = page.getByRole('region', { name: 'Release pull request' });
  const prLink = section.getByRole('link', { name: /^#\d+$/ });
  await expect(prLink).toHaveAttribute('href', /\/pull\/\d+$/);
  await expect(section).toContainText(`release/${name} → main`);
  await expect(section.getByRole('status')).toHaveText('Ready to release. Merges cleanly into main.');
  await expect(section).toContainText(
    'Approval merges it by rebasing: one commit per issue on main. If GitHub cannot rebase it, a merge commit is used where the repository allows one.',
  );
  const approve = section.getByRole('button', { name: 'Approve and release' });
  await expect(approve).toBeEnabled();
  await expect(section.getByRole('button', { name: 'Check again' })).toBeVisible();
  for (const n of numbers) {
    const issue = await (await page.request.get(`${BASE()}/api/projects/demo-app/issues/${n}`)).json();
    expect(issue.state).toBe('open');
  }

  // Bob sees the release but cannot approve it, neither on the page nor through the API.
  const bob = await userContext(browser, 'bob');
  await openRelease(bob.page, id, name);
  await expect(pill(bob.page)).toHaveText('ready to release');
  await expect(bob.page.getByText('Only alice can approve this release.')).toBeVisible();
  await expect(bob.page.getByRole('button', { name: 'Approve and release' })).toHaveCount(0);
  await expect(bob.page.getByRole('button', { name: 'Check again' })).toHaveCount(0);
  const refused = await bob.page.request.post(`${BASE()}/api/releases/${id}/approve`, { headers: mutate });
  expect(refused.status()).toBe(403);
  expect((await refused.json()).error.code).toBe('forbidden');
  await bob.context.close();

  // Alice confirms.
  await approve.click();
  const dialog = page.getByRole('dialog', { name: `Release ${name}?` });
  await expect(dialog).toContainText('into main by rebasing (or with a merge commit if GitHub cannot rebase it): 2 issues, one commit each.');
  await expect(dialog).toContainText(`the branch release/${name} is deleted`);
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await dialog.getByRole('button', { name: 'Approve and release' }).click();
  await expect(dialog).toBeHidden();

  await expect(pill(page)).toHaveText('released');
  await expect(page.locator('.release-released')).toContainText('by alice');
  await expect(page.locator('.release-released')).toContainText('Rebased onto main, one commit per issue');
  await expect(page.getByRole('region', { name: 'Release pull request' })).toHaveCount(0);
  for (const label of ['Approve and release', 'Check again', 'Edit', 'Delete', 'Cancel release']) {
    await expect(page.getByRole('button', { name: label })).toHaveCount(0);
  }
  await expect(page.getByLabel('Add an open issue')).toHaveCount(0);
  for (const n of numbers) {
    const issue = await (await page.request.get(`${BASE()}/api/projects/demo-app/issues/${n}`)).json();
    expect(issue.state).toBe('closed');
  }
  // A released release is history: read-only.
  const edit = await page.request.put(`${BASE()}/api/releases/${id}`, { headers: mutate, data: { name, description: 'later' } });
  expect(edit.status()).toBe(409);
  expect((await edit.json()).error.code).toBe('release_not_editable');

  // The project page lists it under History, no longer among the open releases.
  await page.goto(`${BASE()}/p/demo-app`);
  const activity = page.locator('.panel--activity');
  const history = activity.locator('details.release-history');
  await expect(history.locator('summary')).toHaveText(/^History \(\d+\)$/);
  await expect(activity.locator('.release-list').first().locator('li', { hasText: name })).toHaveCount(0);
  await history.locator('summary').click();
  const row = history.locator('li', { hasText: name });
  await expect(row).toContainText('released');
  await expect(row).toContainText('2 issues');
  await row.getByRole('link').click();
  await expect(page).toHaveURL(new RegExp(`/p/demo-app/releases/${id}$`));
});
