import { API_CALLS, expectApiCalls, test, expect } from './auth-fixtures.js'
import AxeBuilder from '@axe-core/playwright'

const BLOCKING_IMPACTS = new Set(['serious', 'critical'])

async function expectNoBlockingViolations(page, pageName) {
  // Axe must sample final colors, not a frame whose route transition still lowers opacity.
  await page.waitForFunction(() => (
    !document.querySelector('.page-fade-enter-active, .page-fade-leave-active')
  ))
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa'])
    .analyze()
  const blocking = results.violations.filter((violation) => (
    BLOCKING_IMPACTS.has(violation.impact)
  ))
  expect(blocking, `${pageName}:\n${JSON.stringify(blocking, null, 2)}`).toEqual([])
}

test.describe('accessibility smoke', () => {
  test('login shell has focus-visible and reduced-motion styles', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByText('欢迎回来', { exact: true })).toBeVisible()
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
        for (const rule of rules) {
          const text = rule.cssText || ''
          if (text.includes(':focus-visible')) focusVisible = true
          if (text.includes('prefers-reduced-motion')) reduced = true
        }
      }
      return focusVisible && reduced
    })
    expect(cssOk).toBe(true)
  })

  test('login page has no serious or critical axe violations', async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' })
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByText('欢迎回来', { exact: true })).toBeVisible()
    await expectNoBlockingViolations(page, 'login')
  })

  test('authenticated map has no serious or critical axe violations', async ({ studentPage }) => {
    await studentPage.goto('/map?courseId=1', { waitUntil: 'networkidle' })
    await expect(studentPage).toHaveURL(/\/map\?courseId=1$/)
    await expect(studentPage.locator('.wj-canvas-container')).toBeVisible()
    await expect(studentPage.getByRole('button', { name: '软件工程基础' })).toBeVisible()
    expectApiCalls(studentPage, [API_CALLS.me, API_CALLS.graph, API_CALLS.review])
    await expectNoBlockingViolations(studentPage, 'authenticated map')
  })

  test('authenticated companion has no serious or critical axe violations', async ({ studentPage }) => {
    await studentPage.goto('/companion?courseId=1', { waitUntil: 'networkidle' })
    await expect(studentPage).toHaveURL(/\/companion\?courseId=1$/)
    await expect(studentPage.locator('.wj-companion')).toBeVisible()
    await expect(studentPage.getByText('E2E 学习计划', { exact: true })).toBeVisible()
    expectApiCalls(studentPage, [API_CALLS.me, API_CALLS.companion])
    await expectNoBlockingViolations(studentPage, 'authenticated companion')
  })

  test('authenticated teacher graph has no serious or critical axe violations', async ({ teacherPage }) => {
    await teacherPage.goto('/teacher/graph', { waitUntil: 'networkidle' })
    await expect(teacherPage).toHaveURL(/\/teacher\/graph$/)
    await expect(teacherPage.getByTestId('course-switch')).toContainText('软件工程测试课')
    await expect(teacherPage.getByText('E2E 待复核关系', { exact: true })).toBeVisible()
    expectApiCalls(teacherPage, [
      API_CALLS.me,
      API_CALLS.teacherCourses,
      API_CALLS.teacherGraph,
      API_CALLS.teacherPending
    ])
    await expectNoBlockingViolations(teacherPage, 'authenticated teacher graph')
  })
})
