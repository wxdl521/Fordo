// 老师端图谱「多课程切换」真机冒烟验收（Playwright + 系统 Edge，headless）
// 前置：后端 8080 已起、vite dev 5173 已起、DB 中 demo_teacher(id=1,role=1) 存在
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = 'e2e/multi-shots'
mkdirSync(SHOTS, { recursive: true })

// 唯一课程名，避免历次残留导致 selectOption label 歧义
const UNIQUE = '验收课' + Date.now().toString().slice(-6)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
const log = (...a) => console.log(...a)
const fail = []

function check(name, cond) {
  if (cond) { log(`✓ ${name}`) } else { log(`✗ ${name}`); fail.push(name) }
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
  const selectCount = await page.locator('select option').count()
  const defaultName = await page.locator('select option:checked').first().textContent().then(t => t?.trim()).catch(() => null)
  await page.screenshot({ path: `${SHOTS}/1-enter-default.png`, fullPage: true })
  check('进入图谱页有课程下拉且默认选软件工程', selectCount >= 1 && defaultName?.includes('软件工程'))

  // 2. 点「+ 新增课程」→ 输入名称 → 创建 → 自动切到新课程、图谱为空态
  await page.click('button:has-text("+ 新增课程")')
  await page.waitForTimeout(400)
  await page.fill('input[placeholder="例：软件工程"]', UNIQUE)
  await page.click('button:has-text("创建")')
  await page.waitForTimeout(2500)
  const afterCreate = await page.locator('select option:checked').first().textContent().then(t => t?.trim()).catch(() => null)
  await page.screenshot({ path: `${SHOTS}/2-created-empty.png`, fullPage: true })
  check('创建后自动切换到新课程', afterCreate === UNIQUE)

  // 3. 切换：先切回软件工程，再切回新课程，验证下拉切换会重载图谱
  await page.selectOption('select', { label: '软件工程' })
  await page.waitForTimeout(2500)
  const switchedBack = await page.locator('select option:checked').first().textContent().then(t => t?.trim()).catch(() => null)
  await page.screenshot({ path: `${SHOTS}/3-switch-back.png`, fullPage: true })
  check('切回软件工程成功', switchedBack?.includes('软件工程'))

  await page.selectOption('select', { label: UNIQUE })
  await page.waitForTimeout(2500)
  const switchedFwd = await page.locator('select option:checked').first().textContent().then(t => t?.trim()).catch(() => null)
  check('再切回新课程成功', switchedFwd === UNIQUE)

  // 4. 删除当前课程 → 确认 → 课程消失，切到下一门
  page.once('dialog', d => d.accept())   // 自动确认删除二次弹窗
  await page.click('button:has-text("删除当前课程")')
  await page.waitForTimeout(2500)
  const afterDel = await page.locator('select option:checked').first().textContent().then(t => t?.trim()).catch(() => null)
  const remaining = await page.locator('select option').count()
  await page.screenshot({ path: `${SHOTS}/4-after-delete.png`, fullPage: true })
  check('删除后切回其它课程', remaining >= 1 && afterDel?.includes('软件工程'))
  check('新课程已从下拉消失', (await page.locator('select option', { hasText: UNIQUE }).count()) === 0)

  // 5. 鉴权探针：未带/错误 X-User-Id 时 /api/teacher/courses 返回 401/403
  const noId = await page.evaluate(async () => {
    const r = await fetch('/api/teacher/courses')
    return await r.json()
  })
  const badId = await page.evaluate(async () => {
    const r = await fetch('/api/teacher/courses', { headers: { 'X-User-Id': 'abc' } })
    return await r.json()
  })
  const student = await page.evaluate(async () => {
    const r = await fetch('/api/teacher/courses', { headers: { 'X-User-Id': '2' } })
    return await r.json()
  })
  check('无身份头 → 401', noId?.code === 401)
  check('非数字身份头 → 401', badId?.code === 401)
  check('学生身份 → 403', student?.code === 403)

  console.log('\n课程名(本次):', UNIQUE)
  log((fail.length === 0 ? '✓ 全部验收通过' : `✗ 失败项 ${fail.length}: ${fail.join(', ')}`))
} catch (err) {
  console.error('验收脚本异常:', err)
  process.exit(1)
} finally {
  await browser.close()
}
process.exit(fail.length === 0 ? 0 : 1)
