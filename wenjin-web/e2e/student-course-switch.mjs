// 学生端多课程切换 — 真机快速验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起。数据:course5 软件工程(39节点/25题)、
//       course6 Java(33节点/0题)、course8 软件测试(42节点/0题)。
// 验证:①URL courseId → useStudentCourse → 地图按课程渲染(节点数随课程变);
//       ②写入 localStorage wj.student.currentCourseId;③TopBar 学生导航携带 courseId;
//       ④切课后各页重载对应课程数据(诊断卷有/无);⑤无课程时非地图页空态守卫(不回落课程1)。
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
await page.addInitScript(() => {
  localStorage.setItem('wj_user', JSON.stringify({ id: 2, role: 0, name: 'demo_student' }))
})

const fail = []
const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}

async function gotoMap(cid) {
  await page.goto(`${base}/map?courseId=${cid}`, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => {
    const d = window.__wjColorMap?.data?.value
    return d && Array.isArray(d.nodes) && d.nodes.length > 0
  }, { timeout: 12000 })
  return await page.evaluate(() => window.__wjColorMap.data.value.nodes.length)
}
const lsCourse = () => page.evaluate(() => localStorage.getItem('wj.student.currentCourseId'))

try {
  // ── ① course5:地图渲染 + localStorage ──
  const n5 = await gotoMap(5)
  check('course5 地图渲染 39 节点', n5 === 39, `n=${n5}`)
  check('course5 写入 localStorage=5', (await lsCourse()) === '5')
  await page.screenshot({ path: SHOTS + '/student-switch-c5.png' })

  // ── ② TopBar 学生导航携带 courseId=5 ──
  const diagHref = await page.locator('a', { hasText: '入口诊断' }).first().getAttribute('href')
  check('TopBar 入口诊断链接带 courseId=5', /courseId=5/.test(diagHref || ''), `href=${diagHref}`)

  // ── ③ 点击导航 → 诊断页带 courseId=5,course5 有 25 题(非空态) ──
  await page.locator('a', { hasText: '入口诊断' }).first().click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(900)
  check('诊断页 URL 带 courseId=5', /courseId=5/.test(page.url()), page.url())
  const diag5 = await page.textContent('body')
  check('course5 诊断卷非空(渲染出题目)', !diag5.includes('当前课程暂无可用题目'))

  // ── ④ 切 course6 → 节点数变 33,诊断空态 ──
  const n6 = await gotoMap(6)
  check('切 course6 地图渲染 33 节点', n6 === 33, `n=${n6}`)
  check('course5→6 节点数确实变化', n5 !== n6, `${n5} -> ${n6}`)
  check('course6 写入 localStorage=6', (await lsCourse()) === '6')
  await page.locator('a', { hasText: '入口诊断' }).first().click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(900)
  const diag6 = await page.textContent('body')
  check('course6 诊断卷空态提示', diag6.includes('当前课程暂无可用题目'))

  // ── ⑤ 切 course8 → 42 节点(三态分明) ──
  const n8 = await gotoMap(8)
  check('切 course8 地图渲染 42 节点', n8 === 42, `n=${n8}`)
  check('三课程节点数互异', new Set([n5, n6, n8]).size === 3, `${n5}/${n6}/${n8}`)
  await page.screenshot({ path: SHOTS + '/student-switch-c8.png' })

  // ── ⑥ 无课程空态守卫:清 localStorage + 无 query 进成长档案 → 不回落课程1 ──
  await page.evaluate(() => localStorage.removeItem('wj.student.currentCourseId'))
  await page.goto(base + '/growth', { waitUntil: 'networkidle' })
  await page.waitForTimeout(700)
  const growthData = await page.evaluate(() => {
    const d = window.__wjGrowth?.data?.value
    return d === null || d === undefined ? 'null' : 'loaded'
  })
  const growthBody = await page.textContent('body')
  check('无课程时成长档案未加载数据(不回落课程1)', growthData === 'null', `data=${growthData}`)
  check('无课程时给出选课引导', growthBody.includes('请先从首页选择'), )
} catch (e) {
  console.log('✗ 脚本异常:', e.message)
  fail.push('exception')
  await page.screenshot({ path: SHOTS + '/student-switch-error.png' }).catch(() => {})
}

await browser.close()
console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
