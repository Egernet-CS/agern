import type {
  AgentSession,
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
  sendInput(id: string, text: string): Promise<void>;
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
