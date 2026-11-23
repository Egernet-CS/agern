import { expect, test, type Page } from 'playwright/test';
import { BASE, login, mutate } from './support';

// The meta row under an issue in the issues panel (issue #62): its items never break inside, and when the panel is
// narrow the actions (release picker, Start run / run in progress) wrap to their own right-aligned line instead of
// being clipped at the panel's edge.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const WIDTHS = [1440, 1180, 1000, 900, 760, 390];
const issueRows = (page: Page) => page.locator('.panel--issues .issue-list .issue');
const row = (page: Page, title: string) => issueRows(page).filter({ hasText: title });

const assignedTitle = `Layout assigned ${tag}`;
const runningTitle = `Layout running ${tag}`;
const release = `layout-release-${tag}`;
let assigned = 0;
let runId = '';

async function newIssue(page: Page, title: string, body = ''): Promise<number> {
  const r = await page.request.post(`${BASE()}/api/projects/demo-app/issues`, { headers: mutate, data: { title, body, kind: 'bug' } });
  expect(r.status()).toBe(201);
  return (await r.json()).number as number;
}

async function openProject(page: Page, width: number) {
  await page.setViewportSize({ width, height: 900 });
  await page.goto(`${BASE()}/p/demo-app`);
  if (width <= 760) await page.getByRole('tab', { name: 'issues' }).click();
  await expect(issueRows(page).first()).toBeVisible();
}

/** Every visible issue row: nothing sticks out of it, each text item is one line, and the panel never scrolls sideways. */
async function checkRows(page: Page, label: string) {
  const problems = await page.locator('.panel--issues').evaluate((panel) => {
    const out: string[] = [];
    if (panel.scrollWidth > panel.clientWidth + 1) out.push(`panel scrolls sideways: ${panel.scrollWidth} > ${panel.clientWidth}`);
    for (const issue of Array.from(panel.querySelectorAll<HTMLElement>('.issue-list .issue'))) {
      const r = issue.getBoundingClientRect();
      const name = issue.querySelector('.issue-num')?.textContent ?? '?';
      const meta = issue.querySelector<HTMLElement>('.issue-meta');
      if (!meta) continue;
      const action = meta.querySelector<HTMLElement>('.issue-action');
      const items = [...Array.from(meta.children), ...(action ? Array.from(action.children) : [])] as HTMLElement[];
      for (const el of items) {
        const b = el.getBoundingClientRect();
        if (b.width === 0 && b.height === 0) continue;
        if (b.left < r.left - 1 || b.right > r.right + 1) {
          out.push(`${name}: ${el.className || el.tagName} [${b.left}, ${b.right}] outside row [${r.left}, ${r.right}]`);
        }
      }
      // The Start run button, the run link and the release picker are fully inside the row.
      for (const el of Array.from(issue.querySelectorAll<HTMLElement>('.btn--run, .run-link, .release-pick select'))) {
        const b = el.getBoundingClientRect();
        if (b.left < r.left - 1 || b.right > r.right + 1 || b.width === 0) {
          out.push(`${name}: ${el.className || el.tagName} [${b.left}, ${b.right}] not fully inside row [${r.left}, ${r.right}]`);
        }
      }
      for (const span of Array.from(meta.querySelectorAll<HTMLElement>(':scope > span:not(.issue-action)'))) {
        const lines = span.getClientRects().length;
        if (lines !== 1) out.push(`${name}: "${span.textContent}" is ${lines} line boxes`);
        if (span.getBoundingClientRect().height > parseFloat(getComputedStyle(span).lineHeight || '0') * 1.5 + 2) {
          out.push(`${name}: "${span.textContent}" is taller than one line`);
        }
      }
    }
    return out;
  });
  expect(problems, label).toEqual([]);
}

