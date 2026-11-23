import { ApiError, type Api, type Channel, type ChannelHandlers } from './api';
import type { FactoryEvent, FeedFrame } from './types';

/** Real backend client. Cookie auth; every mutating request carries the CSRF header. */

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-Factory-Request'] = '1';
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'network', 'The factory server could not be reached.');
  }
  if (res.status === 204 || res.status === 202) return undefined as T;
  const text = await res.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = undefined;
    }
  }
  if (!res.ok) {
    const err = (data as { error?: { code?: string; message?: string; lockedUntil?: string } } | undefined)?.error;
    throw new ApiError(res.status, err?.code ?? `http_${res.status}`, err?.message ?? res.statusText ?? 'Request failed', err?.lockedUntil ?? null);
  }
  return data as T;
}

const enc = encodeURIComponent;

/** Raw-body upload with progress (fetch cannot report upload progress). */
function upload<T>(path: string, file: File, opts?: { onProgress?: (loaded: number, total: number) => void; signal?: AbortSignal }): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', path);
    xhr.withCredentials = true;
    xhr.setRequestHeader('X-Factory-Request', '1');
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (e) => opts?.onProgress?.(e.loaded, e.lengthComputable ? e.total : file.size);
    xhr.onload = () => {
      let data: unknown;
      try {
        data = xhr.responseText ? JSON.parse(xhr.responseText) : undefined;
      } catch {
        data = undefined;
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data as T);
      else {
        const err = (data as { error?: { code?: string; message?: string } } | undefined)?.error;
        reject(new ApiError(xhr.status, err?.code ?? `http_${xhr.status}`, err?.message ?? (xhr.status === 413 ? 'The file is too large.' : 'Upload failed')));
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'network', 'The upload did not reach the factory server.'));
    xhr.onabort = () => reject(new ApiError(0, 'aborted', 'Upload cancelled'));
    opts?.signal?.addEventListener('abort', () => xhr.abort());
    xhr.send(file);
  });
}
const get = <T>(p: string) => request<T>('GET', p);
const post = <T>(p: string, b?: unknown) => request<T>('POST', p, b ?? {});
const put = <T>(p: string, b: unknown) => request<T>('PUT', p, b);
const del = <T>(p: string) => request<T>('DELETE', p);

