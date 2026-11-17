// End-to-end suite against the REAL backend in fake mode (fake GitHub + fake Claude CLI). See README.md, "Development".
import { defineConfig, devices } from 'playwright/test';

export default defineConfig({
  testDir: './e2e',
  // One server, shared state (users, runs): the specs build on each other and run in file order.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results',
  globalSetup: './e2e/global-setup.ts',
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 900 },
    locale: 'en-GB',
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
