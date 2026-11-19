// Starts a throwaway Agern server for the end-to-end suite: fake GitHub, fake Claude CLI, dev users,
// 2FA required, fake sandboxes (FACTORY_SANDBOX=fake), a fresh database in the development PostgreSQL (docker-compose.dev.yml), a free port,
// a temporary data directory and a frontend built into a temporary directory (web/dist is not touched).
//
//   node e2e/server.mjs            start it and keep it running (Ctrl-C stops it and drops the database)
//   npm run e2e                    the Playwright suite starts and stops it by itself (e2e/global-setup.ts)
//
// Environment: E2E_PG_CONTAINER_CMD overrides how psql is run (default: docker compose exec on the dev
// compose file), E2E_DB_HOST_PORT (default 54329), E2E_SKIP_BUILD=1 reuses existing server/frontend builds.
import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const web = resolve(here, '..');
const repo = resolve(web, '..');

export const USERS = { alice: 'alice-pw', bob: 'bob-pw', carol: 'carol-pw', dave: 'dave-pw' };

function psql(sql) {
  const custom = process.env.E2E_PG_CONTAINER_CMD;
  const [cmd, ...args] = custom
    ? custom.split(' ')
    : ['docker', 'compose', '-f', join(repo, 'docker-compose.dev.yml'), 'exec', '-T', 'postgres'];
  execFileSync(cmd, [...args, 'psql', '-U', 'factory', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-qc', sql], { stdio: 'pipe' });
}

function freePort() {
  return new Promise((ok, fail) => {
    const srv = createServer();
    srv.once('error', fail);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => ok(port));
    });
  });
}

async function waitForHealth(url, proc, timeoutMs = 90_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (proc.exitCode !== null) throw new Error(`Agern exited early with code ${proc.exitCode}`);
    try {
      const r = await fetch(`${url}/api/health`);
      if (r.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error('Agern did not become healthy in time');
}

/** Starts the server; resolves to { url, stop() }. */
export async function startServer({ log = false } = {}) {
  const work = mkdtempSync(join(tmpdir(), 'agern-e2e-'));
  const webDir = join(work, 'web');
  const dataDir = join(work, 'data');
  const serverBin = join(repo, 'server', 'build', 'install', 'server', 'bin', 'server');
  if (!process.env.E2E_SKIP_BUILD || !existsSync(serverBin)) {
    execFileSync(join(repo, 'gradlew'), [':server:installDist', '-q'], { cwd: repo, stdio: 'inherit' });
  }
  execFileSync(join(web, 'node_modules', '.bin', 'vite'), ['build', '--outDir', webDir, '--emptyOutDir', '--logLevel', 'warn'], { cwd: web, stdio: 'inherit' });

  const db = `agern_e2e_${Date.now().toString(36)}_${randomBytes(3).toString('hex')}`;
  psql(`CREATE DATABASE ${db}`);
  const port = await freePort();
  const url = `http://127.0.0.1:${port}`;
  const env = {
    PATH: process.env.PATH,
    HOME: process.env.HOME,
    LANG: process.env.LANG ?? 'C.UTF-8',
    JAVA_HOME: process.env.JAVA_HOME ?? '',
    FACTORY_HOST: '127.0.0.1',
    FACTORY_PORT: String(port),
    FACTORY_PUBLIC_URL: url,
    FACTORY_DB_URL: `jdbc:postgresql://localhost:${process.env.E2E_DB_HOST_PORT ?? '54329'}/${db}`,
    FACTORY_DB_USER: 'factory',
    FACTORY_DB_PASSWORD: 'factory',
    FACTORY_MASTER_KEY: randomBytes(32).toString('base64'),
    FACTORY_GITHUB_ORG: 'agern-e2e',
    FACTORY_AUTH: 'dev',
    FACTORY_DEV_USERS: Object.entries(USERS).map(([u, p]) => `${u}:${p}`).join(','),
    FACTORY_ADMINS: 'alice',
    FACTORY_2FA: 'required',
    FACTORY_DEV_FAKE_GITHUB: 'true',
    FACTORY_CLAUDE_BIN: join(repo, 'server', 'src', 'test', 'resources', 'fake-claude'),
    FACTORY_DATA_DIR: dataDir,
    // Sandboxes with the in-memory development runtime; files under <dataDir>/fake-github/<repo>/ are on the fake
    // GitHub's default branches (e.g. .agern/sandbox.yml, written by the sandbox spec).
    FACTORY_SANDBOX: 'fake',
    FACTORY_WEB_DIR: webDir,
  };
  const proc = spawn(serverBin, [], { env, stdio: log ? 'inherit' : ['ignore', 'pipe', 'pipe'] });
  let output = '';
  if (!log) {
    proc.stdout.on('data', (d) => (output = (output + d).slice(-20_000)));
    proc.stderr.on('data', (d) => (output = (output + d).slice(-20_000)));
  }
  let stopped = false;
  const stop = async () => {
    if (stopped) return;
    stopped = true;
    if (proc.exitCode === null) {
      proc.kill('SIGTERM');
      await new Promise((r) => {
        const t = setTimeout(() => (proc.kill('SIGKILL'), r()), 15_000);
        proc.once('exit', () => (clearTimeout(t), r()));
      });
    }
    try {
      psql(`DROP DATABASE IF EXISTS ${db} WITH (FORCE)`);
    } catch (e) {
      console.error(`could not drop database ${db}: ${e.message}`);
    }
    rmSync(work, { recursive: true, force: true });
  };
  try {
    await waitForHealth(url, proc);
  } catch (e) {
    console.error(output);
    await stop();
    throw e;
  }
  return { url, db, dataDir, stop, output: () => output };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const s = await startServer({ log: true });
  console.log(`\nAgern (fake mode) is running at ${s.url}  database ${s.db}`);
  console.log(`Users: ${Object.entries(USERS).map(([u, p]) => `${u}/${p}`).join(', ')} (alice is admin). Ctrl-C stops it.`);
  const quit = async () => {
    await s.stop();
    process.exit(0);
  };
  process.on('SIGINT', quit);
  process.on('SIGTERM', quit);
}
