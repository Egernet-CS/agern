import { expect, test } from 'playwright/test';
import { BASE, login, registerCredentials } from './support';

test.describe.configure({ mode: 'serial' });

test('without a GitHub token the projects page asks for one', async ({ page }) => {
  await login(page, 'alice');
  await expect(page.getByText('Add your GitHub token to see projects')).toBeVisible();
  await expect(page.locator('.project-card')).toHaveCount(0);
});

test('settings: register the fake GitHub token and a Claude credential; projects show the fake org', async ({ page }) => {
  await login(page, 'alice');
  await registerCredentials(page, 'alice');
  // A bogus token is rejected by (fake) GitHub with a clear message.
  await page.locator('#github').getByLabel('Personal access token').fill('ghp_not-a-fake-token');
  await page.locator('#github').getByRole('button', { name: 'Replace' }).click();
  await expect(page.locator('#github').getByRole('alert')).toContainText('GitHub rejected the token');

  // What the token can do is shown; a token without `repo` gets a clear warning and sees public repos only.
  const gh = page.locator('#github');
  await expect(gh.locator('.token-info')).toContainText('Classic token with scopes read:orgrepo');
  await gh.getByLabel('Personal access token').fill('fake-alice~public_repo');
  await gh.getByRole('button', { name: 'Replace' }).click();
  await expect(gh.getByText('This token can only see public repositories and cannot push')).toBeVisible();
  await page.goto(`${BASE()}/`);
  await expect(page.locator('.project-card')).toHaveCount(1);
  await expect(page.locator('.token-hint')).toContainText('Only public repositories are listed.');
  await page.locator('.token-hint').getByRole('link', { name: 'check it in Settings' }).click();
  await page.locator('#github').getByLabel('Personal access token').fill('fake-alice');
  await page.locator('#github').getByRole('button', { name: 'Replace' }).click();
  await expect(page.locator('#github').getByText('This token can only see public repositories')).toHaveCount(0);

  await page.goto(`${BASE()}/`);
  const cards = page.locator('.project-card');
  await expect(cards).toHaveCount(2);
  await expect(page.locator('.token-hint')).toHaveCount(0);
  await expect(cards.filter({ hasText: 'demo-app' })).toContainText('public');
  await expect(cards.filter({ hasText: 'private-lab' })).toContainText('private');
  await expect(page.locator('.hero-stats')).toContainText('projects2');
});

test('a second user without access does not see the private repository', async ({ page }) => {
  await login(page, 'bob');
  await registerCredentials(page, 'bob');
  await page.goto(`${BASE()}/`);
  await expect(page.locator('.project-card')).toHaveCount(1);
  await expect(page.locator('.project-card')).toContainText('demo-app');
  await expect(page.getByText('private-lab')).toHaveCount(0);
  await expect(page.locator('.token-hint')).toBeVisible();

  // Not by URL either, and not through the API.
  await page.goto(`${BASE()}/p/private-lab`);
  await expect(page.getByText('No project called “private-lab”')).toBeVisible();
  expect((await page.request.get(`${BASE()}/api/projects/private-lab/issues`)).status()).toBe(404);
});
