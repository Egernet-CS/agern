// What the run page says about the builder's use of the build sandbox (docs/ARCHITECTURE.md §10). Pure, so it is unit-tested.
import type { RunSandbox } from '../../api/types';

/** The run-level record of build sandbox use: whether to show "sandbox not used", and the disclosure's summary. */
export type SandboxUse = {
  /** The project builds on the host (`build: host`). */
  host: boolean;
  /** Host mode and no command ran in the sandbox: the run page says "sandbox not used". */
  notUsed: boolean;
  /** The disclosure's summary, or null when there is no command to list. */
  summary: string | null;
  /** Commands counted but not listed (the run keeps the first 50): the list's last line. */
  more: number;
};

const commands = (n: number) => `${n} command${n === 1 ? '' : 's'}`;

/** How an approved run's sandbox use reads. Older runs without the fields read as build mode `sandbox` with nothing recorded. */
export function sandboxUse(sb: Pick<RunSandbox, 'build' | 'commands' | 'commandCount'>): SandboxUse {
  const host = sb.build === 'host';
  const count = sb.commandCount ?? 0;
  const listed = sb.commands?.length ?? 0;
  return {
    host,
    notUsed: host && count === 0,
    summary: count === 0 ? null : host ? `sandbox used for ${commands(count)}` : `${count} sandbox ${count === 1 ? 'command' : 'commands'}`,
    more: Math.max(0, count - listed),
  };
}
