import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

// Issues created through Agern arrive on the feed (`issue` frame) and show up on open project pages without a reload.
test.describe.configure({ mode: 'serial' });

const unique = (what: string) => `${what} ${Date.now().toString(36)}`;
const issues = (page: Page) => page.locator('.panel--issues .issue-list .issue');
const header = (page: Page) => page.locator('.project-head .ph-meta');
const kindFilter = (page: Page, kind: string) => page.getByRole('radiogroup', { name: 'Issue kind' }).getByRole('radio', { name: new RegExp(`^${kind}\\b`) });
const stateFilter = (page: Page, state: string) => page.getByRole('radiogroup', { name: 'Issue state' }).getByRole('radio', { name: state });

/** Opens the project page, waits for the issue list and a live feed, and returns the header's open-issue count. */
async function openProject(page: Page): Promise<number> {
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(issues(page).first()).toBeVisible();
  await expect(page.locator('.live-count')).toHaveAttribute('title', /feed live/);
  await expect(header(page)).toContainText(/\d+ open issues/);
  return Number((await header(page).textContent())!.match(/(\d+) open issues/)![1]);
}

test('an issue created by someone else appears without a reload', async ({ page, browser }) => {
  await login(page, 'alice');
  const n = await openProject(page);
  await kindFilter(page, 'bug').click();
  await expect(kindFilter(page, 'bug')).toHaveAttribute('aria-checked', 'true');
  const allCount = Number(await kindFilter(page, 'all').locator('.seg-n').textContent());

  const bob = await userContext(browser, 'bob');
  const create = async (title: string, kind: string) => {
    const r = await bob.page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body: '', kind } });
    expect(r.status()).toBe(201);
  };

  // A feature while "bug" is selected: counted, but not listed until the filter allows it.
  const feature = unique('Live feature');
  await create(feature, 'feature');
  await expect(header(page)).toContainText(`${n + 1} open issues`);
  await expect(kindFilter(page, 'all').locator('.seg-n')).toHaveText(String(allCount + 1));
  await expect(issues(page).filter({ hasText: feature })).toHaveCount(0);
  await kindFilter(page, 'all').click();
  await expect(issues(page).filter({ hasText: feature })).toHaveCount(1);
  await expect(issues(page).first()).toContainText(feature);

  // A bug while "bug" is selected: listed at once.
  await kindFilter(page, 'bug').click();
  const bug = unique('Live bug');
  await create(bug, 'bug');
  await expect(issues(page).filter({ hasText: bug })).toHaveCount(1);
  await expect(header(page)).toContainText(`${n + 2} open issues`);

  // The "closed" view never lists a new (open) issue; the header still counts it.
  await stateFilter(page, 'closed').click();
  await expect(stateFilter(page, 'closed')).toHaveAttribute('aria-checked', 'true');
  await expect(issues(page).filter({ hasText: bug })).toHaveCount(0);
  const late = unique('Live late');
  await create(late, 'bug');
  await expect(header(page)).toContainText(`${n + 3} open issues`);
  await expect(issues(page).filter({ hasText: late })).toHaveCount(0);
  await bob.context.close();
});

test('an issue filed by the chat agent appears without a reload', async ({ page }) => {
  await login(page, 'alice');
  const n = await openProject(page);
  await page.getByRole('button', { name: '+ New chat' }).click();
  await expect(page).toHaveURL(/\?session=/);
  const title = unique('Agent filed');
  const prompt = page.getByRole('textbox', { name: 'Message the agent' });
  await prompt.fill(`file issue: ${title}`);
  await prompt.press('Enter');
  await expect(page.locator('.chat-log')).toContainText('Created issue #');
  await expect(issues(page).filter({ hasText: title })).toHaveCount(1);
  await expect(header(page)).toContainText(`${n + 1} open issues`);
});

test('the creator sees a dialog-created issue exactly once', async ({ page }) => {
  await login(page, 'alice');
  const n = await openProject(page);
  const title = unique('Dialog issue');
  await page.locator('.panel--issues').getByRole('button', { name: '+ New' }).click();
  const dialog = page.getByRole('dialog', { name: 'New issue in demo-app' });
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByRole('button', { name: 'Create issue' }).click();
  await expect(dialog).toBeHidden();
  await expect(issues(page).filter({ hasText: title })).toHaveCount(1);
  // The counter only moves on the feed frame: once it has, both paths have delivered the issue.
  await expect(header(page)).toContainText(`${n + 1} open issues`);
  await expect(issues(page).filter({ hasText: title })).toHaveCount(1);
});
