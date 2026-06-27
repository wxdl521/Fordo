// 图谱审核预览改力导向星图(退役 dagre)— 真机视觉验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;DB 有 course5(软件工程,含应用边)/course6(Java)。
// 验证:预览走 computeLayout → renderGraphSvg(暖纸星图):
//   ①circle.wj-node 渲染;②暖纸底 #f7f3ec;③节点辉光halo(opacity 0.10);
//   ④边为 <line> 直线(无 dagre <path fill=none>);⑤三色箭头 marker(pending/adopted/confirm);
//   ⑥章节水印 font-size=21;⑦图例含 待复核/已采纳/已确认;⑧坐标全有限(无 NaN,软工含应用边)。
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
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
  await page.locator('[data-testid=course-menu] > div', { hasText: nameIncludes }).first().click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(600)
}

async function openPreview() {
  await page.click('button:has-text("图谱操作")')
  await page.waitForTimeout(200)
  await page.click('div:has-text("生成预览图") >> nth=-1')
  await page.waitForSelector('circle.wj-node', { timeout: 8000 })
  await page.waitForTimeout(300)
}

async function svgStats() {
  return await page.evaluate(() => {
    const nodes = Array.from(document.querySelectorAll('circle.wj-node'))
    let svg = document.querySelector('svg')
    if (nodes.length > 0) {
      svg = nodes[0].closest('svg') || svg
    }
    const cx = nodes.map((c) => parseFloat(c.getAttribute('cx')))
    const cy = nodes.map((c) => parseFloat(c.getAttribute('cy')))
    const rect = svg ? svg.querySelector('rect') : document.querySelector('svg rect')
    const bgFill = rect ? rect.getAttribute('fill') : null
    const halos = Array.from(document.querySelectorAll('circle[opacity="0.10"]')).length
    const lines = document.querySelectorAll('svg line').length
    const dagrePaths = document.querySelectorAll('path[fill="none"]').length
    const markers = ['arrow-pending', 'arrow-adopted', 'arrow-confirm']
      .map((id) => (document.getElementById(id) ? 1 : 0)).reduce((a, b) => a + b, 0)
    const titles = Array.from(document.querySelectorAll('text')).filter((t) => t.getAttribute('font-size') === '21').length
    const legendText = svg ? svg.textContent : ''
    const allFinite = [...cx, ...cy].every((v) => Number.isFinite(v))
    return {
      hasSvg: !!svg, n: nodes.length, bgFill, halos, lines, dagrePaths, markers, titles,
      hasPending: legendText.includes('待复核'),
      hasAdopted: legendText.includes('已采纳'),
      hasConfirm: legendText.includes('已确认'),
      allFinite
    }
  })
}

async function closePreview() {
  await page.click('button:has-text("关闭")').catch(() => {})
  await page.waitForTimeout(200)
}

try {
  await page.goto(base + '/teacher/graph', { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid=course-switch]', { timeout: 10000 })

  // ── Java(纯前置+包含 DAG)──
  await switchCourse('Java')
  await openPreview()
  const j = await svgStats()
  console.log('Java stats:', JSON.stringify(j))
  check('Java 预览渲染(circle.wj-node)', j.n > 0, `n=${j.n}`)
  check('Java 暖纸底色 #f7f3ec', j.bgFill === '#f7f3ec', `bgFill=${j.bgFill}`)
  check('Java 节点辉光halo(opacity 0.10)', j.halos >= j.n, `halos=${j.halos} >= n=${j.n}`)
  check('Java 边为 <line> 直线', j.lines > 0, `lines=${j.lines}`)
  check('Java 无 dagre 折线 <path fill=none>', j.dagrePaths === 0, `dagrePaths=${j.dagrePaths}`)
  check('Java 三色箭头 marker', j.markers === 3, `markers=${j.markers}`)
  check('Java 章节水印(font-size 21)', j.titles > 0, `titles=${j.titles}`)
  check('Java 图例含 待复核/已采纳/已确认', j.hasPending && j.hasAdopted && j.hasConfirm)
  check('Java 坐标全有限(无 NaN)', j.allFinite)
  await page.screenshot({ path: SHOTS + '/starmap-java.png', fullPage: false })
  await closePreview()

  // ── 软件工程(含应用边 relation_type=4 + 相关)──
  await switchCourse('软件工程')
  await openPreview()
  const s = await svgStats()
  console.log('SE stats:', JSON.stringify(s))
  check('软工 预览渲染', s.n > 0, `n=${s.n}`)
  check('软工 暖纸底色', s.bgFill === '#f7f3ec', `bgFill=${s.bgFill}`)
  check('软工 节点辉光halo', s.halos >= s.n, `halos=${s.halos}`)
  check('软工 边为直线(无 dagre 折线)', s.dagrePaths === 0, `dagrePaths=${s.dagrePaths}`)
  check('软工 含应用/相关边坐标全有限(无 NaN)', s.allFinite)
  await page.screenshot({ path: SHOTS + '/starmap-se.png', fullPage: false })

  const dlVisible = await page.locator('button:has-text("下载 .svg")').isVisible().catch(() => false)
  check('下载 .svg 按钮可见', dlVisible)
  await closePreview()
} catch (e) {
  console.log('✗ 脚本异常:', e.message)
  fail.push('exception')
  await page.screenshot({ path: SHOTS + '/starmap-error.png' }).catch(() => {})
}

await browser.close()
console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
