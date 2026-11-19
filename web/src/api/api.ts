import type {
  AgentMessage,
  AgentSession,
  Attachment,
  ClientConfig,
  PreferenceOverrides,
  Preferences,
  CredentialKind,
  FactoryEvent,
  FeedFrame,
  Issue,
  LoginStep,
  IssueKind,
  Project,
  Run,
  RunStatus,
  Settings,
  SoftwareRequest,
  User,
} from './types';

/** One open realtime connection. Reconnection is the caller's job (see lib/realtime.ts). */
export type Channel = { close(): void };

export type ChannelHandlers<T> = {
  onOpen?: () => void;
  onFrame: (frame: T) => void;
  /** Called once when the connection ends for any reason other than close(). */
  onClose?: (info: { code: number; reason: string }) => void;
};

/** The whole HTTP + WebSocket surface of docs/ARCHITECTURE.md §8. */
export interface Api {
  health(): Promise<{ status: 'ok'; version: string; name: string }>;
  providers(): Promise<{ providers: string[]; org: string; twoFactor: boolean }>;
  login(username: string, password: string): Promise<LoginStep>;
  totp(code: string): Promise<LoginStep>;
  totpEnroll(code: string): Promise<LoginStep>;
  regenerateRecoveryCodes(code: string): Promise<{ recoveryCodes: string[] }>;
  resetTwoFactor(userId: string): Promise<User>;
  logout(): Promise<void>;
  me(): Promise<User>;
  putClaudeCredential(kind: CredentialKind, value: string): Promise<User>;
  deleteClaudeCredential(): Promise<User>;
  putGithubToken(token: string): Promise<User>;
  preferences(): Promise<Preferences>;
  /** Replaces the user's own choices (validated like the admin settings; maxFixRounds 0..admin value, else 422). */
  putPreferences(p: PreferenceOverrides): Promise<Preferences>;
  deleteGithubToken(): Promise<User>;

  projects(): Promise<Project[]>;
  project(repo: string): Promise<Project>;
  issues(repo: string, state?: 'open' | 'closed' | 'all'): Promise<Issue[]>;
  issue(repo: string, number: number): Promise<Issue>;
  createIssue(repo: string, body: { title: string; body: string; kind: IssueKind }): Promise<Issue>;

  projectRuns(repo: string): Promise<Run[]>;
  startRun(repo: string, issueNumber: number): Promise<Run>;
  /** `active`: not finished (incl. needs_human). `status`: only these statuses. Both given: both apply. */
  runs(filter?: { active?: boolean; status?: RunStatus[] }): Promise<Run[]>;
  run(id: string): Promise<Run>;
  approveRun(id: string): Promise<Run>;
  cancelRun(id: string): Promise<Run>;

  projectSessions(repo: string, kind?: 'chat'): Promise<AgentSession[]>;
  createChat(repo: string, model?: string): Promise<AgentSession>;
  agents(activeOnly?: boolean): Promise<AgentSession[]>;
  session(id: string): Promise<AgentSession>;
  events(id: string, after?: number, limit?: number): Promise<FactoryEvent[]>;
  /** `attachmentIds`: unsent uploads of this session, in order. */
  sendInput(id: string, text: string, attachmentIds?: string[]): Promise<void>;
  /**
   * Owner only: a text message for a running spec-writer or builder (same endpoint as chat input). Rejects with ApiError
   * 409 `agent_not_running` / `agent_does_not_accept_messages`, 403 for anyone else.
   */
  sendToAgent(sessionId: string, text: string): Promise<AgentMessage>;
  /** Owner only. Rejects with ApiError (413 attachment_too_large / attachments_too_large, 409 too_many_attachments, …). */
  uploadAttachment(sessionId: string, file: File, opts?: { onProgress?: (loaded: number, total: number) => void; signal?: AbortSignal }): Promise<Attachment>;
  /** Removes an unsent upload. */
  deleteAttachment(sessionId: string, id: string): Promise<void>;
  /** URL of the bytes; `preview` serves the downscaled copy when there is one. */
  attachmentUrl(sessionId: string, id: string, opts?: { preview?: boolean }): string;
  /** Server limits for the client (attachments). */
  config(): Promise<ClientConfig>;
  interrupt(id: string): Promise<void>;
  stopSession(id: string): Promise<AgentSession>;

  settings(): Promise<Settings>;
  putSettings(s: Settings): Promise<Settings>;
  users(): Promise<User[]>;
  softwareRequests(): Promise<SoftwareRequest[]>;
  resolveSoftwareRequest(id: string, status: SoftwareRequest['status'], note?: string): Promise<SoftwareRequest>;

  openSessionSocket(id: string, after: number, handlers: ChannelHandlers<FactoryEvent>): Channel;
  openFeed(handlers: ChannelHandlers<FeedFrame>): Channel;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    /** For `locked` (423) and `too_many_attempts` (429): when the lock ends, ISO timestamp. */
    public lockedUntil: string | null = null,
  ) {
    super(message);
  }
}
