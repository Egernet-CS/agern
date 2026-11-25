// Screenshot tour of the mock app. Usage (from web/):
//   npm run dev:mock -- --host 127.0.0.1 --port 5174 --strictPort &
//   npm run shots -- [outDir] [baseUrl]          (defaults: screenshots, http://localhost:5174)
//
// Writes NN-name-desktop.png (1440×900, device scale 2) and NN-name-phone.png (390×844, scale 2) per shot,
// plus logo.png (the Agern logo at 512 px, transparent) for the manual's title page. ONLY=<substring>
// runs only the shots whose name contains it. CHROMIUM_PATH=<binary> uses a specific Chromium build.
//
// Exit status: 0 when every shot succeeded, 1 when any failed (a navigation or selector error or a
// timeout); the failed names are listed at the end and each failure leaves NN-name-size-error.png.
// Console errors of the page are only reported, never a failure.
//
// The getting-started manual (docs/manual/) includes these files: scripts/manual.sh runs this tour into
// build/manual/screenshots/, which is where the manual's \screenshot macro looks for them. A shot the
// manual uses must keep its name.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'screenshots';
const base = process.argv[3] ?? 'http://localhost:5174';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const sizes = { desktop: { width: 1440, height: 900 }, phone: { width: 390, height: 844 } };
const only = process.env.ONLY;
const failed = [];

// The top bar's "mock" badge is not part of the real app: hidden in every shot.
const HIDE_MOCK_FLAG = () => {
  const add = () => {
    const s = document.createElement('style');
    s.textContent = '.mock-flag { display: none !important; }';
    document.head.appendChild(s);
  };
  if (document.head) add();
  else document.addEventListener('DOMContentLoaded', add);
};

/**
 * One screenshot. Options: `full` (the whole page), `element` (a selector: only that element, via
 * locator.screenshot()), `clip` ({ x, y, width, height } in CSS pixels of the viewport).
 */
