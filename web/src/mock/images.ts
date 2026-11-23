// Generated images in the mock (docs/ARCHITECTURE.md §6 "Generated images"): the provider catalogue, the per-user
// setting, images from chat messages that ask for an icon, selection per issue and one seeded run image.
import type { AgentSession, ClientConfig, CodexLogin, GeneratedImage, ImagesPayload, Run, User } from '../api/types';
import { db, emitEvent, MockError, uid } from './engine';

/** A 24×24 orange disc on a transparent background: the bytes of every mock image. */
export const IMAGE_PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAAR0lEQVR42mNgGAXUAAfCFP9jwzQzmCoWEWs4WZaQajhJlpBrONGW0NQCSg0naMmoBSPAgqGfD+hSVNClsKNLcU2XCmcUgAAA49s2H6InXo4AAAAASUVORK5CYII=';

export const IMAGE_CONFIG: ClientConfig['images'] = {
  providers: [
    {
      id: 'openai',
      label: 'OpenAI',
      models: ['gpt-image-2.5-flare', 'gpt-image-2.5-sunburst', 'gpt-image-2', 'gpt-image-1.5', 'gpt-image-1-mini'],
      defaultModel: 'gpt-image-2.5-flare',
      supportsTransparency: true,
      keyPlaceholder: 'sk-…',
      auth: 'api_key',
      available: true,
      unavailableReason: null,
    },
    {
      id: 'gemini',
      label: 'Google Gemini',
      models: ['gemini-3.1-flash-image', 'gemini-3-pro-image', 'gemini-2.5-flash-image'],
      defaultModel: 'gemini-3.1-flash-image',
      supportsTransparency: false,
      keyPlaceholder: 'AIza…',
      auth: 'api_key',
      available: true,
      unavailableReason: null,
    },
    {
      id: 'codex',
      label: 'OpenAI Codex',
      models: ['default'],
      defaultModel: 'default',
      supportsTransparency: true,
      keyPlaceholder: '',
      auth: 'device_login',
      available: true,
      unavailableReason: null,
    },
  ],
  limits: { maxPerRequest: 4, maxPerTurn: 8, maxPerStage: 12, maxEdge: 2048 },
};

const images = new Map<string, GeneratedImage>();
const MODEL_RE = /^[A-Za-z0-9._:-]{1,100}$/;
const label = (id: string) => IMAGE_CONFIG.providers.find((p) => p.id === id)?.label ?? id;

/** Like the backend: the selection is locked while the run that took it is not failed or cancelled. */
function view(img: GeneratedImage): GeneratedImage {
  const sel = img.selection;
  if (!sel) return structuredClone(img);
  const run = sel.usedByRunId ? db.runs.get(sel.usedByRunId) : undefined;
  const locked = !!run && run.status !== 'failed' && run.status !== 'cancelled';
  return structuredClone({ ...img, selection: { ...sel, locked } });
}

const byAge = (a: GeneratedImage, b: GeneratedImage) => a.createdAt.localeCompare(b.createdAt);

export function setImageProvider(u: User, provider: string, model: string, apiKey: string) {
  const p = IMAGE_CONFIG.providers.find((x) => x.id === provider);
  if (!p) throw new MockError(400, 'bad_request', `Unknown image provider '${provider}'`);
  if (p.auth === 'device_login') throw new MockError(409, 'codex_login_required', `${p.label} is connected by signing in with ChatGPT, not with an API key.`);
  const m = model.trim();
  if (!MODEL_RE.test(m)) throw new MockError(400, 'bad_request', "The model name may only contain letters, digits, '.', '_', ':' and '-' (at most 100)");
  const key = apiKey.trim();
  if (!key || key.length > 4096 || /\s/.test(key)) throw new MockError(422, 'image_key_invalid', 'The API key looks malformed');
  if (key.includes('bad')) throw new MockError(422, 'image_key_invalid', `${p.label} rejected the API key. Check that it is complete and active.`);
  Object.assign(u, { imageProvider: p.id, imageModel: m });
  codexLogin = { status: 'none' };
  return u;
}

export function removeImageProvider(u: User) {
  codexLogin = { status: 'none' };
  return Object.assign(u, { imageProvider: null, imageModel: null });
}

// The Codex device login: pending after a start; the third status poll connects it.
let codexLogin: CodexLogin = { status: 'none' };
let codexPolls = 0;

export function startCodexLogin(): CodexLogin {
  codexPolls = 0;
  codexLogin = {
    status: 'pending',
    verificationUrl: 'https://auth.openai.com/codex/device',
    userCode: 'MOCK-12345',
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
  };
  return { ...codexLogin };
}

export function pollCodexLogin(u: User): CodexLogin {
  if (codexLogin.status === 'pending' && ++codexPolls >= 3) {
    codexLogin = { status: 'connected' };
    Object.assign(u, { imageProvider: 'codex', imageModel: 'default' });
  }
  return { ...codexLogin };
}

export function cancelCodexLogin(): CodexLogin {
  if (codexLogin.status === 'pending') codexLogin = { status: 'none' };
  return { ...codexLogin };
}

