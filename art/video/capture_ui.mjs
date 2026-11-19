// Records the real Agern UI (frontend mock mode) for the concept video.
//
//   node art/video/capture_ui.mjs <outDir> [baseUrl] [clip...]
//
// Needs the frontend running in mock mode (`npm run dev:mock -- --port 5175`, default baseUrl
// http://localhost:5175) and Playwright from a web/ checkout with node_modules (WEB_DIR, default
// ../../web relative to this file). CHROMIUM_PATH selects a Chromium/headless-shell binary.
//
// Frames come from the Chrome DevTools screencast (JPEG q94 at 1920x1080 device pixels:
// a 1440x810 viewport at device scale 4/3), not from Playwright's VP8 recordVideo, so text stays
// crisp. Chrome only emits a frame when the screen changes; every frame is written with its
// timestamp to <outDir>/<clip>/frames.json and compose.py resamples to a constant frame rate.
import { mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const webDir = process.env.WEB_DIR ?? path.resolve(here, '../../web');
const { chromium } = createRequire(path.join(webDir, 'package.json'))('playwright');

const [outDir, base = 'http://localhost:5175', ...only] = process.argv.slice(2);
if (!outDir) {
  console.error('usage: node capture_ui.mjs <outDir> [baseUrl] [clip...]');
  process.exit(2);
}

const VIEW = { width: 1440, height: 810 };
const DSF = 1920 / 1440;
// PNG is lossless but slow enough in headless Chrome to starve the page's timers; JPEG q94 keeps
// the UI's own timing intact. SCREENCAST_FORMAT=png to override.
const FORMAT = process.env.SCREENCAST_FORMAT ?? 'jpeg';
const EXT = FORMAT === 'png' ? 'png' : 'jpg';
// The "mock" flag in the top bar exists only in the simulated frontend; hide it for the film.
const CSS = '.mock-flag{display:none!important} *{caret-color:transparent}';

async function record(name, prepare, act) {
  if (only.length && !only.includes(name)) return;
  const dir = path.join(outDir, name);
  mkdirSync(dir, { recursive: true });
  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const ctx = await browser.newContext({ viewport: VIEW, deviceScaleFactor: DSF, reducedMotion: 'no-preference', colorScheme: 'dark' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await prepare(page);
  await page.addStyleTag({ content: CSS });
  await page.waitForTimeout(600);

  const cdp = await ctx.newCDPSession(page);
  const frames = [];
  const writes = [];
  let t0 = null;
  cdp.on('Page.screencastFrame', (f) => {
    const t = f.metadata.timestamp;
    if (t0 === null) t0 = t;
    const file = `${String(frames.length).padStart(5, '0')}.${EXT}`;
    frames.push({ file, t: +(t - t0).toFixed(4) });
    writes.push(Promise.resolve().then(() => writeFileSync(path.join(dir, file), Buffer.from(f.data, 'base64'))));
    cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
  });
  await cdp.send('Page.startScreencast', { format: FORMAT, quality: 94, maxWidth: 1920, maxHeight: 1080, everyNthFrame: 1 });
  const wall0 = Date.now();
  const marks = {};
  const mark = (k) => (marks[k] = (Date.now() - wall0) / 1000);
  await act(page, mark);
  await cdp.send('Page.stopScreencast');
  await Promise.all(writes);
  writeFileSync(path.join(dir, 'frames.json'), JSON.stringify({ frames, marks, duration: (Date.now() - wall0) / 1000 }, null, 1));
  console.log(`${name}: ${frames.length} frames, ${((Date.now() - wall0) / 1000).toFixed(1)} s${errors.length ? `  (page errors: ${errors.join(' | ').slice(0, 200)})` : ''}`);
  await browser.close();
}

const settle = async (page, url) => {
  await page.goto(base + url);
  await page.waitForLoadState('networkidle', { timeout: 60000 }).catch(() => {});
  await page.waitForTimeout(1200);
};

// (a) Terminal-style chat: ask a question, watch the streamed answer with tool calls.
await record(
  'chat',
  (p) => settle(p, '/p/web-shop'),
  async (p, mark) => {
    await p.waitForTimeout(1000);
    await p.locator('.prompt textarea').click();
    mark('type');
    await p.keyboard.type('Why are the cart tests failing?', { delay: 55 });
    await p.waitForTimeout(450);
    await p.keyboard.press('Enter');
    mark('sent');
    await p.waitForTimeout(9500);
    mark('done');
    await p.waitForTimeout(1500);
  },
);

// (b) Live pipeline: issue #42 is in its first review; two reviewers ask for changes, the builder
// is resumed for fix round 1, the reviewers pass, the approver approves and the PR is merged.
await record(
  'run',
  async (p) => {
    await settle(p, '/agents');
    await p.click('.band-run:has-text("Saved carts")');
    await p.waitForTimeout(900);
  },
  async (p, mark) => {
    await p.locator('.pipeline').first().waitFor();
    // Poll the run's status pill (first .pill on the page) until the run has merged.
    const t0 = Date.now();
    let last = '';
    while (Date.now() - t0 < 150000) {
      const st = (await p.locator('.pill').first().textContent().catch(() => '')).trim();
      if (st !== last) {
        mark(st);
        last = st;
      }
      if (st === 'merged') break;
      await p.waitForTimeout(250);
    }
    mark('merged');
    await p.waitForTimeout(3000);
  },
);

// (c) The agents overview: every agent you can see, across projects.
await record(
  'floor',
  (p) => settle(p, '/agents'),
  async (p) => {
    await p.waitForTimeout(7000);
  },
);

// (d) Observing a colleague: bob's chat agent, read-only, streaming live.
await record(
  'observe',
  async (p) => {
    await settle(p, '/p/billing-service');
  },
  async (p, mark) => {
    await p.click('.agent-btn:has-text("bob")');
    mark('open');
    await p.waitForTimeout(9000);
  },
);
