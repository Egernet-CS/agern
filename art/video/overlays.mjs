// Renders the video's text overlays (title, captions, end card) as transparent 1920x1080 PNGs,
// set in the UI's own fonts (JetBrains Mono, Hanken Grotesk) and colours.
//
//   node art/video/overlays.mjs <outDir>
//
// Reads art/video/storyboard.json and web/public/art/logo.svg. Fonts and Playwright come from a
// web/ checkout with node_modules (WEB_DIR, default ../../web). CHROMIUM_PATH as in capture_ui.mjs.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const webDir = process.env.WEB_DIR ?? path.join(root, 'web');
const { chromium } = createRequire(path.join(webDir, 'package.json'))('playwright');
const outDir = process.argv[2];
if (!outDir) {
  console.error('usage: node overlays.mjs <outDir>');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

const sb = JSON.parse(readFileSync(path.join(here, 'storyboard.json'), 'utf8'));
const logo = readFileSync(path.join(root, 'web/public/art/logo.svg'), 'utf8').replace(/<\?xml[^>]*>/, '');
const font = (pkg, file) => pathToFileURL(path.join(webDir, 'node_modules/@fontsource-variable', pkg, 'files', file)).href;

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
// Highlight verdict words the way the UI colours them.
const marks = (s) =>
  esc(s)
    .replace(/\bPASS\b/g, '<b class="pass">PASS</b>')
    .replace(/\bCHANGES\b/g, '<b class="changes">CHANGES</b>')
    .replace(/\bAPPROVE\b/g, '<b class="pass">APPROVE</b>')
    .replace(/\bESCALATE\b/g, '<b class="esc">ESCALATE</b>')
    .replace(/\bneeds-human\b/g, '<b class="esc">needs-human</b>');

const css = `
@font-face { font-family: 'JBM'; src: url(${font('jetbrains-mono', 'jetbrains-mono-latin-wght-normal.woff2')}) format('woff2'); font-weight: 100 800; }
@font-face { font-family: 'HG'; src: url(${font('hanken-grotesk', 'hanken-grotesk-latin-wght-normal.woff2')}) format('woff2'); font-weight: 100 900; }
html, body { margin: 0; width: 1920px; height: 1080px; background: transparent; overflow: hidden; }
body { color: #e7eaf0; font-family: 'JBM', monospace; -webkit-font-smoothing: antialiased; }
.cap { position: absolute; left: 160px; bottom: 40px; max-width: 1640px; }
.cap .label { font-size: 23px; letter-spacing: 0.14em; text-transform: uppercase; color: #ffb14a; margin-bottom: 12px; }
.cap .label::before { content: '> '; color: #5d687b; }
.cap .text { font-size: 38px; font-weight: 500; line-height: 1.2; letter-spacing: -0.005em; text-shadow: 0 2px 18px rgba(0,0,0,.85); }
b { font-weight: 700; }
.pass { color: #5ad6a0; } .changes { color: #8fb4ff; } .esc { color: #ff78ae; }
.center { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.mark svg { width: 150px; height: 150px; display: block; filter: drop-shadow(0 0 28px rgba(255,160,60,.45)); }
.name { font-family: 'HG', sans-serif; font-weight: 650; font-size: 128px; letter-spacing: -0.02em; line-height: 1; margin-top: 30px; }
.name span { color: #5d687b; font-weight: 300; }
.tag { font-size: 34px; letter-spacing: 0.32em; text-transform: uppercase; color: #ffb14a; margin-top: 22px; }
.sub { font-family: 'HG', sans-serif; font-size: 34px; color: #aeb7c7; margin-top: 34px; }
.lines { margin-top: 56px; display: flex; flex-direction: column; gap: 20px; align-items: flex-start; }
.lines div { font-size: 42px; font-weight: 500; }
.lines div::before { content: '·  '; color: #ffb14a; }
.url { margin-top: 64px; font-size: 36px; color: #ffd08a; border: 1px solid #3b475e; border-radius: 12px; padding: 16px 30px; background: rgba(18,23,34,.7); }
.endmark svg { width: 96px; height: 96px; }
.endname { font-family: 'HG', sans-serif; font-weight: 650; font-size: 76px; margin-top: 18px; }
.endname span { display: block; color: #ffb14a; font-family: 'JBM', monospace; font-weight: 400; font-size: 26px; letter-spacing: .3em; text-transform: uppercase; margin-top: 12px; }
`;

function html(c) {
  if (c.kind === 'title') {
    const t = sb.title;
    return `<div class="center"><div class="mark">${logo}</div>
      <div class="name">${esc(t.name)}</div>
      <div class="tag">${esc(t.tagline)}</div>
      <div class="sub">${esc(t.sub)}</div></div>`;
  }
  if (c.kind === 'end') {
    const e = sb.end;
    return `<div class="center"><div class="mark endmark">${logo}</div>
      <div class="endname">${esc(sb.title.name)}<span>${esc(sb.title.tagline)}</span></div>
      <div class="lines">${e.lines.map((l) => `<div>${esc(l)}</div>`).join('')}</div>
      <div class="url">${esc(e.url)}</div></div>`;
  }
  return `<div class="cap"><div class="label">${esc(c.label)}</div><div class="text">${marks(c.text)}</div></div>`;
}

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
for (const c of sb.captions) {
  const file = path.join(outDir, `_${c.id}.html`);
  writeFileSync(file, `<!doctype html><meta charset="utf-8"><style>${css}</style><body>${html(c)}</body>`);
  await page.goto(pathToFileURL(file).href);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(outDir, `${c.id}.png`), omitBackground: true });
  console.log('overlay', c.id);
}
await browser.close();
