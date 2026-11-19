// Types mirror docs/ARCHITECTURE.md §8 exactly. Change the contract first, then this file.

export type CredentialKind = 'oauth_token' | 'api_key';

export type User = {
  id: string;
  username: string;
  displayName: string;
  isAdmin: boolean;
  githubLogin: string | null;
  hasGithubToken: boolean;
  hasClaudeCredential: boolean;
  claudeCredentialKind: CredentialKind | null;
  twoFactorEnabled: boolean;
  /** ISO timestamp while the second factor is locked after too many wrong codes, else null. */
  twoFactorLockedUntil: string | null;
  /** `classic` (ghp_…) or `fine_grained` (github_pat_…); null without a token or when unknown. */
  githubTokenKind: 'classic' | 'fine_grained' | null;
  /** Classic tokens: the OAuth scopes GitHub reports for it; null without a token or for fine-grained tokens. */
  githubTokenScopes: string[] | null;
};

/** Login is multi-step: password → (totp | enroll) → done. See §4 "Two-factor authentication". */
export type LoginStep =
  | { next: 'totp' }
  | { next: 'enroll'; secret: string; otpauthUri: string }
  | { next: 'done'; user: User; recoveryCodes?: string[] };

export type Project = {
  name: string;
  fullName: string;
  description: string | null;
  private: boolean;
  defaultBranch: string;
  language: string | null;
  openIssues: number;
  pushedAt: string | null;
  htmlUrl: string;
  activeAgents: number;
  activeRuns: number;
};

export type IssueKind = 'feature' | 'bug' | 'task';

export type Issue = {
  number: number;
  title: string;
  state: 'open' | 'closed';
  kind: IssueKind;
  labels: { name: string; color: string }[];
  author: string;
  assignees: string[];
  comments: number;
  createdAt: string;
  updatedAt: string;
  htmlUrl: string;
  body?: string;
  activeRunId: string | null;
};

export type SessionKind = 'chat' | 'spec-writer' | 'builder' | 'security' | 'ux' | 'ui-design' | 'code' | 'approver';
export type StageRole = Exclude<SessionKind, 'chat'>;

/** Roles whose running agent accepts messages from the run's owner (docs/ARCHITECTURE.md §6). */
export const STEERABLE_ROLES: StageRole[] = ['spec-writer', 'builder'];

/** Answer to a message for a running pipeline agent. Always `queued`; a `system` event with `messageId` confirms pickup. */
export type AgentMessage = {
  status: 'queued';
  messageId: string;
  sessionId: string;
  runId: string | null;
  role: StageRole;
  note: string;
};
export type SessionStatus = 'starting' | 'running' | 'idle' | 'done' | 'failed' | 'stopped';

export type Owner = { username: string; displayName: string };

export type AgentSession = {
  id: string;
  repo: string;
  kind: SessionKind;
  model: string;
  status: SessionStatus;
  owner: Owner;
  title: string | null;
  runId: string | null;
  parentSessionId: string | null;
  issueNumber: number | null;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  costUsd: number | null;
  numTurns: number | null;
  lastActivity: string | null;
  /** True while an agent process exists. A chat is `idle` both with a process (waiting for input) and without. */
  live: boolean;
};

export type StageStatus = 'pending' | 'running' | 'done' | 'failed' | 'skipped';
export type Verdict = 'PASS' | 'CHANGES' | 'APPROVE' | 'ESCALATE';
export type Finding = { severity: string; file: string | null; detail: string };

export type Stage = {
  id: string;
  role: StageRole;
  /** 0 = the initial build; fix rounds are 1..Run.maxRounds. */
  round: number;
  /** The model this stage runs (or ran) with. */
  model: string;
  status: StageStatus;
  verdict: Verdict | null;
  summary: string | null;
  findings: Finding[];
  sessionId: string | null;
  startedAt: string | null;
  endedAt: string | null;
};

export type RunStatus =
  | 'queued'
  | 'spec'
  | 'building'
  | 'reviewing'
  | 'approving'
  | 'merged'
  | 'needs_human'
  | 'failed'
  | 'cancelled';

