import { test, expect } from '@playwright/test'

/**
 * Mobile map route: /mobile aliases to /map (ColorMap), no placeholder.
 * Without a live API, auth guard may finish on login after the alias redirect —
 * that is still success as long as we never land on a "功能开发中" mobile stub.
 */
test.describe('mobile map routing', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('/mobile leaves the alias path without placeholder copy', async ({ page }) => {
    await page.goto('/mobile', { waitUntil: 'domcontentloaded' })
    // Vue Router redirect: /mobile → /map, then unauthenticated guard → /
    await page.waitForFunction(() => !window.location.pathname.endsWith('/mobile'), null, {
      timeout: 10_000
    })
    const text = await page.locator('body').innerText()
    expect(text.includes('功能开发中')).toBe(false)
    expect(page.url()).not.toMatch(/\/mobile(?:\?|#|$)/)
  })
})
