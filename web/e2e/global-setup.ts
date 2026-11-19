import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
// @ts-expect-error plain ESM helper without type declarations
import { startServer } from './server.mjs';

/** Starts Agern in fake mode on a free port with a fresh database; the returned function stops it. */
export default async function globalSetup() {
  const server = (await startServer()) as { url: string; db: string; dataDir: string; stop: () => Promise<void> };
  const stateDir = mkdtempSync(join(tmpdir(), 'agern-e2e-state-'));
  writeFileSync(join(stateDir, 'state.json'), '{}');
  process.env.E2E_BASE_URL = server.url;
  process.env.E2E_DATA_DIR = server.dataDir;
  process.env.E2E_STATE_DIR = stateDir;
  console.log(`\nAgern e2e server: ${server.url} (database ${server.db})`);
  return async () => {
    await server.stop();
    rmSync(stateDir, { recursive: true, force: true });
  };
}
