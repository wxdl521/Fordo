import { chromium } from 'playwright'

const base = process.env.WJ_BASE || 'http://localhost:5173'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()

try {
  console.log('正在截图：图谱审核页...')
  await page.goto(base + '/teacher/graph')
  await page.waitForTimeout(4000)
  await page.screenshot({ path: 'e2e/p6-graph.png', fullPage: true })
  console.log('✓ p6-graph.png')

  // 用 DEV 钩子驳回一条待复核边后再截
  console.log('正在驳回一条待复核边...')
  await page.evaluate(async () => {
    const state = window.__wjTeacherGraph.state()
    if (state.pending.length > 0) {
      const id = state.pending[0].id
      await window.__wjTeacherGraph.reject(id)
      await window.__wjTeacherGraph.reload()
    }
  })
  await page.waitForTimeout(2000)
  await page.screenshot({ path: 'e2e/p6-graph-after-reject.png', fullPage: true })
  console.log('✓ p6-graph-after-reject.png')

  console.log('正在截图：题目审核池...')
  await page.goto(base + '/teacher/questions')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: 'e2e/p6-questions.png', fullPage: true })
  console.log('✓ p6-questions.png')

  console.log('正在截图：学情看板...')
  await page.goto(base + '/teacher/dashboard')
  await page.waitForTimeout(4000)
  await page.screenshot({ path: 'e2e/p6-dashboard.png', fullPage: true })
  console.log('✓ p6-dashboard.png')

  console.log('\n✓ 所有截图完成！')
} catch (err) {
  console.error('截图失败:', err)
  process.exit(1)
} finally {
  await browser.close()
}
