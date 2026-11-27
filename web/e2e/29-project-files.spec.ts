import { createHash } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from 'playwright/test';
import { BASE, mutate, registerCredentials, userContext } from './support';

// Project files: files a project needs to build but must not commit. An admin uploads one through the Files page; a
// user who can see the project sees it read-only, and a run's builder gets it read-only (`fake:project-files`); a
// private project's files stay invisible; downloads only once switched on; deleting through the confirmation dialog.
test.describe.configure({ mode: 'serial' });

const tag = Date.now().toString(36);
const NAME = `e2e/pf-${tag}.bin`;
const CONTENT = Buffer.from(`agern project file ${tag}\n`.repeat(40));
const SHA = createHash('sha256').update(CONTENT).digest('hex');
const PRIVATE_NAME = `pf-private-${tag}.txt`;
const PRIVATE_CONTENT = Buffer.from(`private lab file ${tag}\n`);

const row = (page: Page, name = NAME) => page.locator(`.pf-table tr[data-file="${name}"]`);
const runPill = (page: Page) => page.locator('.rh-title .pill');
const node = (page: Page, title: string) => page.locator(`.pipeline g[aria-label^="${title}:"]`);

async function fileId(request: APIRequestContext, repo: string, name: string): Promise<string> {
  const res = await request.get(`${BASE()}/api/projects/${repo}/files`);
  expect(res.status()).toBe(200);
  const f = ((await res.json()).files as { id: string; name: string }[]).find((x) => x.name === name);
  expect(f, `${name} in ${repo}`).toBeTruthy();
  return f!.id;
}

/** Uploads [content] as [name] through the API (create, one chunk, complete), as the frontend does. */
async function uploadViaApi(request: APIRequestContext, repo: string, name: string, content: Buffer): Promise<string> {
  const created = await request.post(`${BASE()}/api/projects/${repo}/file-uploads`, { headers: mutate, data: { name, size: content.length } });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  const chunk = await request.put(`${BASE()}/api/projects/${repo}/file-uploads/${id}?offset=0`, {
    headers: { ...mutate, 'Content-Type': 'application/octet-stream' },
    data: content,
  });
  expect(chunk.status()).toBe(200);
  expect((await chunk.json()).received).toBe(content.length);
  const done = await request.post(`${BASE()}/api/projects/${repo}/file-uploads/${id}/complete`, { headers: mutate, data: {} });
  expect(done.status()).toBe(200);
  const file = await done.json();
  expect(file.sha256).toBe(createHash('sha256').update(content).digest('hex'));
  return file.id as string;
}

test.beforeAll(async ({ browser }) => {
  // Start from an empty store in both projects (the run below reports every file of demo-app).
  const { context, page } = await userContext(browser, 'alice');
  await registerCredentials(page, 'alice');
  for (const repo of ['demo-app', 'private-lab']) {
    const res = await page.request.delete(`${BASE()}/api/admin/project-files/${repo}`, { headers: mutate });
    expect([204, 404]).toContain(res.status());
  }
  await context.close();
});

test.afterAll(async ({ browser }) => {
  const { context, page } = await userContext(browser, 'alice');
  for (const repo of ['demo-app', 'private-lab']) await page.request.delete(`${BASE()}/api/admin/project-files/${repo}`, { headers: mutate });
  await context.close();
});