function newImage(owner: User, repo: string, sessionId: string, origin: 'chat' | 'run', prompt: string, purpose: string, provider: string, model: string): GeneratedImage {
  return {
    id: uid(),
    repo,
    origin,
    sessionId,
    owner: { username: owner.username, displayName: owner.displayName },
    provider,
    model,
    prompt,
    purpose,
    mediaType: 'image/png',
    size: 128,
    width: 24,
    height: 24,
    transparent: true,
    createdAt: new Date().toISOString(),
    selection: null,
  };
}

/** A chat message asking for an icon: like the agent calling factory_generate_image with count 2. */
export function chatImages(s: AgentSession, text: string, by: User) {
  if (!/icon/i.test(text) || !by.imageProvider || !by.imageModel) return;
  const issue = /#(\d+)/.exec(text);
  const prompt = 'A friendly squirrel app icon, flat style, warm orange on a transparent background';
  const made = [1, 2].map(() => newImage(by, s.repo, s.id, 'chat', prompt, 'app icon', by.imageProvider!, by.imageModel!));
  made.forEach((m) => images.set(m.id, m));
  const payload: ImagesPayload = {
    requestId: uid(),
    provider: by.imageProvider,
    providerLabel: label(by.imageProvider),
    model: by.imageModel,
    prompt,
    purpose: 'app icon',
    ...(issue ? { issueNumber: Number(issue[1]) } : {}),
    images: made.map((m) => ({ id: m.id, mediaType: m.mediaType, width: m.width, height: m.height, size: m.size })),
  };
  setTimeout(() => emitEvent(s.id, 'images', payload, new Date().toISOString()), 700);
}

export const sessionImages = (sessionId: string) => [...images.values()].filter((i) => i.sessionId === sessionId).sort(byAge).map(view);
export const issueImages = (repo: string, n: number) =>
  [...images.values()].filter((i) => i.repo === repo && i.selection?.issueNumber === n).sort(byAge).map(view);
export const imageCount = (repo: string, n: number) => issueImages(repo, n).length;

function ownChatImage(id: string, by: User): GeneratedImage {
  const img = images.get(id);
  if (!img) throw new MockError(404, 'not_found', 'Image not found');
  if (img.owner.username !== by.username) throw new MockError(403, 'forbidden', `Only ${img.owner.username}, who generated this image, can change it`);
  if (img.origin !== 'chat') throw new MockError(409, 'run_image', 'Images generated in a run cannot be selected or discarded');
  return img;
}

const inUse = () => new MockError(409, 'image_in_use', 'A run is using this image; it can no longer be changed');

export function selectImage(id: string, issueNumber: number, rawName: string, by: User) {
  const img = ownChatImage(id, by);
  const name = rawName.replace(/\s+/g, ' ').trim();
  if (!name || name.length > 60) throw new MockError(400, 'bad_request', 'name is required: 1 to 60 characters');
  const issue = db.issues[img.repo]?.find((i) => i.number === issueNumber);
  if (!issue) throw new MockError(404, 'not_found', `Issue #${issueNumber} not found`);
  if (issue.state !== 'open') throw new MockError(409, 'issue_closed', `Issue #${issueNumber} is closed`);
  if (view(img).selection?.locked) throw inUse();
  if (issueImages(img.repo, issueNumber).filter((i) => i.id !== id).length >= 20) {
    throw new MockError(409, 'too_many_images', `Issue #${issueNumber} already has 20 selected images`);
  }
  img.selection = { issueNumber, name, usedByRunId: null, locked: false };
  return view(img);
}

export function unselectImage(id: string, by: User) {
  const img = ownChatImage(id, by);
  if (view(img).selection?.locked) throw inUse();
  img.selection = null;
  return view(img);
}

export function discardImage(id: string, by: User) {
  const img = ownChatImage(id, by);
  if (img.selection) throw new MockError(409, 'image_selected', `The image is selected for #${img.selection.issueNumber}; remove it from the issue first`);
  images.delete(id);
}

/** A run starting: it takes the issue's selected images that no other live run holds. */
export function claimImages(r: Run) {
  for (const img of images.values()) {
    if (img.repo === r.repo && img.selection?.issueNumber === r.issueNumber && !view(img).selection?.locked) img.selection.usedByRunId = r.id;
  }
}

/** Closing a chat: its unselected images go. */
export function closeChatImages(sessionId: string) {
  for (const [id, img] of images) if (img.sessionId === sessionId && !img.selection) images.delete(id);
}

/** One merged run whose builder generated an image, so the run page shows the section. */
export function seedRunImage() {
  const run = [...db.runs.values()].find((r) => r.status === 'merged' && r.stages.some((s) => s.role === 'builder' && s.sessionId));
  const stage = run?.stages.find((s) => s.role === 'builder' && s.sessionId);
  const owner = run && db.users.find((u) => u.username === run.owner.username);
  if (!run || !stage || !owner) return;
  const img = newImage(owner, run.repo, stage.sessionId!, 'run', 'Empty-state illustration: an empty basket with a squirrel peeking in, flat style', 'empty state of the cart', 'openai', 'gpt-image-2.5-flare');
  images.set(img.id, img);
  stage.images.push(view(img));
}
