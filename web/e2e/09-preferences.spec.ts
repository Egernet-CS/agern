// Personal models and fix rounds: a user's own choices drive the agents they start, and nobody else's.
import { mkdirSync } from 'node:fs';
import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate, userContext } from './support';

test.describe.configure({ mode: 'serial' });

const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);
const shots = process.env.E2E_SCREENSHOTS;

test('a user picks their own models and fix rounds; their run uses them, others are unaffected', async ({ browser }) => {
  const bob = await userContext(browser, 'bob');
  const page = bob.page;
  await page.goto(`${BASE()}/settings#models`);
  const models = page.locator('#models');
  await expect(models.getByRole('heading', { name: 'Models & cost' })).toBeVisible();
  await expect(models).toContainText('billed to it');
  await expect(models.getByLabel('builder', { exact: true })).toHaveAttribute('placeholder', 'default (opus)');

  // The Economy preset is one click; then an explicit model and one fix round on top.
  await models.getByRole('button', { name: 'Economy', exact: true }).click();
  await expect(models.locator('.form-ok')).toContainText('Economy applied');
  await expect(models.getByLabel('security', { exact: true })).toHaveValue('haiku');
  await models.getByLabel('builder', { exact: true }).fill('claude-sonnet-5-5');
  await models.getByLabel('fix rounds', { exact: true }).selectOption('1');
  await models.getByRole('button', { name: 'Save models' }).click();
  await expect(models.locator('.form-ok')).toContainText('Saved');
  await page.reload();
  await expect(models.getByLabel('builder', { exact: true })).toHaveValue('claude-sonnet-5-5');
  await expect(models.locator('.pref-row', { hasText: 'builder' })).toContainText('your choice');
  await expect(models.locator('.pref-row', { hasText: 'approver' })).toContainText('default');
  if (shots) {
    mkdirSync(shots, { recursive: true });
    await models.screenshot({ path: `${shots}/settings-models-desktop.png` });
  }
  // Over the factory's maximum is refused by the API.
  const over = await page.request.put(`${BASE()}/api/me/preferences`, { headers: mutate, data: { maxFixRounds: 99 } });
  expect(over.status()).toBe(422);

  // Bob starts a run: its stages show his models before they start, and it has one fix round at most.
  const issue = await (await page.request.post(`${BASE()}/api/projects/demo-app/issues`, {
    headers: mutate, data: { title: 'Run with my own models', body: 'fake:always-changes', kind: 'task' },
  })).json();
  const run = await (await page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: issue.number } })).json();
  expect(run.maxRounds).toBe(1);
  await page.goto(`${BASE()}/p/demo-app/runs/${run.id}`);
  await expect(node(page, 'approver')).toHaveAttribute('aria-label', /model opus/);
  await expect(node(page, 'builder')).toHaveAttribute('aria-label', /model claude-sonnet-5-5/);
  await expect(node(page, 'security')).toHaveAttribute('aria-label', /model haiku/);
  await expect(node(page, 'spec-writer')).toHaveAttribute('aria-label', /model sonnet/);
  await expect(page.locator('.rh-title .pill')).toHaveText('needs human', { timeout: 45_000 });
  await expect(page.locator('.run-error')).toContainText('after 1 fix round');
  const done = await (await page.request.get(`${BASE()}/api/runs/${run.id}`)).json();
  expect(done.round).toBe(1);
  expect(done.stages.filter((s: { role: string }) => s.role === 'builder').map((s: { model: string }) => s.model)).toEqual(['claude-sonnet-5-5', 'claude-sonnet-5-5']);

  // Alice's choices are untouched by bob's.
  const alice = await userContext(browser, 'alice');
  const prefs = await (await alice.page.request.get(`${BASE()}/api/me/preferences`)).json();
  expect(prefs.effective.pipelineModels.builder).toEqual({ value: 'opus', source: 'default' });
  const defaults = await (await alice.page.request.get(`${BASE()}/api/admin/settings`)).json();
  expect(prefs.effective.maxFixRounds).toEqual({ value: defaults.maxFixRounds, source: 'default', max: defaults.maxFixRounds, clamped: false });
  expect(defaults.maxFixRounds).not.toBe(1);
  await alice.context.close();

  // Back to the defaults.
  await page.goto(`${BASE()}/settings#models`);
  await models.getByRole('button', { name: 'Default', exact: true }).click();
  await expect(models.getByLabel('builder', { exact: true })).toHaveValue('');
  await bob.context.close();
});

test('phone width: models section', async ({ browser }) => {
  test.skip(!shots, 'screenshots only');
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, baseURL: BASE() });
  const page = await ctx.newPage();
  await login(page, 'alice');
  await page.goto(`${BASE()}/settings#models`);
  await page.locator('#models').getByLabel('ux', { exact: true }).fill('haiku');
  await page.locator('#models').screenshot({ path: `${shots}/settings-models-phone.png` });
  await ctx.close();
});
