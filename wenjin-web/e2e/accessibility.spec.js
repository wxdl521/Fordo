import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

/**
 * Accessibility smoke against the preview server (see playwright.config.js webServer).
 */
test.describe('accessibility smoke', () => {
  test('login shell has focus-visible and reduced-motion styles', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    const cssOk = await page.evaluate(() => {
      let focusVisible = false
      let reduced = false
      for (const sheet of document.styleSheets) {
        let rules
        try {
          rules = [...sheet.cssRules]
        } catch {
          continue
        }
        for (const r of rules) {
          const t = r.cssText || ''
          if (t.includes(':focus-visible')) focusVisible = true
          if (t.includes('prefers-reduced-motion')) reduced = true
        }
      }
      return focusVisible && reduced
    })
    expect(cssOk).toBe(true)
  })

  test('login page has no critical axe violations (excl. known contrast tokens)', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' })
    // Let page-fade enter animation settle so axe does not sample mid-transition
    await page.waitForTimeout(400)
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      // Paper theme --mut tokens are intentionally soft; tracked separately from CI gate
      .disableRules(['color-contrast'])
      .analyze()
    const critical = results.violations.filter((v) => v.impact === 'critical')
    expect(critical, JSON.stringify(critical, null, 2)).toEqual([])
  })

  test('/mobile does not show the legacy placeholder', async ({ page }) => {
    await page.goto('/mobile', { waitUntil: 'domcontentloaded' })
    const text = await page.locator('body').innerText()
    expect(text.includes('功能开发中')).toBe(false)
  })
})
