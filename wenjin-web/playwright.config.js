import { defineConfig, devices } from '@playwright/test'

const previewPort = process.env.WJ_PREVIEW_PORT || '4173'
const previewURL = `http://127.0.0.1:${previewPort}`

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
    baseURL: process.env.WJ_BASE_URL || previewURL,
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
        command: `npm run preview -- --host 127.0.0.1 --port ${previewPort}`,
        url: previewURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000
      }
})