test('an admin uploads a file through the Files page and sees its SHA-256', async ({ browser }) => {
  const { context, page } = await userContext(browser, 'alice');
  await page.goto(`${BASE()}/p/demo-app`);
  const projectRow = page.locator('.panel--activity .pf-row');
  await expect(projectRow).toContainText('No files');
  await projectRow.click();
  await expect(page).toHaveURL(/\/p\/demo-app\/files$/);
  await expect(page).toHaveTitle('Files · demo-app · Agern');
  await expect(page.getByText('No files yet.')).toBeVisible();
  await expect(page.getByText('are never committed or sent to GitHub')).toBeVisible();
  await expect(page.locator('.pf-usage')).toContainText('in this project');
  await expect(page.getByRole('heading', { name: 'Who can manage files' })).toBeVisible();

  await page.locator('input.pf-input').setInputFiles({ name: `pf-${tag}.bin`, mimeType: 'application/octet-stream', buffer: CONTENT });
  const dialog = page.getByRole('dialog', { name: 'Upload a file' });
  const nameField = dialog.getByLabel('Name');
  await expect(nameField).toHaveValue(`pf-${tag}.bin`);
  // Checked before any request.
  await nameField.fill('../escape.bin');
  await dialog.getByRole('button', { name: 'Upload' }).click();
  await expect(dialog.getByRole('alert')).toContainText('1–200 characters');
  await nameField.fill(NAME);
  await dialog.getByLabel('Note').fill('E2E test file');
  await dialog.getByRole('button', { name: 'Upload' }).click();
  await expect(dialog).toBeHidden();

  await expect(page.locator('.pf-live')).toHaveText(`Uploaded ${NAME}`);
  const r = row(page);
  await expect(r).toContainText(SHA.slice(0, 12));
  await expect(r.locator(`code[title="${SHA}"]`)).toBeVisible();
  await expect(r).toContainText('v1');
  await expect(r).toContainText('E2E test file');
  await expect(r).toContainText('download off');
  await expect(r.getByRole('checkbox', { name: `Allow download of ${NAME}` })).not.toBeChecked();
  await expect(page.getByRole('link', { name: `Download ${NAME}` })).toHaveCount(0);
  await context.close();
});

test('another user sees it read-only, and a run’s builder gets it read-only', async ({ browser }) => {
  const bob = await userContext(browser, 'bob');
  await registerCredentials(bob.page, 'bob');
  await bob.page.goto(`${BASE()}/p/demo-app/files`);
  const r = row(bob.page);
  await expect(r).toContainText(SHA.slice(0, 12));
  await expect(r).toContainText('download off');
  await expect(bob.page.getByText(/^Files are managed by admins/)).toBeVisible();
  await expect(bob.page.getByRole('link', { name: `Download ${NAME}` })).toHaveCount(0);
  for (const name of ['Upload file', `Replace ${NAME}`, `Edit the note of ${NAME}`, `Delete ${NAME}`]) {
    await expect(bob.page.getByRole('button', { name, exact: true })).toHaveCount(0);
  }
  await expect(bob.page.getByRole('checkbox', { name: `Allow download of ${NAME}` })).toHaveCount(0);
  await expect(bob.page.getByRole('heading', { name: 'Who can manage files' })).toHaveCount(0);
  // The server enforces it, not only the UI.
  const id = await fileId(bob.page.request, 'demo-app', NAME);
  expect((await bob.page.request.delete(`${BASE()}/api/projects/demo-app/files/${id}`, { headers: mutate })).status()).toBe(403);
  expect((await bob.page.request.get(`${BASE()}/api/projects/demo-app/files/${id}/content`)).status()).toBe(403);

  const issue = await bob.page.request.post(`${BASE()}/api/projects/demo-app/issues`, {
    headers: mutate,
    data: { title: `Project files ${tag}`, body: 'Use the vendor file. fake:project-files', kind: 'task' },
  });
  expect(issue.status()).toBe(201);
  const run = await bob.page.request.post(`${BASE()}/api/projects/demo-app/runs`, { headers: mutate, data: { issueNumber: (await issue.json()).number } });
  expect(run.status()).toBe(201);
  const runId = (await run.json()).id as string;
  await bob.page.goto(`${BASE()}/p/demo-app/runs/${runId}`);
  await expect(runPill(bob.page)).toHaveText('merged', { timeout: 60_000 });

  const line = bob.page.locator('.run-pf');
  await expect(line).toContainText('Project files (1)');
  await expect(line).toContainText(NAME);
  await expect(line).toContainText('v1');
  await expect(line).toContainText(SHA.slice(0, 12));
  await expect(line.getByRole('link', { name: 'Files of demo-app' })).toHaveAttribute('href', '/p/demo-app/files');

  await node(bob.page, 'builder').click();
  const log = bob.page.locator('.stage-detail .chat-log');
  // The tool's answer (after "Project files tool: ", possibly over several lines) names the file.
  await expect(log).toContainText(new RegExp(`Project files tool: [\\s\\S]*${NAME.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`));
  await expect(log).toContainText(`PROJECT FILE ok ${NAME} ${CONTENT.length} sha256:${SHA}`);
  await expect(log).toContainText('PROJECT FILES writable: no');
  await bob.context.close();
});

