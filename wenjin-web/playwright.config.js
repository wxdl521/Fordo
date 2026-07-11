import { defineConfig, devices } from '@playwright/test'

/**
 * Browser CI gate: Chromium smoke against Vite preview.
 * Full backend-dependent acceptance scripts remain under e2e/*.mjs (manual / WJ_E2E).
 */
export default defineConfig({
  testDir: './e2e',
  // Only @playwright/test suites (legacy e2e/*.mjs runners are WJ_E2E manual scripts)
  testMatch: ['accessibility.spec.js', 'mobile-map.spec.js'],
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.WJ_BASE_URL || 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      // CI installs Playwright Chromium; local can set WJ_PW_CHANNEL=chrome|msedge
      use: process.env.WJ_PW_CHANNEL
        ? { ...devices['Desktop Chrome'], channel: process.env.WJ_PW_CHANNEL }
        : { ...devices['Desktop Chrome'] }
    }
  ],
  webServer: process.env.WJ_SKIP_WEBSERVER
    ? undefined
    : {
        command: 'npm run preview -- --host 127.0.0.1 --port 4173',
        url: 'http://127.0.0.1:4173',
        reuseExistingServer: !process.env.CI,
        timeout: 120_000
      }
})
