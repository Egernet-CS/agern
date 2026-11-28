// Canned agent transcripts for the mock backend. Each script is a list of steps that the
// engine either plays live (with streamed partials) or materialises instantly as history.
import type { SandboxLog, SandboxPlatform, SandboxVmLog } from '../api/types';

/** A `system` event; with `sandbox` the full log of a sandbox command (docs/ARCHITECTURE.md §8 "Event"). */
export type SystemStep = { kind: 'system'; level: 'info' | 'warn' | 'error'; text: string; sandbox?: SandboxLog; sandboxVm?: SandboxVmLog; ms?: number };

export type Step =
  | { kind: 'think'; text: string }
  | { kind: 'text'; text: string }
  /** `logs`: system events written while the tool runs (between its tool_use and tool_result), like the sandbox logs. */
  | { kind: 'tool'; name: string; input: Record<string, unknown>; result: string; isError?: boolean; ms?: number; logs?: SystemStep[] }
  | { kind: 'pause'; ms: number }
  | SystemStep;

export const tool = (
  name: string,
  input: Record<string, unknown>,
  result: string,
  ms = 900,
  isError = false,
): Step => ({ kind: 'tool', name, input, result, ms, isError });

const testOutput = (n: number, fail = 0) =>
  [
    '> Task :compileKotlin',
    '> Task :compileTestKotlin',
    '> Task :test',
    '',
    ...Array.from({ length: Math.min(n, 9) }, (_, i) => `CartServiceTest > ${['saves cart for signed-in user', 'restores cart after login', 'merges anonymous cart', 'rejects foreign cart id', 'expires carts after 30 days', 'limits saved carts to 20', 'serialises line items', 'keeps prices as minor units', 'ignores deleted products'][i]} PASSED`),
    '',
    fail ? `${n} tests completed, ${fail} failed` : `BUILD SUCCESSFUL in 14s`,
    `${n + 3} actionable tasks: ${n + 3} executed`,
  ].join('\n');

export function specScript(issue: number, title: string): Step[] {
  return [
    { kind: 'think', text: `The issue asks for "${title}". I should read the issue, find where carts live today and what persistence exists before writing a spec.` },
    tool('mcp__factory__factory_get_issue', { number: issue }, JSON.stringify({ number: issue, title, state: 'open', comments: 2 }, null, 2), 700),
    tool('Glob', { pattern: 'src/**/cart/**/*.kt' }, 'src/main/kotlin/shop/cart/CartService.kt\nsrc/main/kotlin/shop/cart/CartRepository.kt\nsrc/main/kotlin/shop/cart/CartRoutes.kt\nsrc/test/kotlin/shop/cart/CartServiceTest.kt', 500),
    tool('Read', { file_path: 'src/main/kotlin/shop/cart/CartService.kt' }, Array.from({ length: 64 }, (_, i) => `${String(i + 1).padStart(4)}→${['package shop.cart', '', 'class CartService(private val repo: CartRepository) {', '    fun current(userId: UserId?): Cart = TODO()', '}'][i % 5]}`).join('\n'), 600),
    { kind: 'text', text: `I have enough context. Writing the spec.\n\n## Spec — ${title}\n\n**Goal.** Signed-in users can keep a cart across devices.\n\n1. Persist carts in \`carts\` / \`cart_items\` (Flyway \`V7__saved_carts.sql\`).\n2. \`CartService.current()\` loads the saved cart; anonymous carts merge on login.\n3. Expire carts after 30 days of inactivity.\n\n**Out of scope:** sharing carts between users.` },
    tool('Bash', { command: `gh issue comment ${issue} --body-file spec.md`, description: 'Post spec as an issue comment' }, `https://github.com/Egernet-CS/web-shop/issues/${issue}#issuecomment-2210938811`, 800),
    { kind: 'text', text: 'Spec posted on the issue.' },
  ];
}

