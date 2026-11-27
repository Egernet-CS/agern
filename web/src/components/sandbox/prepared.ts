// Pure texts for prepared sandbox images and the sandbox startup time (docs/SANDBOXES.md "Prepared images").
import type { RunSandbox } from '../../api/types';

/** Disk sizes as Podman reports them, in binary units: `420 MB`, `1.3 GB`. */
export function diskSize(n: number): string {
  const mb = n / (1024 * 1024);
  if (mb < 1) return `${Math.max(0, Math.round(n / 1024))} KB`;
  if (mb < 1024) return `${Math.round(mb)} MB`;
  return `${(mb / 1024).toFixed(1)} GB`;
}

/** Seconds for the startup line: one decimal below 10 s, whole seconds above. */
export function seconds(ms: number): string {
  const s = ms / 1000;
  return s < 10 ? `${s.toFixed(1)} s` : `${Math.round(s)} s`;
}

/**
 * The run page's startup note: how long the builder waited for its first sandbox command, and (title) how much of it
 * creating the sandbox took and whether it came from a prepared image. Null for older runs and before the first command.
 */
export function startupNote(sb: Pick<RunSandbox, 'firstCommandMs' | 'createMs' | 'startedFrom' | 'preparedKey'>): { text: string; title: string } | null {
  if (sb.firstCommandMs == null) return null;
  const how = (() => {
    switch (sb.startedFrom) {
      case 'reused':
        return `from prepared image ${sb.preparedKey ?? ''}`.trim();
      case 'prepared':
        return `setup ran, prepared image ${sb.preparedKey ?? ''} stored`.replace('  ', ' ');
      case 'setup':
        return 'setup ran';
      case 'plain':
        return 'no setup script';
      default:
        return null;
    }
  })();
  const took = sb.createMs != null ? `; creating the sandbox took ${seconds(sb.createMs)}${how ? ` (${how})` : ''}` : '';
  return {
    text: `first command after ${seconds(sb.firstCommandMs)}`,
    title: `Time from the builder's start to its first sandbox command${took}`,
  };
}
