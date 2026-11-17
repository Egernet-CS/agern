import { createHmac } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from 'playwright/test';

export const BASE = () => {
  const u = process.env.E2E_BASE_URL;
  if (!u) throw new Error('E2E_BASE_URL is not set (the global setup starts the server)');
  return u;
};

export const PASSWORDS: Record<string, string> = { alice: 'alice-pw', bob: 'bob-pw', carol: 'carol-pw', dave: 'dave-pw' };

// ------------------------------------------------------------------ shared state between spec files

type State = { secrets: Record<string, string>; lastStep: Record<string, number>; [k: string]: unknown };

const stateFile = () => join(process.env.E2E_STATE_DIR ?? '.', 'state.json');

export function readState(): State {
  const s = JSON.parse(readFileSync(stateFile(), 'utf8')) as Partial<State>;
  return { ...s, secrets: s.secrets ?? {}, lastStep: s.lastStep ?? {} };
}

export function writeState(patch: Partial<State>) {
  writeFileSync(stateFile(), JSON.stringify({ ...readState(), ...patch }, null, 2));
}

// ------------------------------------------------------------------ TOTP (RFC 6238, SHA-1, 6 digits, 30 s)

function base32Decode(s: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const c of s.replace(/[\s=]/g, '').toUpperCase()) {
    const i = alphabet.indexOf(c);
    if (i < 0) throw new Error(`bad base32 character ${c}`);
    value = (value << 5) | i;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

export function totpAt(secret: string, step: number): string {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(step));
  const h = createHmac('sha1', base32Decode(secret)).update(msg).digest();
  const o = h[h.length - 1] & 0x0f;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, '0');
}

const currentStep = () => Math.floor(Date.now() / 1000 / 30);

/**
 * A code the server will accept now: within ±1 step and newer than the last step used for this user
 * (the server rejects replays). Waits for the clock when necessary.
 */
export async function freshCode(user: string): Promise<string> {
  const st = readState();
  const secret = st.secrets[user];
  if (!secret) throw new Error(`no TOTP secret recorded for ${user}`);
  const last = st.lastStep[user] ?? -1;
  for (;;) {
    const cur = currentStep();
    const step = Math.max(last + 1, cur - 1);
    if (step <= cur + 1) {
      writeState({ lastStep: { ...readState().lastStep, [user]: step } });
      return totpAt(secret, step);
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
}

// ------------------------------------------------------------------ UI helpers

export async function typeCode(page: Page, code: string) {
  await page.locator('.code-input input').first().click();
  await page.keyboard.type(code, { delay: 20 });
}

export async function submitPassword(page: Page, user: string, password = PASSWORDS[user]) {
  await page.goto(`${BASE()}/login`);
  await page.locator('#u').fill(user);
  await page.locator('#p').fill(password);
  await page.getByRole('button', { name: 'Continue' }).click();
}

/** First sign-in: enrol TOTP from the secret shown, pass the recovery codes screen. Returns the codes. */
export async function enrol(page: Page, user: string): Promise<string[]> {
  await submitPassword(page, user);
  await expect(page.getByRole('heading', { name: 'Set up your authenticator' })).toBeVisible();
  const secret = (await page.locator('code.secret').innerText()).replace(/\s/g, '');
  expect(secret).toMatch(/^[A-Z2-7]{16,}$/);
  writeState({ secrets: { ...readState().secrets, [user]: secret } });
  await typeCode(page, await freshCode(user));
  await expect(page.getByRole('heading', { name: 'Save your recovery codes' })).toBeVisible();
  const codes = await page.locator('.recovery-codes code').allInnerTexts();
  expect(codes).toHaveLength(10);
  await page.getByLabel('I have saved my recovery codes').check();
  await page.getByRole('button', { name: 'Enter the factory' }).click();
  await expect(page.locator('.topbar')).toBeVisible();
  return codes;
}

/**
 * Signs in (enrolling on the first sign-in) and lands on the projects page. Reuses the user's last
 * session cookie when it is still valid: every fresh TOTP sign-in needs a new 30-second time step.
 */
export async function login(page: Page, user: string) {
  const saved = (readState().cookies as Record<string, Cookie[]> | undefined)?.[user];
  if (saved) {
    await page.context().addCookies(saved);
    if ((await page.request.get(`${BASE()}/api/me`)).status() === 200) {
      await page.goto(`${BASE()}/`);
      await expect(page.locator('.topbar')).toBeVisible();
      return;
    }
    await page.context().clearCookies();
  }
  if (!readState().secrets[user]) {
    await enrol(page, user);
  } else {
    await submitPassword(page, user);
    await expect(page.getByRole('heading', { name: 'Two-factor code' })).toBeVisible();
    await typeCode(page, await freshCode(user));
    await expect(page.locator('.topbar')).toBeVisible();
  }
  const cookies = await page.context().cookies();
  writeState({ cookies: { ...((readState().cookies as Record<string, Cookie[]>) ?? {}), [user]: cookies } });
}

type Cookie = Awaited<ReturnType<BrowserContext['cookies']>>[number];

/** A fresh browser context (own cookie jar) signed in as [user]. */
export async function userContext(browser: Browser, user: string): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL: BASE() });
  const page = await context.newPage();
  await login(page, user);
  return { context, page };
}

/** Calls the API with the page's cookies, the way the frontend does (CSRF header on mutations). */
export function apiOf(page: Page): APIRequestContext {
  return page.request;
}

export const mutate = { 'X-Factory-Request': '1' };

/** Registers the fake GitHub token and a Claude credential through the Settings page. */
export async function registerCredentials(page: Page, user: string) {
  await page.goto(`${BASE()}/settings`);
  const gh = page.locator('#github');
  await gh.getByLabel('Personal access token').fill(`fake-${user}`);
  await gh.getByRole('button', { name: /^(Save|Replace)$/ }).click();
  await expect(gh.getByText(`connected as @${user}`)).toBeVisible();
  const claude = page.locator('#claude');
  await claude.getByLabel('Token', { exact: true }).fill(`sk-ant-oat01-fake-${user}`);
  await claude.getByRole('button', { name: /^(Save|Replace)$/ }).click();
  await expect(claude.getByText('OAuth token registered')).toBeVisible();
}