export function builderScript(round: number, findings: string[]): Step[] {
  if (round > 1) {
    return [
      { kind: 'text', text: `Reviewers asked for changes:\n${findings.map((f) => `- ${f}`).join('\n')}\n\nAddressing them now.` },
      tool('Read', { file_path: 'src/main/kotlin/shop/cart/CartRoutes.kt' }, '  41→    post("/carts/{id}/items") {\n  42→        val id = call.parameters["id"]!!\n  43→        service.addItem(CartId(id), call.receive())', 600),
      tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartRoutes.kt', old_string: 'service.addItem(CartId(id)', new_string: 'service.addItem(requireOwnCart(CartId(id)))' }, 'The file src/main/kotlin/shop/cart/CartRoutes.kt has been updated.', 700),
      tool('Bash', { command: './gradlew test --tests "shop.cart.*"', description: 'Run cart tests' }, testOutput(9), 2600),
      tool('Bash', { command: 'git commit -am "Address review: enforce cart ownership"', description: 'Commit fixes' }, '[factory/issue-42-saved-carts 5e1c0d2] Address review: enforce cart ownership\n 2 files changed, 31 insertions(+), 4 deletions(-)', 600),
      { kind: 'text', text: 'Ownership is enforced on every cart route and covered by a test. Ready for another review.' },
    ];
  }
  return [
    { kind: 'think', text: 'Start with the migration so the repository has something to talk to, then the service, then routes and tests.' },
    tool('Write', { file_path: 'src/main/resources/db/migration/V7__saved_carts.sql', content: '…' }, 'File created successfully at: src/main/resources/db/migration/V7__saved_carts.sql', 700),
    tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartRepository.kt', old_string: '…', new_string: '…' }, 'The file src/main/kotlin/shop/cart/CartRepository.kt has been updated.', 700),
    tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartService.kt', old_string: '…', new_string: '…' }, 'The file src/main/kotlin/shop/cart/CartService.kt has been updated.', 700),
    tool('Bash', { command: './gradlew test', description: 'Run the test suite' }, testOutput(9, 1).replace('expires carts after 30 days PASSED', 'expires carts after 30 days FAILED'), 2400, true),
    { kind: 'text', text: 'One failure — the expiry check compares `Instant` against a `LocalDate`. Fixing.' },
    tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartService.kt', old_string: 'updatedAt < cutoff.toLocalDate()', new_string: 'updatedAt < cutoff' }, 'The file src/main/kotlin/shop/cart/CartService.kt has been updated.', 600),
    tool('Bash', { command: './gradlew test', description: 'Run the test suite' }, testOutput(9), 2400),
    tool('Bash', { command: 'git add -A && git commit -m "Saved carts for signed-in users (#42)"', description: 'Commit' }, '[factory/issue-42-saved-carts a91f3b7] Saved carts for signed-in users (#42)\n 6 files changed, 284 insertions(+), 12 deletions(-)', 600),
    { kind: 'text', text: 'Implemented saved carts: migration, repository, service merge-on-login, 30-day expiry, and 9 tests. All green.' },
  ];
}

/** The builder of an update round ("Update and merge"): finish Agern's merge of the target, renumber, test, commit. */
export function updateBuilderScript(target: string, kind: 'migration' | 'conflict'): Step[] {
  return kind === 'migration'
    ? [
        { kind: 'think', text: `Agern merged ${target}; my migration V7 now has the same version as one that landed there. Renumber mine to V8.` },
        tool('Bash', { command: 'git mv src/main/resources/db/migration/V7__saved_carts.sql src/main/resources/db/migration/V8__saved_carts.sql', description: 'Renumber my migration' }, '', 400),
        tool('Bash', { command: './gradlew test', description: 'Run the test suite' }, testOutput(9), 2400),
        tool('Bash', { command: 'git commit -am "Renumber the saved-carts migration to V8"', description: 'Commit' }, '[factory c41d2e9] Renumber the saved-carts migration to V8\n 1 file changed, 0 insertions(+), 0 deletions(-)', 500),
        { kind: 'text', text: `Merged ${target}: my migration is now V8 (V7 on ${target} keeps its version). Tests green.` },
      ]
    : [
        tool('Bash', { command: 'git diff --name-only --diff-filter=U', description: 'Conflicted files' }, 'src/main/kotlin/shop/cart/CartService.kt', 400),
        tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartService.kt', old_string: '<<<<<<< HEAD', new_string: '…' }, 'The file src/main/kotlin/shop/cart/CartService.kt has been updated.', 700),
        tool('Bash', { command: './gradlew test', description: 'Run the test suite' }, testOutput(9), 2400),
        tool('Bash', { command: 'git add -A && git commit --no-edit', description: 'Finish the merge' }, `[factory 9b0e7a1] Merge ${target} into the run branch`, 500),
        { kind: 'text', text: `Resolved the conflict in CartService.kt with the change from ${target}; tests green.` },
      ];
}

// ---------------------------------------------------------------- sandboxes

export const EXEC = 'mcp__factory__factory_sandbox_exec';

/** A finished sandbox command: its full log (system event) and the JSON the agent gets back (the tail). */
function sandboxExec(
  command: string, exitCode: number, output: string, durationMs: number, extraLogs: SystemStep[] = [], kind: SandboxLog['kind'] = 'exec', platform: string | null = null,
  review = false,
): Step {
  const what = kind === 'setup' ? 'setup script' : `\`${command.split('\n')[0].slice(0, 200)}\``;
  const log: SystemStep = {
    kind: 'system',
    level: exitCode === 0 ? 'info' : 'warn',
    text: `${review ? 'Review sandbox' : 'Sandbox'}${platform ? ` [${platform}]` : ''} ${what}: exit ${exitCode} in ${(durationMs / 1000).toFixed(1)} s`,
    sandbox: { kind, command, exitCode, timedOut: false, durationMs, output, outputTruncated: false, platform, ...(review ? { review: true } : {}) },
  };
  const tail = output.slice(-20_000);
  return {
    kind: 'tool',
    name: EXEC,
    input: platform && platform !== 'default' ? { command, platform } : { command },
    result: JSON.stringify({ exitCode, output: tail, truncated: tail.length < output.length, durationMs, timedOut: false, ...(platform ? { platform } : {}) }),
    ms: Math.min(3200, 900 + durationMs / 40),
    logs: [...extraLogs, log],
  };
}

const aptOutput = [
  'Get:1 http://deb.debian.org/debian bookworm InRelease [151 kB]',
  'Get:2 http://deb.debian.org/debian bookworm-updates InRelease [55.4 kB]',
  'Get:3 http://deb.debian.org/debian-security bookworm-security InRelease [48.0 kB]',
  'Get:4 http://deb.debian.org/debian bookworm/main amd64 Packages [8792 kB]',
  'Fetched 9046 kB in 2s (4523 kB/s)',
  'Reading package lists...',
  'Building dependency tree...',
  'The following NEW packages will be installed:',
  '  libnode108 libuv1 nodejs nodejs-doc npm',
  ...Array.from({ length: 36 }, (_, i) => `Setting up ${['node-abbrev', 'node-ansi-regex', 'node-semver', 'node-glob', 'node-minimatch', 'node-tar'][i % 6]}${i > 5 ? `-${i}` : ''} (${1 + (i % 4)}.${i % 10}.0-1) ...`),
  'Processing triggers for libc-bin (2.36-9+deb12u7) ...',
].join('\n') + '\n';

const gradleTest = (fail: boolean) =>
  [
    'Starting a Gradle Daemon (subsequent builds will be faster)',
    '> Task :compileKotlin',
    '> Task :compileTestKotlin',
    '> Task :test',
    '',
    'CartServiceTest > saves cart for signed-in user PASSED',
    'CartServiceTest > restores cart after login PASSED',
    'CartServiceTest > merges anonymous cart PASSED',
    fail ? 'CartServiceTest > expires carts after 30 days FAILED\n    org.opentest4j.AssertionFailedError: expected: <0> but was: <1>\n        at shop.cart.CartServiceTest.expires carts after 30 days(CartServiceTest.kt:121)' : 'CartServiceTest > expires carts after 30 days PASSED',
    'CartRepositoryTest > persists line items in PostgreSQL PASSED',
    'CartRepositoryTest > deletes expired carts PASSED',
    '',
    fail ? '7 tests completed, 1 failed' : 'BUILD SUCCESSFUL in 38s',
    fail ? 'FAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task \':test\'.\n> There were failing tests.' : '5 actionable tasks: 5 executed',
  ].join('\n') + '\n';

/** About 2,400 lines, so the run page shows its cap and "show all". */
const npmOutput = () =>
  ['', 'added 1187 packages, and audited 1188 packages in 41s', '']
    .concat(Array.from({ length: 2400 }, (_, i) => `vite v6.4.1 building for production... transforming (${i + 1}) src/components/${['Cart', 'Checkout', 'Basket', 'Price'][i % 4]}${i}.tsx`))
    .concat(['✓ 2403 modules transformed.', 'dist/index.html                 0.48 kB', 'dist/assets/index-4f1c2b.js   312.77 kB │ gzip: 98.31 kB', '✓ built in 9.81s'])
    .join('\n') + '\n';

/** The builder of a run with an approved sandbox: builds and tests inside it through the factory's MCP tools. */
export function sandboxBuilderScript(round: number, image: string, setup: string | null, services: string[], platforms: SandboxPlatform[] = []): Step[] {
  // With platforms, every log names where it ran (null without, like the backend).
  const at = platforms.length ? 'default' : null;
  if (round > 0) {
    return [
      { kind: 'text', text: 'Addressing the review findings; tests run in the sandbox again.' },
      tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartRoutes.kt', old_string: 'service.addItem(CartId(id)', new_string: 'service.addItem(requireOwnCart(CartId(id)))' }, 'The file src/main/kotlin/shop/cart/CartRoutes.kt has been updated.', 700),
      sandboxExec('./gradlew test --tests "shop.cart.*"', 0, gradleTest(false), 24_100, [], 'exec', at),
      tool('Bash', { command: 'git commit -am "Address review: enforce cart ownership"', description: 'Commit fixes' }, '[factory/issue-42-saved-carts 5e1c0d2] Address review: enforce cart ownership\n 2 files changed, 31 insertions(+), 4 deletions(-)', 600),
      { kind: 'text', text: 'Ownership is enforced on every cart route; the cart tests are green in the sandbox.' },
    ];
  }
  const created: SystemStep[] = [
    { kind: 'system', level: 'info', text: `Creating the sandbox: image ${image}${services.length ? `, services ${services.join(', ')}` : ''} …` },
  ];
  const setupLogs: SystemStep[] = setup
    ? [{ kind: 'system', level: 'info', text: `Sandbox${at ? ` [${at}]` : ''} setup script: exit 0 in 41.3 s`, sandbox: { kind: 'setup', command: setup, exitCode: 0, timedOut: false, durationMs: 41_300, output: aptOutput, outputTruncated: false, platform: at } }]
    : [];
  const ready: SystemStep = { kind: 'system', level: 'info', text: `Sandbox ready in 47.9 s: ${image}, 2 CPUs, 4g memory.` };
  return [
    { kind: 'think', text: 'The run has a sandbox with the project toolchain and a PostgreSQL sidecar. Build and test there, not on the host.' },
    tool('mcp__factory__factory_sandbox_info', {}, JSON.stringify({ available: true, state: 'approved', image, created: false, workdir: '/workspace', services: services.map((n) => ({ name: n, host: n, image: 'docker.io/library/postgres:17', ports: [] })), caches: ['/root/.gradle', '/root/.npm'], limits: { cpus: 2, memory: '4g', execTimeoutSeconds: 1800, outputTailChars: 20000 }, capacity: { inUse: 1, max: 2 } }, null, 2), 500),
    tool('Write', { file_path: 'src/main/resources/db/migration/V7__saved_carts.sql', content: '…' }, 'File created successfully at: src/main/resources/db/migration/V7__saved_carts.sql', 700),
    tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartService.kt', old_string: '…', new_string: '…' }, 'The file src/main/kotlin/shop/cart/CartService.kt has been updated.', 700),
    sandboxExec('./gradlew test', 1, gradleTest(true), 38_400, [...created, ...setupLogs, ready], 'exec', at),
    { kind: 'text', text: 'One failure — the expiry check compares `Instant` against a `LocalDate`. Fixing.' },
    tool('Edit', { file_path: 'src/main/kotlin/shop/cart/CartService.kt', old_string: 'updatedAt < cutoff.toLocalDate()', new_string: 'updatedAt < cutoff' }, 'The file src/main/kotlin/shop/cart/CartService.kt has been updated.', 600),
    sandboxExec('npm ci && npm run build', 0, npmOutput(), 52_700, [], 'exec', at),
    sandboxExec('./gradlew test', 0, gradleTest(false), 31_900, [], 'exec', at),
    ...platforms.map((pl) => {
      const plSetup: SystemStep[] = pl.setup
        ? [{ kind: 'system', level: 'info', text: `Sandbox [${pl.name}] setup script: exit 0 in 38.0 s`, sandbox: { kind: 'setup', command: pl.setup, exitCode: 0, timedOut: false, durationMs: 38_000, output: aptOutput, outputTruncated: false, platform: pl.name } }]
        : [];
      return sandboxExec('./gradlew test', 0, gradleTest(false), 29_400, [
        { kind: 'system', level: 'info', text: `Creating platform \`${pl.name}\`: image ${pl.image} …` },
        ...plSetup,
        { kind: 'system', level: 'info', text: `Platform \`${pl.name}\` ready in 40.2 s: ${pl.image}, 2 CPUs, 4g memory, commands as builder (uid 1000).` },
      ], 'exec', pl.name);
    }),
    tool('Bash', { command: 'git add -A && git commit -m "Saved carts for signed-in users (#42)"', description: 'Commit' }, '[factory/issue-42-saved-carts a91f3b7] Saved carts for signed-in users (#42)\n 6 files changed, 284 insertions(+), 12 deletions(-)', 600),
    { kind: 'text', text: `Implemented saved carts. Built the frontend and ran the test suite in the sandbox (PostgreSQL sidecar): all green${platforms.length ? ` — default image and ${platforms.map((x) => x.name).join(', ')}` : ''}.` },
  ];
}

/**
 * The builder of a project that builds on the host (`build: host`): builds and runs the plain tests on the host and uses
 * the sandbox only for [sandboxCommands] (tests that need its services, starting the app), none when nothing needs it.
 */
export function hostBuilderScript(round: number, image: string, setup: string | null, services: string[], sandboxCommands: string[]): Step[] {
  const swiftTest = (n: number) => `Building for debugging...\n[42/42] Linking PickerPackageTests\nTest Suite 'All tests' passed.\n\t Executed ${n} tests, with 0 failures (0 unexpected) in 3.112 seconds`;
  if (round > 0) {
    return [
      { kind: 'text', text: 'Addressing the review findings; building and testing on the host again.' },
      tool('Edit', { file_path: 'Sources/Picker/PickListStore.swift', old_string: 'refresh()', new_string: 'refresh(after: sync)' }, 'The file Sources/Picker/PickListStore.swift has been updated.', 700),
      tool('Bash', { command: 'swift test', description: 'Run the tests on the host' }, swiftTest(31), 2400),
      tool('Bash', { command: 'git commit -am "Address review: refresh after every sync"', description: 'Commit fixes' }, '[factory/issue-9-pick-list 2b4e9c1] Address review: refresh after every sync\n 1 file changed, 6 insertions(+), 2 deletions(-)', 600),
      { kind: 'text', text: 'Fixed; the test suite is green on the host.' },
    ];
  }
  const created: SystemStep[] = [
    { kind: 'system', level: 'info', text: `Creating the sandbox: image ${image}${services.length ? `, services ${services.join(', ')}` : ''} …` },
  ];
  const setupLogs: SystemStep[] = setup
    ? [{ kind: 'system', level: 'info', text: 'Sandbox setup script: exit 0 in 12.4 s', sandbox: { kind: 'setup', command: setup, exitCode: 0, timedOut: false, durationMs: 12_400, output: aptOutput, outputTruncated: false, platform: null } }]
    : [];
  const ready: SystemStep = { kind: 'system', level: 'info', text: `Sandbox ready in 19.6 s: ${image}, 2 CPUs, 4g memory.` };
  const inSandbox = sandboxCommands.map((c, i) => sandboxExec(c, 0, i === 0 ? swiftTest(6) : 'Server started at http://0.0.0.0:8080\nGET /health 200', 18_300 + i * 4_000, i === 0 ? [...created, ...setupLogs, ready] : []));
  return [
    { kind: 'think', text: 'The project builds on the host (build: host). Build and run the plain tests here; the sandbox only for what needs its services or the running app.' },
    tool('Edit', { file_path: 'Sources/Picker/PickListStore.swift', old_string: '…', new_string: '…' }, 'The file Sources/Picker/PickListStore.swift has been updated.', 700),
    tool('Bash', { command: 'swift build', description: 'Build on the host' }, 'Building for debugging...\n[118/118] Compiling Picker PickListStore.swift\nBuild complete! (21.37s)', 2200),
    tool('Bash', { command: 'swift test --skip SyncIntegrationTests', description: 'Run the plain tests on the host' }, swiftTest(25), 2600),
    ...inSandbox,
    tool('Bash', { command: 'git add -A && git commit -m "Refresh the pick list after a sync"', description: 'Commit' }, '[factory/issue-9-pick-list 7c1d0a3] Refresh the pick list after a sync\n 3 files changed, 58 insertions(+), 9 deletions(-)', 600),
    {
      kind: 'text',
      text: sandboxCommands.length
        ? `Built and ran the plain tests on the host; ran ${sandboxCommands.length === 1 ? 'the sync integration tests' : 'the sync integration tests and the app'} in the sandbox (${services.join(', ')} sidecar). All green.`
        : 'Built and ran the tests on the host; nothing needed the sandbox, so none was created. All green.',
    },
  ];
}

export const reviewerFocus: Record<string, { files: string[]; look: string }> = {
  security: { files: ['CartRoutes.kt', 'CartRepository.kt'], look: 'authorization on cart ids and SQL parameterisation' },
  ux: { files: ['cart.tsx', 'login.tsx'], look: 'what happens to an anonymous cart on login' },
  'ui-design': { files: ['cart.css', 'CartBadge.tsx'], look: 'empty and loading states of the cart drawer' },
  code: { files: ['CartService.kt', 'CartServiceTest.kt'], look: 'naming, test coverage and transaction boundaries' },
};

export function reviewerScript(role: string, verdict: 'PASS' | 'CHANGES', summary: string): Step[] {
  const f = reviewerFocus[role] ?? reviewerFocus.code;
  return [
    tool('Bash', { command: 'git diff main...HEAD --stat', description: 'Show changed files' }, ' src/main/kotlin/shop/cart/CartRepository.kt   |  88 ++++++++\n src/main/kotlin/shop/cart/CartRoutes.kt       |  41 +++-\n src/main/kotlin/shop/cart/CartService.kt      | 102 +++++++--\n src/main/resources/db/migration/V7__saved_carts.sql |  22 ++\n src/test/kotlin/shop/cart/CartServiceTest.kt  | 143 +++++++++++\n 6 files changed, 384 insertions(+), 12 deletions(-)', 600),
    tool('Read', { file_path: `src/main/kotlin/shop/cart/${f.files[0]}` }, '   1→package shop.cart\n   2→\n   3→import shop.auth.UserId\n   …', 700),
    tool('Grep', { pattern: 'CartId\\(', path: 'src/main' }, 'src/main/kotlin/shop/cart/CartRoutes.kt:42\nsrc/main/kotlin/shop/cart/CartRoutes.kt:57\nsrc/main/kotlin/shop/cart/CartService.kt:19', 500),
    { kind: 'text', text: `Checked ${f.look}.\n\n**Verdict: ${verdict}** — ${summary}` },
  ];
}

/**
 * What a ux reviewer of a run with an approved sandbox does first (like the backend): starts the app detached in the
 * shared, read-only review sandbox and waits until it answers.
 */
export function reviewSandboxSteps(image: string, setup: string | null): Step[] {
  const created: SystemStep[] = [
    { kind: 'system', level: 'info', text: `Creating the review sandbox: image ${image}, services postgres …` },
    ...(setup
      ? [{ kind: 'system' as const, level: 'info' as const, text: 'Review sandbox setup script: exit 0 in 39.8 s', sandbox: { kind: 'setup' as const, command: setup, exitCode: 0, timedOut: false, durationMs: 39_800, output: aptOutput, outputTruncated: false, platform: null, review: true } }]
      : []),
    { kind: 'system', level: 'info', text: `Review sandbox ready in 44.1 s: ${image}, 2 CPUs, 4g memory, /workspace read-only, output in .agern-run/review.` },
  ];
  const start = 'cp -a /workspace /tmp/app && cd /tmp/app && (nohup ./gradlew run > /tmp/app.log 2>&1 < /dev/null &) && for i in $(seq 90); do curl -fsS http://127.0.0.1:8081/ >/dev/null && break; sleep 1; done; tail -3 /tmp/app.log';
  return [
    { kind: 'think', text: 'The run has a read-only review sandbox. Start the shop there and take the screenshots inside it.' },
    sandboxExec(start, 0, '> Task :run\nShop listening on http://0.0.0.0:8081\nConnected to postgres:5432\n', 58_300, created, 'exec', null, true),
  ];
}

export function approverScript(verdict: 'APPROVE' | 'ESCALATE', title = 'Saved carts for signed-in users (#42)'): Step[] {
  return [
    tool('Bash', { command: 'gh pr view --json title,additions,deletions,reviews', description: 'Inspect the pull request' }, JSON.stringify({ title, additions: 415, deletions: 16 }), 700),
    { kind: 'think', text: 'All four reviewers pass. The change touches a migration; it is additive and reversible.' },
    {
      kind: 'text',
      text:
        verdict === 'APPROVE'
          ? 'All reviewers pass, tests are green, and the migration is additive. **APPROVE** — squash-merging.'
          : 'The change introduces a new payment-provider dependency that was not in the spec. **ESCALATE** — a human should confirm the vendor choice.',
    },
  ];
}

export type ChatReply = { match: RegExp; steps: Step[] };

export const chatReplies: ChatReply[] = [
  {
    match: /test|fail|ci/i,
    steps: [
      { kind: 'think', text: 'Let me run the tests first to see what is actually failing rather than guessing.' },
      tool('Bash', { command: './gradlew test', description: 'Run the test suite' }, testOutput(9, 1).replace('restores cart after login PASSED', 'restores cart after login FAILED\n    java.lang.AssertionError: expected 3 items but was 2\n        at shop.cart.CartServiceTest.restores cart after login(CartServiceTest.kt:88)'), 2200, true),
      tool('Read', { file_path: 'src/test/kotlin/shop/cart/CartServiceTest.kt', offset: 70, limit: 30 }, '  80→    @Test\n  81→    fun `restores cart after login`() = runTest {\n  82→        val anon = service.current(null)\n  83→        service.addItem(anon.id, item("A"))\n  84→        service.login(user, anon.id)\n  …', 700),
      { kind: 'text', text: "The failing test is `restores cart after login`. The merge drops items that exist in both carts instead of summing quantities:\n\n```kotlin\n// CartService.kt\nval merged = (saved.items + anon.items)\n    .groupBy { it.sku }\n    .map { (_, lines) -> lines.first().copy(qty = lines.sumOf { it.qty }) }\n```\n\nWant me to apply this fix, or file it as a bug for the pipeline with `/factory run`?" },
    ],
  },
  {
    match: /issue|bug|feature|task/i,
    steps: [
      tool('mcp__factory__factory_list_issues', { state: 'open' }, '[{"number":42,"title":"Saved carts for signed-in users","kind":"feature"},{"number":51,"title":"Checkout button jumps on iOS Safari","kind":"bug"},{"number":53,"title":"Upgrade Ktor to 3.1","kind":"task"}]', 700),
      { kind: 'text', text: 'There are **3 open issues**:\n\n| # | Kind | Title |\n|---|---|---|\n| 42 | feature | Saved carts for signed-in users |\n| 51 | bug | Checkout button jumps on iOS Safari |\n| 53 | task | Upgrade Ktor to 3.1 |\n\n#42 already has a pipeline run in review. Shall I start one for #51?' },
    ],
  },
  {
    match: /.*/,
    steps: [
      { kind: 'think', text: 'I should look at the repository layout before answering.' },
      tool('Bash', { command: 'git log --oneline -5', description: 'Show recent commits' }, 'a91f3b7 Saved carts for signed-in users (#42)\n3c2d0e1 Tidy checkout totals\n77ab012 Bump kotlinx-serialization to 1.7.3\n0e4c8d9 Fix VAT rounding for DK (#47)\nbb1290f Initial Ktor 3 migration', 800),
      tool('Glob', { pattern: 'src/main/kotlin/shop/**/*.kt' }, Array.from({ length: 23 }, (_, i) => `src/main/kotlin/shop/${['cart', 'checkout', 'catalog', 'auth'][i % 4]}/${['Service', 'Routes', 'Repository', 'Model', 'Mapper', 'Validator'][i % 6]}${i}.kt`).join('\n'), 500),
      { kind: 'text', text: "Here's the lay of the land:\n\n- **cart/** — saved carts landed in `a91f3b7`; merge-on-login lives in `CartService`.\n- **checkout/** — totals and VAT; `0e4c8d9` fixed DK rounding.\n- **catalog/** and **auth/** are untouched recently.\n\nNothing looks half-finished. Tell me what you want to build next, or use `/factory run <issue>` to hand an issue to the pipeline." },
    ],
  },
];

/** Long history for the seeded chat session. */
export const seededChat: { user: string; steps: Step[] }[] = [
  {
    user: 'Why did the checkout total differ from the invoice for order 1182?',
    steps: [
      { kind: 'think', text: 'Totals vs invoices — probably rounding. Look at how VAT is computed in both places.' },
      tool('Grep', { pattern: 'vat|VAT', path: 'src/main/kotlin/shop/checkout', output_mode: 'files_with_matches' }, 'src/main/kotlin/shop/checkout/Totals.kt\nsrc/main/kotlin/shop/checkout/InvoiceRenderer.kt', 500),
      tool('Read', { file_path: 'src/main/kotlin/shop/checkout/Totals.kt' }, '  12→    fun vat(lines: List<Line>) = lines.sumOf { (it.price * it.qty * rate).roundToLong() }\n  13→\n  14→    fun vatInvoice(total: Long) = (total * rate).roundToLong()', 600),
      { kind: 'text', text: 'Checkout rounds VAT **per line**, the invoice rounds it **once on the total**. With 7 lines at 24.99 kr that drifts by 3 øre — exactly the difference on order 1182.\n\nFix: round once, on the total, in both places. I filed it as #47.' },
    ],
  },
];