test('open issues with a release tag and picker fit the issues panel at every width', async ({ page }) => {
  await login(page, 'alice');
  assigned = await newIssue(page, assignedTitle);
  await newIssue(page, `Layout plain ${tag}`);
  await openProject(page, 1440);

  await page.getByRole('button', { name: 'New release' }).click();
  const dialog = page.getByRole('dialog', { name: 'New release in demo-app' });
  await dialog.getByLabel('Name').fill(release);
  await dialog.getByRole('button', { name: 'Create release' }).click();
  await expect(dialog).toBeHidden();
  await page.getByLabel(`Release for #${assigned}`).selectOption({ label: release });
  await expect(row(page, assignedTitle).locator('.release-tag')).toHaveText(release);

  for (const width of WIDTHS) {
    await openProject(page, width);
    const r = row(page, assignedTitle);
    await expect(r.locator('.release-tag')).toBeVisible();
    await expect(r.getByLabel(`Release for #${assigned}`)).toBeVisible();
    await expect(r.getByRole('button', { name: '▶ Start run' })).toBeVisible();
    await checkRows(page, `open, ${width}px`);
  }
});

test('in a narrow panel the actions wrap to their own right-aligned line', async ({ page }) => {
  await login(page, 'alice');
  for (const width of [1180, 390]) {
    await openProject(page, width);
    const r = row(page, assignedTitle);
    const meta = (await r.locator('.issue-meta').boundingBox())!;
    const author = (await r.locator('.issue-meta > span').filter({ hasText: /^alice$/ }).boundingBox())!;
    const action = (await r.locator('.issue-action').boundingBox())!;
    expect(action.y, `${width}px`).toBeGreaterThanOrEqual(author.y + author.height - 1);
    expect(Math.abs(action.x + action.width - (meta.x + meta.width)), `${width}px`).toBeLessThanOrEqual(1);
  }
});

test('wide panels keep a row that fits on one line', async ({ page }) => {
  await login(page, 'alice');
  await openProject(page, 1440);
  // Without a release tag and comments the row fits: one line, the actions at the right edge.
  const r = row(page, `Layout plain ${tag}`);
  const meta = (await r.locator('.issue-meta').boundingBox())!;
  const author = (await r.locator('.issue-meta > span').filter({ hasText: /^alice$/ }).boundingBox())!;
  const action = (await r.locator('.issue-action').boundingBox())!;
  expect(action.y).toBeLessThan(author.y + author.height);
  expect(Math.abs(action.x + action.width - (meta.x + meta.width))).toBeLessThanOrEqual(1);
});

test('a row with a run in progress fits at every width', async ({ page }) => {
  await login(page, 'alice');
  await newIssue(page, runningTitle, 'Keeps running while the layout is checked. fake:hang-spec-writer');
  await openProject(page, 1440);
  await row(page, runningTitle).getByRole('button', { name: '▶ Start run' }).click();
  await expect(page).toHaveURL(/\/p\/demo-app\/runs\//);
  runId = page.url().split('/runs/')[1].split(/[?#/]/)[0];
  try {
    for (const width of WIDTHS) {
      await openProject(page, width);
      await expect(row(page, runningTitle).getByRole('link', { name: 'run in progress' })).toBeVisible();
      await checkRows(page, `run in progress, ${width}px`);
    }
  } finally {
    const r = await page.request.post(`${BASE()}/api/runs/${runId}/cancel`, { headers: mutate });
    expect([200, 409]).toContain(r.status());
  }
});

test('closed issues fit and get no empty action line', async ({ page }) => {
  await login(page, 'alice');
  for (const width of WIDTHS) {
    await openProject(page, width);
    await page.getByRole('radiogroup', { name: 'Issue state' }).getByRole('radio', { name: 'closed' }).click();
    await expect(page.getByRole('radio', { name: 'closed' })).toHaveAttribute('aria-checked', 'true');
    await expect(issueRows(page).first()).toBeVisible();
    await checkRows(page, `closed, ${width}px`);
    const actions = page.locator('.panel--issues .issue-list .issue .issue-action');
    const n = await actions.count();
    for (let k = 0; k < n; k++) {
      const box = await actions.nth(k).boundingBox();
      expect(box === null || box.height === 0, `closed row ${k} at ${width}px has an action line`).toBe(true);
    }
  }
});
