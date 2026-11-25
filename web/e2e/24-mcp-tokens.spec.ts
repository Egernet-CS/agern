import { expect, test } from 'playwright/test';
import { BASE, apiOf, login, mutate, userContext } from './support';

// Personal MCP tokens (docs/ARCHITECTURE.md §7 "Personal tokens"): Settings › Claude on your computer, the /mcp endpoint
// with the token, and the admin card. alice is an admin; bob has a GitHub token (02-settings-projects).
test.describe.configure({ mode: 'serial' });

const initialize = { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } };

test('create a token in Settings, use it on /mcp, revoke it', async ({ page, playwright }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/settings#mcp-tokens`);
  const section = page.locator('#mcp-tokens');
  await expect(section.getByRole('heading', { name: 'Claude on your computer' })).toBeVisible();
  await expect(section.getByText('No tokens yet.')).toBeVisible();
  const create = section.getByRole('button', { name: 'Create token' });
  await expect(create).toBeDisabled();
  await section.getByLabel('Name').fill('laptop');
  await section.getByLabel('Project').selectOption('demo-app');
  await expect(section.getByLabel('Expires')).toHaveValue('90');
  await create.click();

  const dialog = page.getByRole('dialog', { name: 'Your new token' });
  await expect(dialog.getByText('Shown only once.')).toBeVisible();
  const serverName = dialog.getByLabel('Server name in Claude');
  const port = new URL(BASE()).port;
  await expect(serverName).toHaveValue(`agern-127-0-0-1-${port}-demo-app`);
  const secret = (await dialog.getByTestId('mcp-secret').textContent())!.trim();
  expect(secret).toMatch(/^agern_pat_[A-Za-z0-9_-]{40,}$/);
  const command = dialog.getByTestId('mcp-command');
  await expect(command).toHaveText(`claude mcp add --transport http agern-127-0-0-1-${port}-demo-app ${BASE()}/mcp --header "Authorization: Bearer ${secret}"`);

  // The name is editable and validated; an invalid one disables copying the command.
  await serverName.fill('agern-work-demo');
  await expect(command).toContainText('claude mcp add --transport http agern-work-demo ');
  const copy = dialog.getByRole('button', { name: 'Copy', exact: true });
  await expect(copy).toBeEnabled();
  await serverName.fill('bad name!');
  await expect(dialog.getByRole('alert')).toHaveText('Use letters, digits and - only');
  await expect(copy).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Copy token' })).toBeEnabled();
  await dialog.getByRole('button', { name: 'I have copied it' }).click();
  await expect(dialog).toBeHidden();

  const row = section.locator('.mcp-tokens-table tbody tr', { hasText: 'laptop' });
  await expect(row).toContainText('demo-app');
  await expect(row.locator('td[data-label="last used"]')).toHaveText('never');

  // The token works on /mcp from outside the browser (no Origin header) and names the instance and project.
  const client = await playwright.request.newContext();
  const auth = { Authorization: `Bearer ${secret}`, Accept: 'application/json, text/event-stream' };
  const init = await client.post(`${BASE()}/mcp`, { headers: auth, data: initialize });
  expect(init.status()).toBe(200);
  const info = (await init.json()).result;
  expect(info.serverInfo.name).toBe(`agern-127-0-0-1-${port}-demo-app`);
  expect(info.instructions).toContain('demo-app');
  expect(info.instructions).toContain('alice');
  await page.reload();
  await expect(row.locator('td[data-label="last used"]')).not.toHaveText('never');

  // Revoke: confirm in a dialog; the token stops working at once.
  await row.getByRole('button', { name: 'Revoke token laptop for demo-app' }).click();
  const confirm = page.getByRole('dialog', { name: 'Revoke token "laptop"?' });
  await expect(confirm).toContainText('Claude on that computer loses access to demo-app immediately.');
  await confirm.getByRole('button', { name: 'Revoke' }).click();
  await expect(confirm).toBeHidden();
  await expect(section.getByText('No tokens yet.')).toBeVisible();
  const refused = await client.post(`${BASE()}/mcp`, { headers: auth, data: initialize });
  expect(refused.status()).toBe(401);
  expect((await refused.json()).error.message).toBe('This token was revoked');
  await client.dispose();
});

test("the admin card lists and revokes another user's token", async ({ page, browser }) => {
  const bob = await userContext(browser, 'bob');
  const created = await apiOf(bob.page).post(`${BASE()}/api/me/mcp-tokens`, {
    headers: mutate,
    data: { name: 'workstation', repo: 'demo-app', expiresInDays: 30 },
  });
  expect(created.status()).toBe(200);
  await bob.context.close();

  await login(page, 'alice');
  await page.goto(`${BASE()}/admin`);
  const card = page.locator('.settings-card', { hasText: 'Personal MCP tokens' });
  const row = card.locator('.mcp-tokens-table tbody tr', { hasText: 'workstation' });
  await expect(row.locator('td[data-label="user"]')).toContainText('bob');
  await expect(row.locator('td[data-label="project"]')).toHaveText('demo-app');
  await row.getByRole('button', { name: 'Revoke token workstation for demo-app' }).click();
  const confirm = page.getByRole('dialog', { name: 'Revoke token "workstation"?' });
  await expect(confirm).toContainText("bob's Claude loses access to demo-app immediately.");
  await confirm.getByRole('button', { name: 'Revoke' }).click();
  await expect(confirm).toBeHidden();
  await expect(card.getByText('No personal tokens.')).toBeVisible();
});
