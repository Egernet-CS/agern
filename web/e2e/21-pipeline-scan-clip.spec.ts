import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate } from './support';

// The amber scan bar of a running stage (issue #74) is clipped to its node's rounded rectangle, inset to the inside of
// the border, through a per-node clip path on a non-animated wrapper, so its ends never stick out past the corners.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);

/** The structure of the spec-writer node's clipped bar; returns problems as strings so a failure lists all of them. */
async function checkClip(page: Page, label: string) {
  const sw = page.locator('.pipeline g[aria-label^="spec-writer: running"]');
  await expect(sw).toBeVisible();
  const problems = await sw.evaluate((g) => {
    const out: string[] = [];
    const scans = g.querySelectorAll('.pn-scan');
    if (scans.length !== 1) return [`expected one .pn-scan, found ${scans.length}`];
    const scan = scans[0];
    if (scan.hasAttribute('clip-path')) out.push('.pn-scan itself has a clip-path');
    const ref = scan.parentElement?.getAttribute('clip-path') ?? '';
    const m = /^url\(#(.+)\)$/.exec(ref);
    if (!m) return [...out, `wrapper clip-path is "${ref}"`];
    const id = m[1];
    const matches = document.querySelectorAll(`[id="${id}"]`);
    if (matches.length !== 1) out.push(`id ${id} matches ${matches.length} elements`);
    const clip = matches[0];
    if (!clip || clip.tagName.toLowerCase() !== 'clippath') return [...out, `#${id} is not a clipPath`];
    if (!g.contains(clip)) out.push(`#${id} is not inside the node`);
    const rect = clip.querySelector('rect');
    const box = g.querySelector('.pn-box');
    if (!rect || !box) return [...out, 'clip rect or .pn-box missing'];
    const num = (el: Element, a: string) => Number(el.getAttribute(a));
    const want: Record<string, number> = { x: 1, y: 1, rx: 10, width: num(box, 'width') - 2, height: num(box, 'height') - 2 };
    for (const [a, v] of Object.entries(want)) {
      if (num(rect, a) !== v) out.push(`clip rect ${a}=${rect.getAttribute(a)}, expected ${v}`);
    }
    const ids = Array.from(document.querySelectorAll('.pipeline clipPath')).map((c) => c.id);
    if (new Set(ids).size !== ids.length) out.push(`clip ids not unique: ${ids.join(', ')}`);
    return out;
  });
  expect(problems, label).toEqual([]);
  const builder = node(page, 'builder');
  await expect(builder.locator('.pn-scan')).toHaveCount(0);
  await expect(builder.locator('clipPath')).toHaveCount(0);
}

test('a running stage clips its scan bar to its own rounded box', async ({ page }) => {
  await login(page, 'alice');
  const title = `Scan clip ${tag}`;
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, {
    headers: mutate,
    data: { title, body: 'Keeps the spec-writer running. fake:hang-spec-writer', kind: 'bug' },
  });
  expect(r.status()).toBe(201);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${BASE()}/p/demo-app`);
  await page.locator('.panel--issues .issue-list .issue').filter({ hasText: title }).getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  const runId = page.url().split('/runs/')[1].split(/[?#/]/)[0];
  try {
    await expect(page.locator('.pipeline g[aria-label^="spec-writer: running"]')).toBeVisible({ timeout: 30_000 });
    await checkClip(page, 'wide layout');

    await page.setViewportSize({ width: 600, height: 900 });
    await expect(page.locator('.pipeline.pipeline--v')).toBeVisible();
    await checkClip(page, 'narrow layout');

    const sw = page.locator('.pipeline g[aria-label^="spec-writer: running"]');
    await sw.click();
    await expect(sw).toHaveClass(/is-selected/);
    await checkClip(page, 'selected');

    await page.emulateMedia({ reducedMotion: 'reduce' });
    await expect(sw.locator('.pn-scan')).toBeHidden();
  } finally {
    const c = await page.request.post(`${BASE()}/api/runs/${runId}/cancel`, { headers: mutate });
    expect([200, 409]).toContain(c.status());
  }
});
