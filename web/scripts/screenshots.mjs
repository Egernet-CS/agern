// Screenshot tour of the mock app. Usage:
//   VITE_MOCK=1 npx vite --port 5174 &   then   node scripts/screenshots.mjs [outDir] [baseUrl]
// Set CHROMIUM_PATH to use a specific Chromium build.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'screenshots';
const base = process.argv[3] ?? 'http://localhost:5174';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const sizes = { desktop: { width: 1440, height: 900 }, phone: { width: 390, height: 844 } };
const only = process.env.ONLY;

async function shot(name, size, fn, { full = false } = {}) {
  if (only && !name.includes(only)) return;
  const ctx = await browser.newContext({ viewport: sizes[size], deviceScaleFactor: size === 'phone' ? 2 : 1, reducedMotion: 'no-preference' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  try {
    await fn(page);
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${out}/${name}-${size}.png`, fullPage: full });
    console.log(`ok  ${name}-${size}${errors.length ? `  (console: ${errors.join(' | ').slice(0, 200)})` : ''}`);
  } catch (e) {
    console.log(`ERR ${name}-${size}: ${e.message.split('\n')[0]}`);
    await page.screenshot({ path: `${out}/${name}-${size}-error.png` }).catch(() => {});
  }
  await ctx.close();
}

const go = (path) => async (page) => {
  await page.goto(base + path);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(900);
};
const login = async (page, user, path = '/login?mock=loggedout') => {
  await page.goto(base + path);
  await page.fill('#u', user);
  await page.fill('#p', 'pw');
  await page.click('button:has-text("Continue")');
  await page.waitForTimeout(500);
};
const typeCode = async (page, code) => {
  await page.locator('.code-input input').first().click();
  await page.keyboard.type(code, { delay: 30 });
  await page.waitForTimeout(700);
};
const firstRunLink = async (page, text) => {
  await page.goto(`${base}/agents`);
  await page.waitForTimeout(1200);
  await page.click(`.band-run:has-text("${text}")`);
  await page.waitForTimeout(1500);
};

for (const size of ['desktop', 'phone']) {
  await shot('01-login', size, go('/login?mock=loggedout'));
  await shot('02-login-totp', size, async (p) => login(p, 'alice'));
  await shot('03-login-enroll', size, async (p) => login(p, 'carl'), { full: true });
  await shot('04-login-recovery-codes', size, async (p) => {
    await login(p, 'carl');
    await typeCode(p, '123456');
  }, { full: true });
  await shot('05-login-locked', size, async (p) => {
    await login(p, 'dora');
    await typeCode(p, '123456');
  });
  await shot('06-projects', size, go('/'), { full: true });
  await shot('07-projects-first-run', size, go('/?mock=fresh'), { full: true });
  await shot('08-project', size, go('/p/web-shop'));
  await shot('09-project-streaming', size, async (p) => {
    await go('/p/web-shop')(p);
    await p.locator('.prompt textarea').fill('Why are the cart tests failing?');
    await p.keyboard.press('Enter');
    await p.waitForTimeout(2600);
  });
  await shot('10-project-autocomplete', size, async (p) => {
    await go('/p/web-shop')(p);
    await p.locator('.prompt textarea').fill('/fa');
    await p.waitForTimeout(200);
  });
  if (size === 'phone') {
    await shot('11-project-issues-tab', size, async (p) => {
      await go('/p/web-shop')(p);
      await p.click('.project-tabs button:has-text("issues")');
    });
    await shot('12-project-activity-tab', size, async (p) => {
      await go('/p/web-shop')(p);
      await p.click('.project-tabs button:has-text("agents")');
    });
  }
  await shot('13-project-no-claude', size, go('/p/web-shop?mock=noclaude'));
  await shot('14-observing', size, async (p) => {
    await go('/p/billing-service')(p);
    if (size === 'phone') await p.click('.project-tabs button:has-text("agents")');
    await p.click('.agent-btn:has-text("bob")');
    await p.waitForTimeout(2500);
  });
  await shot('15-run-live', size, async (p) => {
    await firstRunLink(p, 'Saved carts');
  }, { full: true });
  await shot('16-run-live-later', size, async (p) => {
    await firstRunLink(p, 'Saved carts');
    await p.waitForTimeout(16000);
  }, { full: true });
  await shot('17-run-needs-human', size, async (p) => {
    await firstRunLink(p, 'new provider');
  }, { full: true });
  await shot('18-agents', size, go('/agents'), { full: true });
  await shot('19-settings', size, go('/settings'), { full: true });
  await shot('20-settings-regenerate', size, async (p) => {
    await go('/settings')(p);
    await p.click('button:has-text("Regenerate recovery codes")');
    await typeCode(p, '654321');
  });
  await shot('21-admin', size, go('/admin'), { full: true });
  await shot('22-admin-reset-2fa', size, async (p) => {
    await go('/admin')(p);
    await p.click('tr:has-text("Bob") button:has-text("Reset 2FA")');
  });
  await shot('23-new-issue', size, async (p) => {
    await go('/p/web-shop')(p);
    if (size === 'phone') await p.click('.project-tabs button:has-text("issues")');
    await p.click('button:has-text("+ New")');
  });
}

await browser.close();
