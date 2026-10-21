// 管理后台 A2 审计与监控 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;库已执行 V16__audit.sql;有一个启用的管理员。
// 账号:WJ_ADMIN_USER / WJ_ADMIN_PASS(默认读 ../docs/admin/local-admin.txt);demo_teacher/demo(越权探针)。
// 覆盖:总览落地+数字与列表一致+图表渲染、登录日志失败原因、操作日志 target_id、
//       匿名不记/越权记失败、重置密码吊销旧令牌。脚本建的 e2e_* 用户会留在库里。
import { chromium } from 'playwright'
import { mkdirSync, readFileSync, existsSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

function adminCreds() {
  if (process.env.WJ_ADMIN_USER && process.env.WJ_ADMIN_PASS) {
    return { username: process.env.WJ_ADMIN_USER, password: process.env.WJ_ADMIN_PASS }
  }
  const f = '../docs/admin/local-admin.txt'
  if (!existsSync(f)) throw new Error('缺管理员凭据:设 WJ_ADMIN_USER/WJ_ADMIN_PASS 或提供 ' + f)
  const kv = Object.fromEntries(readFileSync(f, 'utf8').split(/\r?\n/).filter((l) => l.includes('=')).map((l) => l.split('=')))
  return { username: kv.username.trim(), password: kv.password.trim() }
}

const sfx = Date.now().toString(36)
const PW = 'Passw0rd!x'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('dialog', (d) => d.accept())
// 只把"非预期"的失败算作问题:脚本故意发的匿名/吊销探针会产生 401/403,不计
const badResponses = []
page.on('response', (r) => {
  const st = r.status()
  if (st >= 400 && st !== 401 && st !== 403) badResponses.push(`${st} ${r.request().method()} ${r.url()}`)
})
const consoleErrors = []
// 项目本身没有 favicon(既有现状,与后台无关),浏览器自动请求的 404 不计
page.on('console', (m) => {
  if (m.type() !== 'error' || /status of 40[13]/.test(m.text()) || /favicon/.test(m.location()?.url || '')) return
  consoleErrors.push(m.text())
})
const fail = []
const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}

async function api(method, path, token, body) {
  return await page.evaluate(async ({ method, path, token, body }) => {
    const res = await fetch('/api' + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token != null ? { Authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined
    })
    const json = await res.json().catch(() => null)
    return { status: res.status, body: json }
  }, { method, path, token, body })
}
const login = (username, password) => api('POST', '/login', null, { username, password })
const cardValue = async (id) => (await page.textContent(`[data-testid="admin-overview-card-${id}-value"]`))?.replace(/[^\d]/g, '')

