import { expect, test } from 'playwright/test';
import { BASE, enrol, freshCode, readState, submitPassword, typeCode, writeState } from './support';

test.describe.configure({ mode: 'serial' });

test('first login enrols TOTP, shows recovery codes, then lets the user in', async ({ page }) => {
  await submitPassword(page, 'alice');
  // The enrolment screen shows the QR code and the secret as text.
  await expect(page.getByRole('img', { name: 'QR code for your authenticator app' })).toBeVisible();
  // A pending session is not logged in: the API says 401 everywhere else.
  expect((await page.request.get(`${BASE()}/api/me`)).status()).toBe(401);

  const codes = await enrol(page, 'alice');
  writeState({ aliceRecovery: codes });
  await expect(page).toHaveURL(`${BASE()}/`);
  await expect(page.getByText(/Good (morning|afternoon|evening|night), alice/)).toBeVisible();
  expect((await page.request.get(`${BASE()}/api/me`)).status()).toBe(200);
});

test('logout, then login with a code from the authenticator', async ({ page }) => {
  await submitPassword(page, 'alice');
  await expect(page.getByRole('heading', { name: 'Two-factor code' })).toBeVisible();
  await typeCode(page, await freshCode('alice'));
  await expect(page.locator('.topbar')).toBeVisible();

  await page.locator('.avatar-btn').click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
  expect((await page.request.get(`${BASE()}/api/me`)).status()).toBe(401);

  // Again, now with a recovery code.
  await submitPassword(page, 'alice');
  await page.getByRole('button', { name: 'Use a recovery code' }).click();
  await page.getByRole('textbox', { name: 'Recovery code' }).fill((readState().aliceRecovery as string[])[0]);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.locator('.topbar')).toBeVisible();
});

test('a wrong code is rejected; five wrong codes show the locked state', async ({ page }) => {
  await enrol(page, 'carol');
  await page.locator('.avatar-btn').click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();

  await submitPassword(page, 'carol');
  await expect(page.getByRole('heading', { name: 'Two-factor code' })).toBeVisible();
  const good = await freshCode('carol');
  const wrong = String((Number(good) + 1) % 1_000_000).padStart(6, '0');
  for (let i = 0; i < 4; i++) {
    await typeCode(page, wrong);
    await expect(page.getByRole('alert')).toHaveText('That code is not valid. Check your authenticator and try again.');
  }
  await typeCode(page, wrong);
  await expect(page.getByRole('heading', { name: 'Sign-in locked' })).toBeVisible();
  await expect(page.getByText(/locked until \d{2}[:.]\d{2}/)).toBeVisible();

  // Even the right code is refused while locked; the API answers 423 with lockedUntil.
  await page.getByRole('button', { name: 'Back to sign in' }).click();
  await submitPassword(page, 'carol');
  // The password step must have finished (pending-session cookie set) before calling the TOTP endpoint.
  await expect(page.getByRole('heading', { name: 'Two-factor code' })).toBeVisible();
  const res = await page.request.post(`${BASE()}/api/auth/totp`, { headers: { 'X-Factory-Request': '1' }, data: { code: good } });
  expect(res.status()).toBe(423);
  const body = await res.json();
  expect(body.error.code).toBe('locked');
  expect(Date.parse(body.error.lockedUntil)).toBeGreaterThan(Date.now() + 14 * 60_000);
});

test('repeated wrong passwords are rate limited', async ({ page }) => {
  for (let i = 0; i < 5; i++) {
    await submitPassword(page, 'dave', 'not-the-password');
    await expect(page.getByRole('alert')).toHaveText('Wrong username or password');
  }
  await submitPassword(page, 'dave');
  await expect(page.getByRole('alert')).toHaveText(/Too many failed sign-in attempts\. Try again after \d{1,2}[:.]\d{2}/);
  await expect(page.getByRole('heading', { name: 'Set up your authenticator' })).toHaveCount(0);
});
