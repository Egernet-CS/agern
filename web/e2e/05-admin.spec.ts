import { expect, test } from 'playwright/test';
import { BASE, enrol, login, readState } from './support';

test.describe.configure({ mode: 'serial' });

test('admin changes a setting and it sticks', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/admin`);
  const card = page.locator('.settings-card', { hasText: 'Factory settings' });
  await expect(card.getByLabel('max fix rounds')).toHaveValue('2');
  await card.getByLabel('ux').selectOption('haiku');
  await card.getByLabel('max fix rounds').fill('3');
  await card.getByRole('button', { name: 'Save settings' }).click();
  await expect(card.getByRole('status')).toHaveText('Saved. Applies to agents started from now on.');
  await page.reload();
  await expect(card.getByLabel('ux')).toHaveValue('haiku');
  await expect(card.getByLabel('max fix rounds')).toHaveValue('3');
  const s = await (await page.request.get(`${BASE()}/api/admin/settings`)).json();
  expect(s.pipelineModels.ux).toBe('haiku');
  expect(s.maxFixRounds).toBe(3);
});

test('an agent files a software request; the admin resolves it', async ({ page }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/p/demo-app?session=${readState().aliceChat}`);
  const prompt = page.getByRole('textbox', { name: 'Message the agent' });
  await prompt.fill('please install the toolchain');
  await prompt.press('Enter');
  await expect(page.locator('.chat-log')).toContainText(/Request [0-9a-f-]+ filed for 'fake-toolchain'/);

  await page.goto(`${BASE()}/admin`);
  const card = page.locator('.settings-card', { hasText: 'Software requests' });
  const req = card.locator('.req', { hasText: 'fake-toolchain' });
  await expect(req).toContainText('demo-app · alice');
  await req.getByLabel('Note for fake-toolchain').fill('installed with brew');
  await req.getByRole('button', { name: 'Mark installed' }).click();
  await expect(card.locator('.req', { hasText: 'fake-toolchain' })).toHaveCount(0);
  await card.getByText('1 resolved').click();
  await expect(card.locator('.req-done')).toContainText('installed');
  await expect(card.locator('.req-done')).toContainText('installed with brew');
});

test('admin sees a locked account and resets its 2FA; the user enrols again', async ({ page, browser }) => {
  await login(page, 'alice');
  await page.goto(`${BASE()}/admin`);
  const row = page.locator('.users-table tr', { hasText: 'carol' });
  await expect(row).toContainText(/locked until \d{2}[:.]\d{2}/);
  await row.getByRole('button', { name: 'Reset 2FA' }).click();
  const dialog = page.getByRole('dialog', { name: 'Reset two-factor for carol?' });
  await dialog.getByRole('button', { name: 'Reset 2FA' }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toContainText('enrols at next login');

  // Carol was locked out (01-auth); now she is asked to set up her authenticator again.
  const ctx = await browser.newContext();
  const carol = await ctx.newPage();
  await enrol(carol, 'carol');
  await expect(carol).toHaveURL(`${BASE()}/`);
  await ctx.close();

  // Non-admins cannot reach the admin API.
  const bobCtx = await browser.newContext();
  const bob = await bobCtx.newPage();
  await login(bob, 'bob');
  await expect(bob.getByRole('link', { name: 'Admin' })).toHaveCount(0);
  expect((await bob.request.get(`${BASE()}/api/admin/users`)).status()).toBe(403);
  await bobCtx.close();
});