export type Run = {
  id: string;
  repo: string;
  issueNumber: number;
  issueTitle: string;
  issueKind: IssueKind;
  status: RunStatus;
  /** Fix rounds so far: 0 while in the initial build/review, 1..maxRounds after reviewers asked for changes. */
  round: number;
  maxRounds: number;
  branch: string | null;
  prNumber: number | null;
  prUrl: string | null;
  owner: Owner;
  startedBySessionId: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  stages: Stage[];
};

export type EventType = 'user' | 'claude' | 'system' | 'status';

export type FactoryEvent = {
  seq: number;
  sessionId: string;
  ts: string;
  type: EventType;
  payload: unknown;
};

/** A file or image attached to a chat message (docs/ARCHITECTURE.md §8 "Attachments"). */
export type Attachment = {
  id: string;
  name: string;
  /** Decided by the server from the bytes, not from the browser's guess. */
  mediaType: string;
  size: number;
  /** `image`: png/jpeg/gif/webp, shown inline. `file`: anything else, download only. */
  kind: 'image' | 'file';
  width: number | null;
  height: number | null;
  /** How the model sees it: the original image, a downscaled copy, or not inline at all (file only). */
  inline: 'original' | 'downscaled' | 'none';
  /** Why an image was downscaled or is file only. */
  note: string | null;
};

export type ClientConfig = {
  attachments: { maxFileBytes: number; maxMessageBytes: number; maxCount: number; imageTypes: string[] };
};

export type UserPayload = { text: string; username: string; attachments?: Attachment[] };
export type SystemPayload = { level: 'info' | 'warn' | 'error'; text: string };
export type StatusPayload = { status: SessionStatus };

export type Settings = {
  agentsMayInstallSoftware: boolean;
  chatModel: string;
  pipelineModels: Record<StageRole, string>;
  maxFixRounds: number;
};

/** Where an effective value comes from: the user's own choice, or the admin default. */
export type PreferenceSource = 'user' | 'default';

/** A user's own choices; null / missing = use the admin default (docs/ARCHITECTURE.md §6 "Whose models"). */
export type PreferenceOverrides = {
  chatModel: string | null;
  pipelineModels: Partial<Record<StageRole, string>>;
  maxFixRounds: number | null;
};

export type Preferences = {
  overrides: PreferenceOverrides;
  effective: {
    chatModel: { value: string; source: PreferenceSource };
    pipelineModels: Record<StageRole, { value: string; source: PreferenceSource }>;
    /** `max`: the admin default, also the highest allowed. `clamped`: the user's choice is above it and capped. */
    maxFixRounds: { value: number; source: PreferenceSource; max: number; clamped: boolean };
  };
  /** Model aliases to offer; any full model id is accepted too. */
  suggestions: string[];
};

export type SoftwareRequest = {
  id: string;
  name: string;
  reason: string;
  repo: string;
  requestedBy: string;
  sessionId: string | null;
  status: 'pending' | 'installed' | 'denied';
  note: string | null;
  createdAt: string;
  resolvedAt: string | null;
};

export type FeedFrame =
  | { type: 'session'; session: AgentSession }
  | { type: 'run'; run: Run }
  /** An issue created through Agern, in list shape (no body). Event only: not replayed, not sent for changes made on GitHub. */
  | { type: 'issue'; repo: string; issue: Issue }
  /** A chat was closed (deleted) by its owner or an admin. */
  | { type: 'session_closed'; repo: string; sessionId: string };

/** Agents are working on these runs. */
export const WORKING_RUN_STATUSES: RunStatus[] = ['queued', 'spec', 'building', 'reviewing', 'approving'];
/** Not finished: what `GET /api/runs?active=true` returns (working + needs_human). */
export const ACTIVE_RUN_STATUSES: RunStatus[] = [...WORKING_RUN_STATUSES, 'needs_human'];

export const isRunWorking = (r: Run) => WORKING_RUN_STATUSES.includes(r.status);
export const isRunActive = (r: Run) => ACTIVE_RUN_STATUSES.includes(r.status);
/** Same rule as `GET /api/agents?active=true`: a live process, or starting/running. */
export const isSessionActive = (s: AgentSession) => s.live || s.status === 'starting' || s.status === 'running';
/** Mid-turn (as opposed to `idle`: waiting for input). */
export const isSessionBusy = (s: { status: SessionStatus }) => s.status === 'starting' || s.status === 'running';