try {
  const admin = adminCreds()
  await page.goto(base, { waitUntil: 'networkidle' })

  // 1. 管理员登录 → 落地平台总览
  await page.fill('input[placeholder="如 2023302481"]', admin.username)
  await page.fill('input[type="password"]', admin.password)
  await page.click('button:has-text("登 录")')
  await page.waitForURL('**/admin/overview', { timeout: 10000 })
  await page.waitForSelector('[data-testid="admin-overview-card-users-total-value"]')
  check('管理员登录落地 /admin/overview', true)
  const adminToken = await page.evaluate(() => localStorage.getItem('wj_token'))
  const adminMe = (await api('GET', '/me', adminToken)).body.data

  // 2. 总览数字与用户列表一致
  const allUsers = (await api('GET', '/system-admin/users?size=1', adminToken)).body.data.total
  const teachers = (await api('GET', '/system-admin/users?size=1&role=1', adminToken)).body.data.total
  const disabled = (await api('GET', '/system-admin/users?size=1&status=0', adminToken)).body.data.total
  check('用户总数卡=列表总数', Number(await cardValue('users-total')) === allUsers, `${await cardValue('users-total')} vs ${allUsers}`)
  check('教师卡=列表教师数', Number(await cardValue('users-teachers')) === teachers)
  check('禁用卡=列表禁用数', Number(await cardValue('users-disabled')) === disabled)
  await page.waitForTimeout(1500)
  check('登录趋势图已渲染 canvas', (await page.locator('[data-testid="admin-overview-chart-activity"] canvas').count()) > 0)
  check('AI 趋势图已渲染 canvas', (await page.locator('[data-testid="admin-overview-chart-ai"] canvas').count()) > 0)
  const ov = (await api('GET', '/system-admin/overview?days=14', adminToken)).body.data
  check('overview days=14 返回 14 天且日期升序', ov.activity.daily.length === 14 && ov.ai.daily.length === 14 &&
    ov.activity.daily.every((d, i, a) => i === 0 || a[i - 1].date < d.date))
  check('今日活跃≥1(管理员刚登录)', ov.activity.todayActiveUsers >= 1)
  await page.click('[data-testid="admin-overview-days-30"]')
  await page.waitForTimeout(800)
  await page.screenshot({ path: `${SHOTS}/admin-04-overview.png`, fullPage: true })

  // 3. 造登录失败:密码错 + 禁用
  const uName = `e2e_mon_${sfx}`
  const created = await api('POST', '/system-admin/users', adminToken, { username: uName, password: PW, realName: '监控验收', role: 2 })
  const uid = created.body?.data?.id
  check('建测试学生', !!uid)
  await login(uName, 'wrong-password')
  await api('PATCH', `/system-admin/users/${uid}/status`, adminToken, { status: 0 })
  await login(uName, PW)
  await api('PATCH', `/system-admin/users/${uid}/status`, adminToken, { status: 1 })

  // 4. 登录日志页
  await page.click('[data-testid="admin-nav-login-logs"]')
  await page.waitForURL('**/admin/login-logs')
  await page.fill('[data-testid="admin-login-username"]', uName)
  await page.click('[data-testid="admin-login-search"]')
  await page.waitForTimeout(800)
  const loginRows = page.locator('[data-testid^="admin-login-row-"]')
  const reasons = await page.locator('[data-testid^="admin-login-reason-"]').allTextContents()
  check('登录日志 2 条失败', (await loginRows.count()) === 2, `rows=${await loginRows.count()}`)
  check('失败原因中文:密码错误+已禁用', reasons.some((r) => r.includes('用户名或密码错误')) && reasons.some((r) => r.includes('禁用')), reasons.join(' | '))
  await page.screenshot({ path: `${SHOTS}/admin-05-login-logs.png` })

  // 5. 操作日志页:USER_STATUS target_id = uid
  await page.click('[data-testid="admin-nav-audit-logs"]')
  await page.waitForURL('**/admin/audit-logs')
  await page.fill('[data-testid="admin-audit-operator"]', adminMe.username)
  await page.selectOption('[data-testid="admin-audit-action"]', 'USER_STATUS')
  await page.click('[data-testid="admin-audit-search"]')
  await page.waitForTimeout(800)
  const auditApi = (await api('GET', `/system-admin/audit-logs?action=USER_STATUS&size=10`, adminToken)).body.data.records
  const mine = auditApi.filter((r) => r.targetId === String(uid))
  check('USER_STATUS 记 2 条且 target_id 正确', mine.length === 2 && mine.every((r) => r.success && r.operatorName === adminMe.username && r.targetType === 'USER'))
  check('操作日志页显示中文动作', (await page.locator('[data-testid^="admin-audit-action-"]').first().textContent()).includes('状态') ||
    (await page.locator('[data-testid^="admin-audit-action-"]').first().textContent()).includes('禁用') ||
    (await page.locator('[data-testid^="admin-audit-action-"]').first().textContent()).includes('启用'), await page.locator('[data-testid^="admin-audit-action-"]').first().textContent())
  await page.screenshot({ path: `${SHOTS}/admin-06-audit-logs.png` })

  // 6. 匿名不记;教师越权记失败
  const before = (await api('GET', '/system-admin/audit-logs?action=USER_STATUS&size=1', adminToken)).body.data.total
  const anon = await api('PATCH', `/system-admin/users/${uid}/status`, null, { status: 0 })
  check('匿名写接口 401', anon.status === 401)
  const afterAnon = (await api('GET', '/system-admin/audit-logs?action=USER_STATUS&size=1', adminToken)).body.data.total
  check('匿名请求不落审计', afterAnon === before, `${before}→${afterAnon}`)
  const dt = (await login('demo_teacher', 'demo')).body?.data?.token
  if (dt) {
    const forb = await api('PATCH', `/system-admin/users/${uid}/status`, dt, { status: 0 })
    check('教师越权 403', forb.status === 403)
    const rec = (await api('GET', '/system-admin/audit-logs?action=USER_STATUS&success=0&size=1', adminToken)).body.data.records[0]
    check('越权记失败审计(403,操作人=demo_teacher)', rec && rec.httpStatus === 403 && rec.operatorName === 'demo_teacher' && rec.success === false)
  } else {
    console.log('  (跳过越权探针:demo_teacher/demo 登录失败)')
  }

  // 7. 重置密码吊销旧令牌
  const oldTok = (await login(uName, PW)).body.data.token
  check('重置前旧令牌 /me 200', (await api('GET', '/me', oldTok)).status === 200)
  const reset = await api('POST', `/system-admin/users/${uid}/reset-password`, adminToken, {})
  const newPw = reset.body?.data?.password
  check('重置返回生成密码', !!newPw)
  check('重置后旧令牌 /me 401', (await api('GET', '/me', oldTok)).status === 401)
  const newTok = (await login(uName, newPw)).body?.data?.token
  check('新密码登录令牌 /me 200', newTok && (await api('GET', '/me', newTok)).status === 200)
  check('管理员自身令牌不受影响', (await api('GET', '/me', adminToken)).status === 200)

  // 8. 暗色主题截图
  await page.click('[data-testid="admin-nav-overview"]')
  await page.waitForURL('**/admin/overview')
  await page.click('text=墨夜').catch(() => {})
  await page.waitForTimeout(1200)
  await page.screenshot({ path: `${SHOTS}/admin-07-overview-dark.png`, fullPage: true })
  check('无非预期的失败请求', badResponses.length === 0, badResponses.slice(0, 5).join(' | '))
  check('页面无非预期控制台错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
} catch (e) {
  console.error('脚本异常:', e)
  fail.push('exception')
  await page.screenshot({ path: `${SHOTS}/admin-monitor-ERR.png` }).catch(() => {})
} finally {
  await browser.close()
}
console.log(fail.length ? `\nFAIL ${fail.length}: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
