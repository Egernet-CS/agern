import type {
  AgentMessage,
  AgentSession,
  Attachment,
  ClaudeUsage,
  ClientConfig,
  CreatedMcpToken,
  McpToken,
  CodexLogin,
  GeneratedImage,
  PreferenceOverrides,
  Preferences,
  CredentialKind,
  FactoryEvent,
  FeedFrame,
  GitHubStatus,
  Issue,
  LoginStep,
  IssueKind,
  Project,
  ProjectSettings,
  Release,
  ReleaseMode,
  Run,
  RetryMode,
  RetryOptions,
  RunStatus,
  DeployCheck,
  DeployStatus,
  PendingSandbox,
  SandboxOverview,
  SandboxStatus,
  Settings,
  SoftwareRequest,
  User,
  MemberLabelsResult,
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
  /** GitHub as Agern sees it (any logged-in user). `brief`: verdict and official status only (kinds [], rateLimits null). */
  githubStatus(opts?: { brief?: boolean }): Promise<GitHubStatus>;
  providers(): Promise<{ providers: string[]; org: string; twoFactor: boolean }>;
  login(username: string, password: string): Promise<LoginStep>;
  totp(code: string): Promise<LoginStep>;
  totpEnroll(code: string): Promise<LoginStep>;
  regenerateRecoveryCodes(code: string): Promise<{ recoveryCodes: string[] }>;
  resetTwoFactor(userId: string): Promise<User>;
  /** The caller's personal MCP tokens (not revoked), newest first. */
  mcpTokens(): Promise<McpToken[]>;
  /** 400 bad name or expiry, 404 project not visible, 409 `mcp_token_name_taken` / `too_many_mcp_tokens`. */
  createMcpToken(body: { name: string; repo: string; expiresInDays: number | null }): Promise<CreatedMcpToken>;
  /** Revokes one of the caller's tokens; answers the updated list. */
  revokeMcpToken(id: string): Promise<McpToken[]>;
  /** Admin: every user's personal MCP tokens. */
  adminMcpTokens(): Promise<McpToken[]>;
  /** Admin: revokes any token; answers the updated full list. */
  adminRevokeMcpToken(id: string): Promise<McpToken[]>;
  logout(): Promise<void>;
  me(): Promise<User>;
  putClaudeCredential(kind: CredentialKind, value: string): Promise<User>;
  deleteClaudeCredential(): Promise<User>;
  putGithubToken(token: string): Promise<User>;
  preferences(): Promise<Preferences>;
  /** Replaces the user's own choices (validated like the admin settings; maxFixRounds 0..admin value, else 422). */
  putPreferences(p: PreferenceOverrides): Promise<Preferences>;
  /** The caller's own Claude usage (limit windows for an OAuth token, spend through Agern); never anyone else's. */
  usage(): Promise<ClaudeUsage>;
  deleteGithubToken(): Promise<User>;
  /**
   * Validates the key and model with the provider, then stores them (replacing any earlier setting). 400 unknown
   * provider or bad model name, 422 `image_key_invalid` / `image_model_unknown`, 502 `image_provider_unavailable`.
   */
  putImageProvider(provider: string, model: string, apiKey: string): Promise<User>;
  deleteImageProvider(): Promise<User>;
  /** Starts a Codex device login (cancels a pending one). */
  startCodexLogin(): Promise<CodexLogin>;
  codexLogin(): Promise<CodexLogin>;
  cancelCodexLogin(): Promise<CodexLogin>;

  projects(): Promise<Project[]>;
  project(repo: string): Promise<Project>;
  /** Sets or replaces the project's icon (raw image bytes). Admin only; answers the updated project. */
  setProjectIcon(repo: string, file: File): Promise<Project>;
  /** Sets the project's icon from an image file on its default branch (copied once). Admin only; answers the updated project. */
  setProjectIconFromRepository(repo: string, path: string): Promise<Project>;
  /** Sets the project's merge policy and its reviewers. Admin only; answers the updated project. */
  setProjectSettings(repo: string, settings: ProjectSettings): Promise<Project>;
  /** Removes the project's icon (also when none is set). Admin only; answers the updated project. */
  deleteProjectIcon(repo: string): Promise<Project>;
  issues(repo: string, state?: 'open' | 'closed' | 'all'): Promise<Issue[]>;
  issue(repo: string, number: number): Promise<Issue>;
  createIssue(repo: string, body: { title: string; body: string; kind: IssueKind }): Promise<Issue>;

  /** Newest first, each with its issues in order. */
  releases(repo: string): Promise<Release[]>;
  release(id: string): Promise<Release>;
  /** 400 invalid name or description, 409 `release_name_taken`. */
  createRelease(repo: string, body: { name: string; description: string; mode?: ReleaseMode }): Promise<Release>;
  updateRelease(id: string, body: { name: string; description: string }): Promise<Release>;
  /** Removes the plan only; the issues are untouched. */
  deleteRelease(id: string): Promise<void>;
  /**
   * Puts open issues into a release (moving them from another), or takes them out with `releaseId` null. All-or-nothing:
   * 409 `issue_closed`, 404 for an unknown issue. Answers all releases of the project after the change.
   */
  assignIssues(repo: string, issueNumbers: number[], releaseId: string | null): Promise<Release[]>;
  /** The new complete order; 409 `release_issues_changed` when it is not exactly the release's current issues. */
  reorderRelease(id: string, issueNumbers: number[]): Promise<Release>;
  /**
   * Starts building a planned release with the caller's credentials; the caller becomes its owner. 409
   * `release_not_planning` / `release_empty` / `release_already_building` / `release_branch_exists`, 422
   * `claude_credential_required` / `github_push_denied`.
   */
  startRelease(id: string): Promise<Release>;
  /** Owner or admin; 409 `release_not_building`. The running issue finishes, no further one starts. */
  pauseRelease(id: string): Promise<Release>;
  /** Owner only; 409 `release_not_paused`, 422 `claude_credential_required`. */
  resumeRelease(id: string): Promise<Release>;
  /**
   * Owner or admin; 409 `release_not_waiting_for_network` unless paused with reason `network`. Holds the issue that could
   * not start and goes on with the next one.
   */
  giveUpReleaseNetwork(id: string): Promise<Release>;
  /** Owner or admin; 409 `release_not_building`. Stops the running issue; what was merged stays on the branch. */
  cancelRelease(id: string): Promise<Release>;
  /**
   * Owner only: a held or skipped issue is built again — `mode` omitted = start over (as before retry modes), `continue`
   * from its run's commits or the run's failed `step`, in its turn. 409 `release_not_building` / `release_issue_not_held` /
   * `retry_mode_unavailable`.
   */
  retryReleaseIssue(id: string, issueNumber: number, mode?: RetryMode): Promise<Release>;
  /** How a held or skipped issue can be retried (from its linked run). */
  releaseIssueRetryOptions(id: string, issueNumber: number): Promise<RetryOptions>;
  /**
   * Owner only (403 for everyone else, admins included): merges a `ready` release into the default branch, one commit per
   * issue, closes its issues and deletes its branch. 409 `release_not_ready` / `release_incomplete` / `release_empty` /
   * `release_conflict` / `release_migration_conflict` / `release_merge_failed` / `release_merge_method_unavailable`.
   */
  approveRelease(id: string): Promise<Release>;
  /** Owner only: "Check again" — re-reads (or opens) the release pull request and its mergeability. 409 `release_not_ready`. */
  checkRelease(id: string): Promise<Release>;
  /**
   * "Bring up to date" (owner or admin): starts a run that merges the default branch into `release/<name>`. 404 / 403;
   * 409 `release_not_ready`, `release_sync_unsupported`, `release_sync_merge_commits_disallowed`, `release_sync_running`,
   * `release_up_to_date`; 422 `claude_credential_required`.
   */
  syncRelease(id: string): Promise<Release>;
  /**
   * Manual or automatic release. Planned: anyone who can see the project; scheduled or started: automatic is the owner's
   * alone, manual also an admin's (403 otherwise). 409 `release_not_editable` (released) / `release_closed` (cancelled).
   */
  setReleaseMode(id: string, mode: ReleaseMode): Promise<Release>;
  /**
   * Sets (caller becomes the owner) or changes (owner or admin) the scheduled start; `startAt` is an ISO instant.
   * 400 past or more than a year ahead, 422 `claude_credential_required`, 403 not owner/admin, 409 `release_not_schedulable`.
   */
  scheduleRelease(id: string, startAt: string): Promise<Release>;
  /** Back to `planning`; 409 `release_not_scheduled`, 403 not owner/admin. */
  cancelReleaseSchedule(id: string): Promise<Release>;

  /** The project's `.agern/sandbox.yml` on its default branch and its approval (anyone who can see the project). */
  sandboxStatus(repo: string): Promise<SandboxStatus>;
  /**
   * Admin: approves exactly the content with this hash. 409 `sandbox_definition_changed` when the file changed meanwhile,
   * 422 `sandbox_definition_invalid`, 404 when there is no file, 403 for non-admins.
   */
  approveSandbox(repo: string, sha256: string): Promise<SandboxStatus>;
  /** Admin: removes the project's approval (runs already started keep their decision). */
  revokeSandboxApproval(repo: string): Promise<SandboxStatus>;
  /** Admin: the sandbox runtime, its limits and the sandboxes that exist right now. */
  sandboxes(): Promise<SandboxOverview>;
  /** Admin: projects (that the admin can see) whose valid definition is not approved in this version. */
  pendingSandboxes(): Promise<PendingSandbox[]>;
  /**
   * Admin: removes a run's sandbox now (containers and network; cache volumes stay). The run continues and the builder's
   * next sandbox command creates a fresh one. 404 when the run has no sandbox right now. Answers the new overview.
   */
  stopSandbox(runId: string): Promise<SandboxOverview>;

  /** The project's `.agern/deploy.yml` on its default branch, its approval and the project's public deploy key. */
  deployStatus(repo: string): Promise<DeployStatus>;
  /**
   * Admin: approves exactly the content with this hash. 409 `deploy_definition_changed` when the file changed meanwhile,
   * 422 `deploy_definition_invalid`, 404 when there is no file, 403 for non-admins.
   */
  approveDeploy(repo: string, sha256: string): Promise<DeployStatus>;
  /** Admin: removes the project's deploy approval; no target is usable until a version is approved again. */
  revokeDeployApproval(repo: string): Promise<DeployStatus>;
  /** Admin: generates the project's Ed25519 key pair. 409 `deploy_key_exists` when there is one. */
  generateDeployKey(repo: string): Promise<DeployStatus>;
  /** Admin: replaces the key pair at once (the old private key is deleted). 404 when there is none. */
  rotateDeployKey(repo: string): Promise<DeployStatus>;
  /**
   * Admin: connects to a target, verifies its host key and authenticates, then disconnects (runs no command). Connection
   * problems are `ok: false`; 409 `deploy_not_approved`, `deploy_key_missing`, `deploy_busy` or `deploy_disabled`, 404 unknown target.
   */
  checkDeployTarget(repo: string, target: string): Promise<DeployCheck>;

  projectRuns(repo: string): Promise<Run[]>;
  startRun(repo: string, issueNumber: number): Promise<Run>;
  /** Creates the missing member labels of a workspace project with the caller's token (403 `labels_forbidden`). */
  createMemberLabels(repo: string): Promise<MemberLabelsResult>;
  /** `active`: not finished (incl. needs_human). `status`: only these statuses. Both given: both apply. */
  runs(filter?: { active?: boolean; status?: RunStatus[] }): Promise<Run[]>;
  run(id: string): Promise<Run>;
  approveRun(id: string): Promise<Run>;
  /**
   * Owner only: merges although the checks on `headSha` have not passed (docs/ARCHITECTURE.md §6 "Checks before a
   * merge"). 409 `run_not_overridable` / `checks_changed` / `review_required` / `review_in_progress` / `agent_busy`.
   */
  mergeAnywayRun(id: string, headSha: string): Promise<Run>;
  cancelRun(id: string): Promise<Run>;
  /** "Retry now" for a run waiting for the network. Owner or admin (403); 409 `run_not_waiting_for_network`. */
  retryRunNow(id: string): Promise<Run>;
  /** "Give up" for a run waiting for the network: it fails now. Owner or admin (403); 409 `run_not_waiting_for_network`. */
  giveUpRun(id: string): Promise<Run>;
  /**
   * Owner only (403 for everyone else, admins included): a message to the run's advisor while it needs a human. 409
   * `run_not_waiting` / `review_in_progress` / `agent_busy` / `too_many_agents` / `run_worktree_missing`, 422
   * `claude_credential_required`.
   */
  talkToRun(id: string, text: string): Promise<{ status: 'accepted'; sessionId: string }>;
  /** Owner only: reviews changes from the conversation again. 409 `nothing_to_review` / `review_in_progress` / `agent_busy`. */
  reviewRun(id: string): Promise<Run>;
  /** How a run can be retried, with the recommendation and its reason. */
  runRetryOptions(id: string): Promise<RetryOptions>;
  /**
   * Owner only, failed or cancelled single runs: retries it (mode omitted = start over). Answers the run now at work: the
   * same run for `step`, a new one otherwise. 409 `run_not_retryable` / `release_run` / `retry_mode_unavailable` / `active_run_exists`.
   */
  retryRun(id: string, mode?: RetryMode): Promise<Run>;

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
  /** Images generated in a session (chat or stage), oldest first. */
  sessionImages(sessionId: string): Promise<GeneratedImage[]>;
  /** Images selected for an issue. */
  issueImages(repo: string, issueNumber: number): Promise<GeneratedImage[]>;
  /**
   * Owner of a chat image only: selects it for an open issue under a name (or moves/renames it). 403 not the owner,
   * 404 unknown image or issue, 400 bad name, 409 `run_image` / `issue_closed` / `too_many_images` / `image_in_use`.
   */
  selectImage(id: string, issueNumber: number, name: string): Promise<GeneratedImage>;
  /** 409 `image_in_use` while a run uses it. */
  unselectImage(id: string): Promise<GeneratedImage>;
  /** Deletes an unselected chat image; 409 `image_selected`. */
  discardImage(id: string): Promise<void>;
  /** URL of a generated image's bytes (same visibility as its project). */
  imageUrl(id: string): string;
  /** URL of a stage screenshot's image (same visibility as the run). */
  screenshotUrl(runId: string, id: string): string;
  /** Server limits for the client (attachments). */
  config(): Promise<ClientConfig>;
  interrupt(id: string): Promise<void>;
  stopSession(id: string): Promise<AgentSession>;
  /**
   * Closes a chat: stops its agent and permanently deletes it (transcript, attachments). Owner or admin; rejects with
   * ApiError 403 for others, 404 when it is unknown or already closed, 409 `not_a_chat` for pipeline sessions and
   * 409 `agent_not_stopped` when the process would not stop.
   */
  closeChat(id: string): Promise<void>;

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
