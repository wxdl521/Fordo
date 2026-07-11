/**
 * Mobile map route: /mobile aliases to /map (ColorMap), no placeholder.
 * Not collected by Vitest. Run with:
 *   WJ_E2E=1 node e2e/mobile-map.spec.js
 */
import { chromium } from 'playwright'

const BASE = process.env.WJ_BASE_URL || 'http://127.0.0.1:5173'

async function main() {
  if (!process.env.WJ_E2E) {
    console.log('[mobile-map] skipped (set WJ_E2E=1 to run against a live app)')
    return
  }

  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  try {
    await page.goto(BASE + '/mobile', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForURL(/\/map/, { timeout: 10000 })
    const text = await page.locator('body').innerText()
    if (text.includes('功能开发中')) {
      throw new Error('placeholder still visible at 390x844')
    }
    console.log('[mobile-map] OK — /mobile → /map, no placeholder')
  } finally {
    await browser.close()
  }
}

main().catch((err) => {
  console.error('[mobile-map] FAIL', err)
  process.exit(1)
})