function wsUrl(path: string): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}${path}`;
}

function openSocket<T>(path: string, handlers: ChannelHandlers<T>): Channel {
  let closedByUs = false;
  const ws = new WebSocket(wsUrl(path));
  ws.onopen = () => handlers.onOpen?.();
  ws.onmessage = (m) => {
    try {
      handlers.onFrame(JSON.parse(typeof m.data === 'string' ? m.data : '') as T);
    } catch {
      // ignore malformed frames
    }
  };
  ws.onclose = (e) => {
    if (!closedByUs) handlers.onClose?.({ code: e.code, reason: e.reason });
  };
  return {
    close() {
      closedByUs = true;
      ws.close();
    },
  };
}

export const httpApi: Api = {
  health: () => get('/api/health'),
  providers: () => get('/api/auth/providers'),
  login: (username, password) => post('/api/auth/login', { username, password }),
  totp: (code) => post('/api/auth/totp', { code }),
  totpEnroll: (code) => post('/api/auth/totp/enroll', { code }),
  regenerateRecoveryCodes: (code) => post('/api/me/recovery-codes', { code }),
  resetTwoFactor: (id) => post(`/api/admin/users/${enc(id)}/reset-2fa`),
  logout: () => post('/api/auth/logout'),
  me: () => get('/api/me'),
  putClaudeCredential: (kind, value) => put('/api/me/claude-credential', { kind, value }),
  deleteClaudeCredential: () => del('/api/me/claude-credential'),
  putGithubToken: (token) => put('/api/me/github-token', { token }),
  deleteGithubToken: () => del('/api/me/github-token'),
  putImageProvider: (provider, model, apiKey) => put('/api/me/image-provider', { provider, model, apiKey }),
  deleteImageProvider: () => del('/api/me/image-provider'),
  startCodexLogin: () => post('/api/me/image-provider/codex/login'),
  codexLogin: () => get('/api/me/image-provider/codex/login'),
  cancelCodexLogin: () => del('/api/me/image-provider/codex/login'),
  preferences: () => get('/api/me/preferences'),
  putPreferences: (p) => put('/api/me/preferences', p),
  usage: () => get('/api/me/usage'),

  projects: () => get('/api/projects'),
  project: (repo) => get(`/api/projects/${enc(repo)}`),
  issues: (repo, state = 'open') => get(`/api/projects/${enc(repo)}/issues?state=${state}`),
  issue: (repo, n) => get(`/api/projects/${enc(repo)}/issues/${n}`),
  createIssue: (repo, body) => post(`/api/projects/${enc(repo)}/issues`, body),

  releases: (repo) => get(`/api/projects/${enc(repo)}/releases`),
  release: (id) => get(`/api/releases/${enc(id)}`),
  createRelease: (repo, body) => post(`/api/projects/${enc(repo)}/releases`, body),
  updateRelease: (id, body) => put(`/api/releases/${enc(id)}`, body),
  deleteRelease: (id) => del(`/api/releases/${enc(id)}`),
  assignIssues: (repo, issueNumbers, releaseId) => post(`/api/projects/${enc(repo)}/releases/assign`, { issueNumbers, releaseId }),
  reorderRelease: (id, issueNumbers) => put(`/api/releases/${enc(id)}/issues/order`, { issueNumbers }),
  startRelease: (id) => post(`/api/releases/${enc(id)}/start`),
  pauseRelease: (id) => post(`/api/releases/${enc(id)}/pause`),
  resumeRelease: (id) => post(`/api/releases/${enc(id)}/resume`),
  cancelRelease: (id) => post(`/api/releases/${enc(id)}/cancel`),
  retryReleaseIssue: (id, issueNumber) => post(`/api/releases/${enc(id)}/issues/${issueNumber}/retry`),
  approveRelease: (id) => post(`/api/releases/${enc(id)}/approve`),
  checkRelease: (id) => post(`/api/releases/${enc(id)}/check`),
  setReleaseMode: (id, mode) => put(`/api/releases/${enc(id)}/mode`, { mode }),
  scheduleRelease: (id, startAt) => put(`/api/releases/${enc(id)}/schedule`, { startAt }),
  cancelReleaseSchedule: (id) => del(`/api/releases/${enc(id)}/schedule`),

  sandboxStatus: (repo) => get(`/api/projects/${enc(repo)}/sandbox`),
  approveSandbox: (repo, sha256) => post(`/api/projects/${enc(repo)}/sandbox/approval`, { sha256 }),
  revokeSandboxApproval: (repo) => del(`/api/projects/${enc(repo)}/sandbox/approval`),
  sandboxes: () => get('/api/admin/sandboxes'),
  pendingSandboxes: () => get('/api/admin/sandboxes/pending'),
  stopSandbox: (runId) => del(`/api/admin/sandboxes/${enc(runId)}`),

  projectRuns: (repo) => get(`/api/projects/${enc(repo)}/runs`),
  startRun: (repo, issueNumber) => post(`/api/projects/${enc(repo)}/runs`, { issueNumber }),
  runs: (filter = {}) => {
    const q = new URLSearchParams();
    if (filter.active) q.set('active', 'true');
    if (filter.status?.length) q.set('status', filter.status.join(','));
    const qs = q.toString();
    return get(`/api/runs${qs ? `?${qs}` : ''}`);
  },
  run: (id) => get(`/api/runs/${enc(id)}`),
  approveRun: (id) => post(`/api/runs/${enc(id)}/approve`),
  cancelRun: (id) => post(`/api/runs/${enc(id)}/cancel`),
  talkToRun: (id, text) => post(`/api/runs/${enc(id)}/conversation`, { text }),
  reviewRun: (id) => post(`/api/runs/${enc(id)}/review`),

  projectSessions: (repo, kind) => get(`/api/projects/${enc(repo)}/sessions${kind ? `?kind=${kind}` : ''}`),
  createChat: (repo, model) => post(`/api/projects/${enc(repo)}/sessions`, model ? { model } : {}),
  agents: (activeOnly = false) => get(`/api/agents${activeOnly ? '?active=true' : ''}`),
  session: (id) => get(`/api/sessions/${enc(id)}`),
  events: (id, after = 0, limit) =>
    get(`/api/sessions/${enc(id)}/events?after=${after}${limit ? `&limit=${limit}` : ''}`),
  sendInput: async (id, text, attachmentIds) => {
    await post(`/api/sessions/${enc(id)}/input`, attachmentIds?.length ? { text, attachmentIds } : { text });
  },
  sendToAgent: (id, text) => post(`/api/sessions/${enc(id)}/input`, { text }),
  uploadAttachment: (sessionId, file, opts) => upload(`/api/sessions/${enc(sessionId)}/attachments?name=${enc(file.name)}`, file, opts),
  deleteAttachment: (sessionId, id) => del(`/api/sessions/${enc(sessionId)}/attachments/${enc(id)}`),
  sessionImages: (id) => get(`/api/sessions/${enc(id)}/images`),
  issueImages: (repo, n) => get(`/api/projects/${enc(repo)}/issues/${n}/images`),
  selectImage: (id, issueNumber, name) => put(`/api/images/${enc(id)}/selection`, { issueNumber, name }),
  unselectImage: (id) => del(`/api/images/${enc(id)}/selection`),
  discardImage: (id) => del(`/api/images/${enc(id)}`),
  imageUrl: (id) => `/api/images/${enc(id)}`,
  screenshotUrl: (runId, id) => `/api/runs/${enc(runId)}/screenshots/${enc(id)}`,
  attachmentUrl: (sessionId, id, opts) => `/api/sessions/${enc(sessionId)}/attachments/${enc(id)}${opts?.preview ? '?preview=1' : ''}`,
  config: () => get('/api/config'),
  interrupt: (id) => post(`/api/sessions/${enc(id)}/interrupt`),
  stopSession: (id) => post(`/api/sessions/${enc(id)}/stop`),
  closeChat: (id) => del(`/api/sessions/${enc(id)}`),

  settings: () => get('/api/admin/settings'),
  putSettings: (s) => put('/api/admin/settings', s),
  users: () => get('/api/admin/users'),
  softwareRequests: () => get('/api/admin/software-requests'),
  resolveSoftwareRequest: (id, status, note) =>
    post(`/api/admin/software-requests/${enc(id)}`, note ? { status, note } : { status }),

  openSessionSocket: (id, after, handlers) =>
    openSocket<FactoryEvent>(`/api/ws/sessions/${enc(id)}?after=${after}`, handlers),
  openFeed: (handlers) => openSocket<FeedFrame>('/api/ws/feed', handlers),
};
