// 老师端图谱「多课程切换」真机冒烟验收（Playwright + 系统 Edge，headless）
// 前置：后端 8080 已起、vite dev 5173 已起、DB 中 demo_teacher(id=1,role=1) 存在
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = 'e2e/multi-shots'
mkdirSync(SHOTS, { recursive: true })

// 唯一课程名，避免历次残留导致切换歧义
const UNIQUE = '验收课' + Date.now().toString().slice(-6)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
const log = (...a) => console.log(...a)
const fail = []

function check(name, cond) {
  if (cond) { log(`✓ ${name}`) } else { log(`✗ ${name}`); fail.push(name) }
}

// ── 自定义课程下拉的操作助手（已替代原生 <select>）──
const courseLabel = () =>
  page.locator('[data-testid=course-switch]').textContent().then(t => t?.trim()).catch(() => null)

async function openCourseMenu() {
  await page.click('[data-testid=course-switch]')
  await page.waitForTimeout(200)
}
async function closeMenu() {
  await page.keyboard.press('Escape')
  await page.waitForTimeout(150)
}
async function courseCount() {
  await openCourseMenu()
  const n = await page.locator('[data-testid=course-menu] > div').count()
  await closeMenu()
  return n
}
async function switchTo(label) {
  await openCourseMenu()
  await page.click(`[data-testid=course-menu] >> text=${label}`)
  await page.waitForTimeout(2500)
}

try {
  // 0. 模拟登录：demo_teacher(id=1, role=1) 写入 localStorage.wj_user
  await page.goto(base + '/', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => {
    localStorage.setItem('wj_user', JSON.stringify({ id: 1, username: 'demo_teacher', role: 1 }))
  })

  // 1. 进图谱页，应看到课程下拉，默认选中「软件工程」
  await page.goto(base + '/teacher/graph', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(3500)
  const selectCount = await courseCount()
  const defaultName = await courseLabel()
  await page.screenshot({ path: `${SHOTS}/1-enter-default.png`, fullPage: true })
  check('进入图谱页有课程下拉且默认选软件工程', selectCount >= 1 && defaultName?.includes('软件工程'))

  // 2. 「课程管理 ▾」→「+ 新增课程」→ 输入名称 → 创建 → 自动切到新课程、图谱为空态
  await page.click('button:has-text("课程管理")')
  await page.waitForTimeout(200)
  await page.click('text=+ 新增课程')
  await page.waitForTimeout(400)
  await page.fill('input[placeholder="例：软件工程"]', UNIQUE)
  await page.click('button:has-text("创建")')
  await page.waitForTimeout(2500)
  const afterCreate = await courseLabel()
  await page.screenshot({ path: `${SHOTS}/2-created-empty.png`, fullPage: true })
  check('创建后自动切换到新课程', afterCreate?.includes(UNIQUE))

  // 3. 切换：先切回软件工程，再切回新课程，验证下拉切换会重载图谱
  await switchTo('软件工程')
  const switchedBack = await courseLabel()
  await page.screenshot({ path: `${SHOTS}/3-switch-back.png`, fullPage: true })
  check('切回软件工程成功', switchedBack?.includes('软件工程'))

  await switchTo(UNIQUE)
  const switchedFwd = await courseLabel()
  check('再切回新课程成功', switchedFwd?.includes(UNIQUE))

  // 4. 「课程管理 ▾」→「删除当前课程」→ 确认 → 课程消失，切到下一门
  await page.click('button:has-text("课程管理")')
  await page.waitForTimeout(200)
  page.once('dialog', d => d.accept())   // 自动确认删除二次弹窗
  await page.click('text=删除当前课程')
  await page.waitForTimeout(2500)
  const afterDel = await courseLabel()
  await openCourseMenu()
  const remaining = await page.locator('[data-testid=course-menu] > div').count()
  const uniqueGone = (await page.locator('[data-testid=course-menu]').getByText(UNIQUE).count()) === 0
  await closeMenu()
  await page.screenshot({ path: `${SHOTS}/4-after-delete.png`, fullPage: true })
  check('删除后切回其它课程', remaining >= 1 && afterDel?.includes('软件工程'))
  check('新课程已从下拉消失', uniqueGone)

  // 5. 鉴权探针：Bearer 令牌模型 → /api/teacher/courses 返回 401/403
  const noToken = await page.evaluate(async () => {
    const r = await fetch('/api/teacher/courses')
    return await r.json()
  })
  const forgedToken = await page.evaluate(async () => {
    const r = await fetch('/api/teacher/courses', { headers: { Authorization: 'Bearer forged.token.value' } })
    return await r.json()
  })
  const studentToken = await page.evaluate(async () => {
    const loginResp = await (await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'demo_student', password: 'demo' })
    })).json()
    const token = loginResp?.data?.token
    const r = await fetch('/api/teacher/courses', { headers: { Authorization: 'Bearer ' + token } })
    return await r.json()
  })
  check('无令牌 → 401', noToken?.code === 401)
  check('伪造令牌 → 401', forgedToken?.code === 401)
  check('学生令牌 → 403', studentToken?.code === 403)

  console.log('\n课程名(本次):', UNIQUE)
  log((fail.length === 0 ? '✓ 全部验收通过' : `✗ 失败项 ${fail.length}: ${fail.join(', ')}`))
} catch (err) {
  console.error('验收脚本异常:', err)
  process.exit(1)
} finally {
  await browser.close()
}
process.exit(fail.length === 0 ? 0 : 1)
