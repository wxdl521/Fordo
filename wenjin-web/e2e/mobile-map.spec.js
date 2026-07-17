import { API_CALLS, expectApiCalls, test, expect } from './auth-fixtures.js'

test.describe('mobile map routing', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('/mobile resolves to an interactive authenticated map without overflow', async ({ studentPage }) => {
    await studentPage.goto('/mobile?courseId=1&root=1', { waitUntil: 'networkidle' })

    await expect(studentPage).toHaveURL(/\/map\?courseId=1&root=1$/)
    await expect(studentPage.locator('.wj-canvas-container')).toBeVisible()
    await expect(studentPage.getByText('欢迎回来', { exact: true })).toHaveCount(0)

    const graphNode = studentPage.getByRole('button', { name: '软件工程基础' })
    await expect(graphNode).toBeVisible()
    expectApiCalls(studentPage, [API_CALLS.me, API_CALLS.graph, API_CALLS.review])

    const overflow = await studentPage.evaluate(() => ({
      document: document.documentElement.scrollWidth - window.innerWidth,
      body: document.body.scrollWidth - window.innerWidth
    }))
    expect(overflow.document).toBeLessThanOrEqual(1)
    expect(overflow.body).toBeLessThanOrEqual(1)

    await graphNode.focus()
    await expect(graphNode).toBeFocused()
    await studentPage.keyboard.press('Enter')
    await expect(studentPage.getByRole('dialog', { name: '软件工程基础' })).toBeVisible()

    await studentPage.getByRole('button', { name: '关闭详情' }).click()
    await expect(studentPage.getByRole('dialog', { name: '软件工程基础' })).toHaveCount(0)

    await graphNode.focus()
    await studentPage.keyboard.press('Space')
    await expect(studentPage.getByRole('dialog', { name: '软件工程基础' })).toBeVisible()
  })
})
