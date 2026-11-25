import { expect, test, type Locator, type Page } from 'playwright/test';
import { BASE, login, mutate, registerCredentials } from './support';

// A release of a workspace project (docs/ARCHITECTURE.md §6 "Releases"): in the submodule workspace demo-shop, a release
// holds a member issue (label demo-shop-web: built on release/<name> in demo-shop-web) and an issue of the workspace
// repository itself (no member label). Ready, it has one release pull request per touched repository; approval merges
// the member's first, then moves the submodule pointer and merges the workspace's.
// 23-submodule-workspaces closes "Add a health endpoint" (#1) and "Update the architecture notes" (#3) with its runs, so
// this spec uses the still open "Show the cart total in the header" (#2) and a new unlabelled issue.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const BUILD = { timeout: 120_000 };
const pill = (page: Page) => page.locator('.rh-title .pill');
const issueRows = (page: Page) => page.locator('.panel--issues .issue-list .issue');
const item = (page: Page, title: string) => page.locator('.release-order li', { hasText: title });
/** The entry of [repo] in the "Pull requests" list (exact name: demo-shop is a prefix of its members' names). */
const repoEntry = (list: Locator, repo: string) =>
  list.locator('.release-repo').filter({ has: list.page().locator('.release-repo-name', { hasText: new RegExp(`^${repo}$`) }) });
const repoState = (entry: Locator) => entry.locator('.release-repo-line .pill');

async function openIssueNumber(page: Page, title: string): Promise<number> {
  const r = await page.request.get(`${BASE()}/api/projects/demo-shop/issues?state=open`);
  expect(r.status()).toBe(200);
  const issue = ((await r.json()) as { number: number; title: string }[]).find((i) => i.title === title);
  expect(issue, `open issue "${title}" in demo-shop`).toBeTruthy();
  return issue!.number;
}

test('a release of a submodule workspace builds member and workspace issues and merges one pull request per repository', async ({ page }) => {
  test.setTimeout(300_000);
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  const memberTitle = 'Show the cart total in the header';
  const ownTitle = `Workspace release notes ${tag}`;
  const created = await page.request.post(`${BASE()}/api/projects/demo-shop/issues`, {
    headers: mutate,
    data: { title: ownTitle, body: 'Describe the release in docs/releases.md.', kind: 'task' },
  });
  expect(created.status()).toBe(201);
  const own = (await created.json()).number as number;
  const member = await openIssueNumber(page, memberTitle);

  // Releases are available in the workspace project.
  await page.goto(`${BASE()}/p/demo-shop`);
  await expect(issueRows(page).first()).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
  const name = `ws-${tag}`;
  await page.getByRole('button', { name: 'New release' }).click();
  const dialog = page.getByRole('dialog', { name: 'New release in demo-shop' });
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('button', { name: 'Create release' }).click();
  await expect(dialog).toBeHidden();
  const releaseRow = page.locator('.panel--activity .release-list li', { hasText: name });
  await expect(releaseRow).toHaveCount(1);

  // The member issue and the workspace repository's own issue, from the issue list's release picker.
  for (const [n, title] of [[member, memberTitle], [own, ownTitle]] as const) {
    await page.getByLabel(`Release for #${n}`).selectOption({ label: name });
    await expect(issueRows(page).filter({ hasText: title }).locator('.release-tag')).toHaveText(name);
  }
  await expect(page.locator('.panel--issues .form-error')).toHaveCount(0);
  await expect(releaseRow).toContainText('2 issues');

  await releaseRow.getByRole('link').click();
  await expect(page).toHaveURL(/\/p\/demo-shop\/releases\/[^/]+$/);
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
  await expect(pill(page)).toHaveText('in preparation');

  await page.getByRole('button', { name: '▶ Start release' }).click();
  const start = page.getByRole('dialog', { name: 'Start release?' });
  await start.getByRole('button', { name: 'Start release' }).click();
  await expect(start).toBeHidden();
  await expect(pill(page)).toHaveText('ready to release', BUILD);
  await expect(page.locator('.release-progress')).toHaveText('2 of 2 merged');

  // The member issue was built in demo-shop-web, its pull request into release/<name> there.
  await expect(item(page, memberTitle).locator('.issue-member')).toHaveText('web');
  await expect(item(page, memberTitle).getByRole('link', { name: new RegExp(`^pull request demo-shop-web #\\d+ of #${member}$`) })).toHaveAttribute(
    'href',
    /\/demo-shop-web\/pull\/\d+$/,
  );
  await expect(item(page, ownTitle).locator('.issue-member')).toHaveCount(0);

  // One release pull request per touched repository: the member's, then the workspace's.
  const section = page.getByRole('region', { name: 'Pull requests' });
  const web = repoEntry(section, 'demo-shop-web');
  const ws = repoEntry(section, 'demo-shop');
  await expect(web).toHaveCount(1);
  await expect(ws).toHaveCount(1);
  await expect(web.locator('.tag')).toHaveText('web');
  await expect(web).toContainText(`release/${name} → main`);
  await expect(repoState(web)).toHaveText('open');
  await expect(web.getByRole('link', { name: /^release pull request #\d+ of demo-shop-web$/ })).toHaveAttribute('href', /\/demo-shop-web\/pull\/\d+$/);
  await expect(ws.locator('.tag')).toHaveText('workspace repository');
  await expect(ws).toContainText(`release/${name} → main`);
  await expect(repoState(ws)).toHaveText('open');
  await expect(ws.getByRole('link', { name: /^release pull request #\d+ of demo-shop$/ })).toHaveAttribute('href', /\/demo-shop\/pull\/\d+$/);
  await expect(section.getByRole('status')).toHaveText('Ready to release. All 2 pull requests merge cleanly.');

  const approve = section.getByRole('button', { name: 'Approve and release' });
  await expect(approve).toBeEnabled();
  await approve.click();
  const confirm = page.getByRole('dialog', { name: `Release ${name}?` });
  await expect(confirm).toContainText(
    'Member pull requests are merged first; then the submodule pointers are moved and the workspace pull request is merged.',
  );
  const order = confirm.locator('.release-approve-order li');
  await expect(order).toHaveCount(2);
  await expect(order.first()).toContainText('demo-shop-web #');
  await expect(order.last()).toContainText(/^demo-shop #\d+/);
  await expect(confirm.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await confirm.getByRole('button', { name: 'Approve and release' }).click();
  await expect(confirm).toBeHidden();

  await expect(pill(page)).toHaveText('released', BUILD);
  const done = page.getByRole('region', { name: 'Pull requests' });
  await expect(repoState(repoEntry(done, 'demo-shop-web'))).toHaveText('merged');
  await expect(repoState(repoEntry(done, 'demo-shop'))).toHaveText('merged');
  await expect(repoEntry(done, 'demo-shop-web')).toContainText(/merged at \S+/);
  for (const n of [member, own]) {
    const issue = await (await page.request.get(`${BASE()}/api/projects/demo-shop/issues/${n}`)).json();
    expect(issue.state).toBe('closed');
  }
});