async function shot(name, size, fn, { full = false, element = null, clip = null } = {}) {
  if (only && !name.includes(only)) return;
  const ctx = await browser.newContext({ viewport: sizes[size], deviceScaleFactor: 2, reducedMotion: 'no-preference' });
  await ctx.addInitScript(HIDE_MOCK_FLAG);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  const path = `${out}/${name}-${size}.png`;
  try {
    await fn(page);
    await page.waitForTimeout(400);
    if (element) await page.locator(element).first().screenshot({ path, timeout: 15000 });
    else await page.screenshot({ path, fullPage: full, clip: clip ?? undefined });
    console.log(`ok  ${name}-${size}${errors.length ? `  (console: ${errors.join(' | ').slice(0, 200)})` : ''}`);
  } catch (e) {
    failed.push(`${name}-${size}`);
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
// Run and release ids are random per page load: they are reached by clicking, never by URL.
const projectRun = async (page, path, status, title) => {
  await go(path)(page);
  await page.click(`.run-row--${status}:has-text("${title}")`);
  await page.waitForSelector('[aria-label="Pipeline diagram"]');
  await page.waitForTimeout(800);
};
const release = async (page, name) => {
  await go('/p/web-shop')(page);
  await page.click(`.release-row:has(.rr-title:text-is("${name}"))`);
  await page.waitForSelector('.release-issues');
  await page.waitForTimeout(600);
};
const stageNode = (page, role) => page.locator(`[aria-label="Pipeline diagram"] [role="button"][aria-label^="${role}:" i]`).first();

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

// ---- Screens of the getting-started manual (desktop). Element and clipped shots keep the text legible.
const D = 'desktop';

// Settings, card by card.
await shot('24-settings-fresh', D, go('/settings?mock=fresh'));
await shot('25-settings-claude', D, go('/settings?mock=fresh'), { element: '#claude' });
await shot('26-settings-github', D, go('/settings'), { element: '#github' });
await shot('27-settings-images', D, go('/settings'), { element: '#images' });
await shot('28-settings-models', D, async (p) => {
  await go('/settings')(p);
  await p.waitForSelector('#models .pref-grid');
}, { element: '#models' });

// The project page: the icon menu (admins) and a chat message with an attachment.
await shot('29-project-icon', D, async (p) => {
  await go('/p/web-shop')(p);
  await p.click('[aria-label="Change project icon"]');
  await p.waitForSelector('.ph-icon-menu');
}, { clip: { x: 0, y: 56, width: 720, height: 250 } });
await shot('30-chat-attachment', D, async (p) => {
  await go('/p/web-shop')(p);
  // Any demo image will do as the attachment: a picture of the issue list.
  const picture = await p.locator('.panel--issues').screenshot();
  await p.setInputFiles('.panel--chat input[type="file"]', { name: 'checkout-total.png', mimeType: 'image/png', buffer: picture });
  await p.waitForSelector('.att-chips');
  await p.waitForTimeout(900);
  await p.locator('.prompt textarea').fill('The total on this screenshot is wrong. Where is it computed?');
  await p.keyboard.press('Enter');
  await p.waitForTimeout(3500);
}, { element: '.panel--chat' });

// Runs.
await shot('31-run-merged', D, async (p) => projectRun(p, '/p/design-system', 'merged', 'Dark theme tokens'));
await shot('32-run-reviewer-screenshots', D, async (p) => {
  await projectRun(p, '/p/web-shop', 'merged', 'Upgrade Ktor');
  await stageNode(p, 'ux').click();
  await p.waitForSelector('.stage-detail .shots');
}, { element: '.stage-detail' });
const startRun57 = async (p) => {
  await go('/p/web-shop')(p);
  await p.locator('.panel--issues li:has-text("#57")').locator('button:has-text("Start run")').click();
  await p.waitForSelector('[aria-label="Pipeline diagram"]');
  await p.waitForSelector('.chat-steer-note', { timeout: 20000 });
  await p.waitForTimeout(2500);
};
await shot('33-run-started', D, startRun57);
await shot('49-run-steer', D, startRun57, { element: '.stage-detail' });
await shot('34-run-talk', D, async (p) => {
  await firstRunLink(p, 'new provider');
  await p.click('button:has-text("Talk to the agent")');
  await p.locator('.conv-panel textarea').fill('Why did the approver escalate, and is the new provider covered by tests?');
  await p.keyboard.press('Enter');
  await p.waitForTimeout(6000);
}, { element: '.conv-panel' });
await shot('35-run-retry', D, async (p) => {
  // bob's failed run: only its owner may retry it.
  await projectRun(p, '/p/web-shop?mock=observer', 'failed', 'Move product images');
  await p.click('button:has-text("Retry…")');
  await p.waitForSelector('dialog[open], [role="dialog"]');
  await p.waitForTimeout(600);
});

// Run pages without their long transcripts: the head and the diagram fill the viewport.
await shot('50-run-reviewing', D, async (p) => firstRunLink(p, 'Saved carts'));
await shot('51-run-needs-human', D, async (p) => firstRunLink(p, 'new provider'));
await shot('52-run', 'phone', async (p) => firstRunLink(p, 'Saved carts'));

// Releases.
await shot('36-project-releases', D, async (p) => {
  await go('/p/web-shop')(p);
  await p.locator('.release-list').first().scrollIntoViewIfNeeded();
}, { element: '.panel--activity' });
await shot('37-release-planning', D, async (p) => release(p, '1.4'), { full: true });
await shot('38-release-building', D, async (p) => release(p, '1.3'), { full: true });
await shot('39-release-ready', D, async (p) => release(p, '1.2'), { full: true });
await shot('40-release-mode', D, async (p) => release(p, '1.4'), { element: '.release-mode' });
await shot('41-new-release', D, async (p) => {
  await go('/p/web-shop')(p);
  await p.click('[aria-label="New release"]');
  await p.waitForSelector('#rn');
  await p.fill('#rn', '1.5');
});

// Sandboxes.
await shot('42-sandbox-approved', D, go('/p/web-shop/sandbox'), { full: true });
await shot('43-sandbox-pending', D, go('/p/data-pipeline/sandbox'), { full: true });
await shot('44-admin-sandboxes', D, async (p) => {
  await go('/admin')(p);
  await p.waitForSelector('section[aria-labelledby="sb-h"]');
}, { element: 'section[aria-labelledby="sb-h"]' });
await shot('45-admin-users', D, async (p) => {
  await go('/admin')(p);
  await p.waitForSelector('section[aria-labelledby="u-h"] tr');
}, { element: 'section[aria-labelledby="u-h"]' });

// The top bar: the usage indicator near a limit, and the user menu with the version.
await shot('46-usage-high', D, async (p) => {
  await go('/?mock=usage-high')(p);
  await p.waitForSelector('.usage-ind');
}, { clip: { x: 720, y: 0, width: 720, height: 58 } });
await shot('47-usage-limit', D, async (p) => {
  await go('/?mock=usage-limit')(p);
  await p.waitForSelector('.usage-ind');
}, { clip: { x: 720, y: 0, width: 720, height: 58 } });
await shot('48-user-menu', D, async (p) => {
  await go('/')(p);
  await p.click('.avatar-btn');
  await p.waitForSelector('.usermenu-version');
}, { clip: { x: 1040, y: 0, width: 400, height: 260 } });

// The logo for the title page, rendered from the app's own SVG (pdfLaTeX cannot read SVG).
if (!only || 'logo'.includes(only)) {
  const ctx = await browser.newContext({ viewport: { width: 600, height: 600 } });
  const page = await ctx.newPage();
  try {
    await page.setContent(`<img id="logo" src="${base}/art/logo.svg" style="width:512px;height:512px;display:block">`);
    await page.waitForFunction(() => document.getElementById('logo')?.complete);
    await page.locator('#logo').screenshot({ path: `${out}/logo.png`, omitBackground: true });
    console.log('ok  logo');
  } catch (e) {
    failed.push('logo');
    console.log(`ERR logo: ${e.message.split('\n')[0]}`);
  }
  await ctx.close();
}

await browser.close();
if (failed.length) {
  console.log(`\n${failed.length} shot(s) failed: ${failed.join(', ')}`);
  process.exitCode = 1;
} else {
  console.log('\nall shots ok');
}
