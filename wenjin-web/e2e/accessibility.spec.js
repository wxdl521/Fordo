/**
 * Accessibility smoke (Playwright script).
 * Not collected by Vitest (e2e/ excluded). Run with:
 *   WJ_E2E=1 node e2e/accessibility.spec.js
 * Requires a running frontend (default http://127.0.0.1:5173).
 */
import { chromium } from 'playwright'

const BASE = process.env.WJ_BASE_URL || 'http://127.0.0.1:5173'

async function main() {
  if (!process.env.WJ_E2E) {
    console.log('[accessibility] skipped (set WJ_E2E=1 to run against a live app)')
    return
  }

  const browser = await chromium.launch()
  const page = await browser.newPage()
  try {
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 15000 })
    const cssOk = await page.evaluate(() => {
      let focusVisible = false
      let reduced = false
      for (const sheet of document.styleSheets) {
        let rules
        try { rules = [...sheet.cssRules] } catch { continue }
        for (const r of rules) {
          const t = r.cssText || ''
          if (t.includes(':focus-visible')) focusVisible = true
          if (t.includes('prefers-reduced-motion')) reduced = true
        }
      }
      return focusVisible && reduced
    })
    if (!cssOk) throw new Error('tokens.css focus-visible / prefers-reduced-motion not found')

    await page.goto(BASE + '/mobile', { waitUntil: 'domcontentloaded', timeout: 15000 })
    const text = await page.locator('body').innerText()
    if (text.includes('功能开发中')) throw new Error('/mobile still shows placeholder copy')

    console.log('[accessibility] OK')
  } finally {
    await browser.close()
  }
}

main().catch((err) => {
  console.error('[accessibility] FAIL', err)
  process.exit(1)
})
