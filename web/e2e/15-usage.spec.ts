// The user's own Claude usage: top-bar indicator and Settings → "Models & cost", live from the fake CLI's rate_limit_events
// ("usage-ok", "usage-high", "usage-limit" in a chat message; docs/ARCHITECTURE.md §10).
import { mkdirSync } from 'node:fs';
import { expect, test, type Page } from 'playwright/test';
import { BASE, mutate, registerCredentials, userContext } from './support';

test.describe.configure({ mode: 'serial' });

const USER = 'carol';
const shots = process.env.E2E_SCREENSHOTS;
const indicator = (page: Page) => page.locator('.topbar .usage-ind');
const usageBlock = (page: Page) => page.locator('#models #usage');

test('subscription and API key usage, live, and private to the user', async ({ browser }) => {
  const { context, page } = await userContext(browser, USER);

  // A freshly saved OAuth token: no data yet, and no 0 %.
  await registerCredentials(page, USER);
  await expect(indicator(page)).toHaveAttribute('data-level', 'none');
  await expect(indicator(page)).toHaveText('no usage data');
  await expect(indicator(page)).toHaveAttribute('aria-label', /^Claude usage: no data yet/);
  await expect(usageBlock(page)).toContainText('No usage data yet. It appears here once one of your agents has run.');
  await expect(usageBlock(page)).toContainText('they are not refreshed in between');
  await expect(usageBlock(page)).not.toContainText('0%');

  // A chat whose agent reports its limits: the indicator follows without a reload.
  await page.goto(`${BASE()}/p/demo-app`);
  await page.getByRole('button', { name: 'Start chat' }).click();
  await expect(page).toHaveURL(/\?session=/);
  const sessionId = new URL(page.url()).searchParams.get('session')!;
  const prompt = page.getByRole('textbox', { name: 'Message the agent' });
  await prompt.fill('how is my usage-ok');
  await prompt.press('Enter');
  await expect(page.locator('.chat-log')).toContainText('You said: how is my usage-ok');
  await expect(indicator(page)).toHaveAttribute('data-level', 'ok');
  await expect(indicator(page)).toHaveText('5h 42%');
  await expect(indicator(page)).toHaveAttribute('title', /^Claude usage: 5-hour session 42% used, resets in 2h \d\dm, updated /);

  // The indicator links to the details.
  await indicator(page).click();
  await expect(page).toHaveURL(/\/settings#models$/);
  const block = usageBlock(page);
  const five = block.locator('.usage-row', { hasText: '5-hour session' });
  const week = block.locator('.usage-row', { hasText: 'Weekly' });
  await expect(five).toContainText('42% used');
  await expect(five).toContainText('resets in 2h');
  await expect(five).toContainText('updated');
  await expect(five.getByRole('img', { name: '42% used' })).toBeVisible();
  await expect(week).toContainText('18% used');
  await expect(week).toContainText(/resets in \dd \d+h/);
  await expect(block.locator('.usage-row').first()).toContainText('5-hour session');
  await expect(block).toContainText(/Spent through Agern: today \$\d+\.\d\d · this month \$\d+\.\d\d \(UTC days\)/);
  await expect(block).not.toContainText('today $0.00');

  // Nobody else sees it: another user's own usage has none of these windows.
  const other = await userContext(browser, 'alice');
  const theirs = await (await other.page.request.get(`${BASE()}/api/me/usage`)).json();
  expect(theirs.windows).toEqual([]);
  expect(JSON.stringify(theirs)).not.toContain('0.42');
  await other.context.close();

  // High usage, while Settings stays open: the frame updates both places.
  const send = async (text: string) =>
    expect((await page.request.post(`${BASE()}/api/sessions/${sessionId}/input`, { headers: mutate, data: { text } })).status()).toBe(202);
  await send('now usage-high please');
  await expect(indicator(page)).toHaveAttribute('data-level', 'high');
  await expect(indicator(page)).toContainText('86%');
  await expect(indicator(page)).toContainText('high');
  await expect(indicator(page)).toHaveAttribute('aria-label', /^Claude usage: 5-hour session 86% used \(high\)/);
  await expect(five).toContainText('86% used');
  await expect(five.locator('.usage-badge')).toHaveText(/high$/);
  if (shots) {
    mkdirSync(shots, { recursive: true });
    await page.locator('.topbar').screenshot({ path: `${shots}/usage-indicator-high.png` });
  }

  // A reached limit, with the reset time.
  await send('and usage-limit now');
  await expect(indicator(page)).toHaveAttribute('data-level', 'limit');
  await expect(indicator(page)).toContainText('5h limit reached · resets in');
  await expect(five.locator('.usage-badge')).toHaveText(/limit reached$/);
  await expect(five).toHaveAttribute('data-level', 'limit');
  if (shots) {
    await page.locator('.topbar').screenshot({ path: `${shots}/usage-indicator-limit.png` });
    await page.locator('#models').screenshot({ path: `${shots}/settings-usage-desktop.png` });
  }

  // An API key: spend through Agern only, said plainly.
  const claude = page.locator('#claude');
  await claude.getByText('Anthropic API key', { exact: true }).click();
  await claude.getByLabel('API key', { exact: true }).fill(`sk-ant-api03-fake-${USER}`);
  await claude.getByRole('button', { name: 'Replace' }).click();
  await expect(claude.getByText('API key registered')).toBeVisible();
  await expect(indicator(page)).toHaveAttribute('data-level', 'spend');
  await expect(indicator(page)).toContainText(/^\$\d+\.\d\d this month$/);
  await expect(indicator(page)).toHaveAttribute('aria-label', /not the remaining balance of your Anthropic account/);
  await expect(block).toContainText(
    'This is what your agents spent through Agern — not the remaining balance of your Anthropic account, which Agern cannot read with an API key.',
  );
  await expect(block.locator('.usage-row')).toHaveCount(0);
  await expect(block).toContainText('Spent through Agern: today $');

  // No credential: no indicator, no usage block.
  await claude.getByRole('button', { name: 'Remove' }).click();
  await expect(claude.getByText('not set — chat and runs are disabled')).toBeVisible();
  await expect(page.locator('.usage-ind')).toHaveCount(0);
  await expect(page.locator('#usage')).toHaveCount(0);

  // Back to an OAuth token for later specs: its windows start empty again.
  await claude.getByText('OAuth token (setup-token)', { exact: true }).click();
  await claude.getByLabel('Token', { exact: true }).fill(`sk-ant-oat01-fake-${USER}`);
  await claude.getByRole('button', { name: 'Save' }).click();
  await expect(claude.getByText('OAuth token registered')).toBeVisible();
  await expect(indicator(page)).toHaveText('no usage data');
  await context.close();
});
