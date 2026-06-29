// 图谱预览改确定性渲染 — 真机视觉验收（Playwright + 系统 Edge, headless）
// 前置：后端 8080 + vite 5173 已起；DB 有 course5(软件工程) / course6(Java)。
// 验证：点「生成预览图」走纯前端确定性渲染（circle.wj-node），章节横向铺开（非竖列），下载可用。
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
// 注入老师登录态（router 守卫 role===1 + http.js X-User-Id）
await page.addInitScript(() => {
  localStorage.setItem('wj_user', JSON.stringify({ id: 1, role: 1, name: 'demo_teacher' }))
})

const fail = []
const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}

async function switchCourse(nameIncludes) {
  await page.click('[data-testid=course-switch]')
  await page.waitForTimeout(250)
  const item = page.locator('[data-testid=course-menu] > div', { hasText: nameIncludes }).first()
  await item.click()
  // 切课后重新拉图
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(600)
}

async function openPreview() {
  await page.click('button:has-text("图谱操作")')
  await page.waitForTimeout(200)
  await page.click('div:has-text("生成预览图") >> nth=-1')
  // 等弹窗里的确定性 SVG 渲染出来
  await page.waitForSelector('circle.wj-node', { timeout: 8000 })
  await page.waitForTimeout(300)
}

// 取预览 SVG 里所有节点圆的 cx，算横向跨度与不同 x 带数量
async function nodeXStats() {
  return await page.evaluate(() => {
    const cs = Array.from(document.querySelectorAll('circle.wj-node'))
    const xs = cs.map((c) => parseFloat(c.getAttribute('cx')))
    if (!xs.length) return { n: 0, spread: 0, bands: 0, min: 0, max: 0 }
    const min = Math.min(...xs), max = Math.max(...xs)
    // 按 80px 粒度分桶，数有多少个不同 x 带（竖列只会落 1~2 个带）
    const bands = new Set(xs.map((x) => Math.round(x / 80))).size
    return { n: xs.length, spread: +(max - min).toFixed(1), bands, min: +min.toFixed(1), max: +max.toFixed(1) }
  })
}

async function closePreview() {
  await page.click('button:has-text("关闭")').catch(() => {})
  await page.waitForTimeout(200)
}

try {
  await page.goto(base + '/teacher/graph', { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid=course-switch]', { timeout: 10000 })

  // ── 主验收：Java 课（当初竖挤的主角，非 SE → 网格铺开）──
  await switchCourse('Java')
  await openPreview()
  const java = await nodeXStats()
  check('Java 预览走确定性渲染(circle.wj-node 存在)', java.n > 0, `n=${java.n}`)
  check('Java 节点横向铺开(x 跨度 > 600)', java.spread > 600, `spread=${java.spread} (min=${java.min},max=${java.max})`)
  check('Java 非竖列(不同 x 带 >= 4)', java.bands >= 4, `bands=${java.bands}`)
  await page.screenshot({ path: SHOTS + '/preview-java.png', fullPage: false })
  await closePreview()

  // ── 软件工程课（含「软件实现」+空章节 → 同样网格，验证任意科目都铺开）──
  await switchCourse('软件工程')
  await openPreview()
  const se = await nodeXStats()
  check('软件工程预览走确定性渲染', se.n > 0, `n=${se.n}`)
  check('软件工程节点横向铺开(x 跨度 > 600)', se.spread > 600, `spread=${se.spread} (min=${se.min},max=${se.max})`)
  await page.screenshot({ path: SHOTS + '/preview-se.png', fullPage: false })

  // ── 下载 .svg 按钮存在且可点 ──
  const dlVisible = await page.locator('button:has-text("下载 .svg")').isVisible().catch(() => false)
  check('下载 .svg 按钮可见', dlVisible)
  await closePreview()
} catch (e) {
  console.log('✗ 脚本异常:', e.message)
  fail.push('exception')
  await page.screenshot({ path: SHOTS + '/preview-error.png' }).catch(() => {})
}

await browser.close()
console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