test('a private project’s files stay invisible to someone who cannot see it', async ({ browser }) => {
  const alice = await userContext(browser, 'alice');
  const id = await uploadViaApi(alice.page.request, 'private-lab', PRIVATE_NAME, PRIVATE_CONTENT);
  const on = await alice.page.request.put(`${BASE()}/api/projects/private-lab/files/${id}`, { headers: mutate, data: { downloadable: true } });
  expect(on.status()).toBe(200);
  expect((await on.json()).downloadable).toBe(true);
  // Alice herself can download it.
  const own = await alice.page.request.get(`${BASE()}/api/projects/private-lab/files/${id}/content`);
  expect(own.status()).toBe(200);
  expect(Buffer.from(await own.body()).equals(PRIVATE_CONTENT)).toBe(true);
  await alice.context.close();

  const bob = await userContext(browser, 'bob');
  expect((await bob.page.request.get(`${BASE()}/api/projects/private-lab/files`)).status()).toBe(404);
  expect((await bob.page.request.get(`${BASE()}/api/projects/private-lab/files/${id}/content`)).status()).toBe(404);
  await bob.page.goto(`${BASE()}/p/private-lab/files`);
  await expect(bob.page.getByText('No project called “private-lab”')).toBeVisible();
  await expect(bob.page.getByText(PRIVATE_NAME)).toHaveCount(0);
  await bob.context.close();
});

test('downloads work once an admin switches them on', async ({ browser }) => {
  const alice = await userContext(browser, 'alice');
  await alice.page.goto(`${BASE()}/p/demo-app/files`);
  const r = row(alice.page);
  await r.getByRole('checkbox', { name: `Allow download of ${NAME}` }).check();
  await expect(alice.page.locator('.pf-live')).toHaveText(`Download of ${NAME} allowed`);
  const link = alice.page.getByRole('link', { name: `Download ${NAME}` });
  await expect(link).toBeVisible();
  const id = await fileId(alice.page.request, 'demo-app', NAME);
  await expect(link).toHaveAttribute('href', `/api/projects/demo-app/files/${id}/content`);
  await alice.context.close();

  const bob = await userContext(browser, 'bob');
  const res = await bob.page.request.get(`${BASE()}/api/projects/demo-app/files/${id}/content`);
  expect(res.status()).toBe(200);
  expect(res.headers()['content-disposition']).toMatch(/^attachment/);
  expect(Buffer.from(await res.body()).equals(CONTENT)).toBe(true);
  await bob.page.goto(`${BASE()}/p/demo-app/files`);
  await expect(bob.page.getByRole('link', { name: `Download ${NAME}` })).toBeVisible();
  await bob.context.close();
});

test('an admin deletes the file through the confirmation dialog', async ({ browser }) => {
  const { context, page } = await userContext(browser, 'alice');
  await page.goto(`${BASE()}/p/demo-app/files`);
  await row(page).getByRole('button', { name: `Delete ${NAME}` }).click();
  const dialog = page.getByRole('dialog', { name: `Delete ${NAME}?` });
  await expect(dialog).toContainText('Runs that already started keep their copy; new runs and chats no longer get it.');
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await dialog.locator('.btn--danger').click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('.pf-live')).toHaveText(`Deleted ${NAME}`);
  await expect(page.getByText('No files yet.')).toBeVisible();
  await expect(row(page)).toHaveCount(0);
  await page.goto(`${BASE()}/p/demo-app`);
  await expect(page.locator('.panel--activity .pf-row')).toContainText('No files');
  await context.close();
});
