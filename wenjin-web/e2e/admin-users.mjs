// 管理后台 A0+A1 用户管理 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;库里有一个启用的管理员。
// 账号:WJ_ADMIN_USER / WJ_ADMIN_PASS(默认读 ../docs/admin/local-admin.txt);demo_teacher/demo(教师,用于越权探针)。
// 导入用 xlsx 由本脚本调 python(openpyxl)按唯一后缀现生成,便于重复运行。
// 注意:脚本建的 e2e_* 用户会留在库里(系统不提供删除用户)。
import { chromium } from 'playwright'
import { mkdirSync, readFileSync, existsSync } from 'fs'
import { execFileSync } from 'child_process'
import { tmpdir } from 'os'
import { join } from 'path'

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
const xlsxDir = process.env.WJ_TMP || tmpdir()
function makeXlsx(file, rows) {
  const py = `
import json, sys
from openpyxl import Workbook
wb = Workbook(); ws = wb.active
ws.append(["用户名","姓名","角色","初始密码"])
for r in json.loads(sys.argv[2]): ws.append(r)
wb.save(sys.argv[1])`
  execFileSync('python', ['-I', '-c', py, file, JSON.stringify(rows)])
}
const PW = 'Passw0rd!x'
const impS1 = `e2e_imp_s1_${sfx}`
const impT1 = `e2e_imp_t1_${sfx}`
const impS2 = `e2e_imp_s2_${sfx}`
const badXlsx = join(xlsxDir, `wj-import-bad-${sfx}.xlsx`)
const goodXlsx = join(xlsxDir, `wj-import-good-${sfx}.xlsx`)
makeXlsx(badXlsx, [[impS1, '导入学生一', '学生', PW], [impT1, '导入教师一', '教师', PW], [impS1, '重复行', '学生', PW], [impS2, '短密码', '学生', '123']])
makeXlsx(goodXlsx, [[impS1, '导入学生一', '学生', PW], [impT1, '导入教师一', '教师', PW], [impS2, '导入学生二', '2', PW]])

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('dialog', (d) => d.accept())
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
    const ct = res.headers.get('content-type') || ''
    const json = ct.includes('json') ? await res.json().catch(() => null) : null
    return { status: res.status, body: json, contentType: ct }
  }, { method, path, token, body })
}
const login = (username, password) => api('POST', '/login', null, { username, password })
async function findUser(adminToken, username) {
  const r = await api('GET', '/system-admin/users?keyword=' + encodeURIComponent(username), adminToken)
  return (r.body?.data?.records || []).find((u) => u.username === username)
}

try {
  const admin = adminCreds()
  await page.goto(base, { waitUntil: 'networkidle' })

  // 1. 管理员 UI 登录 → 落地后台(A2 起默认是平台总览) → 侧栏进用户管理
  await page.fill('input[placeholder="如 2023302481"]', admin.username)
  await page.fill('input[type="password"]', admin.password)
  await page.click('button:has-text("登 录")')
  await page.waitForURL('**/admin/overview', { timeout: 10000 })
  await page.click('[data-testid="admin-nav-users"]')
  await page.waitForURL('**/admin/users', { timeout: 10000 })
  await page.waitForSelector('[data-testid="admin-users-table"]')
  check('管理员登录进后台,侧栏可进 /admin/users', page.url().endsWith('/admin/users'))
  check('后台顶栏显示管理员姓名', (await page.textContent('[data-testid="admin-name"]'))?.trim().length > 0)
  check('无学生/教师 TopBar(无「染色地图」导航)', (await page.locator('text=染色地图').count()) === 0)
  const adminToken = await page.evaluate(() => localStorage.getItem('wj_token'))
  await page.screenshot({ path: `${SHOTS}/admin-01-users.png` })

  // 2. UI 新建教师
  const tName = `e2e_t_${sfx}`
  await page.click('[data-testid="admin-user-create"]')
  await page.fill('[data-testid="admin-user-create-username"]', tName)
  await page.fill('[data-testid="admin-user-create-realname"]', 'E2E 教师')
  await page.fill('[data-testid="admin-user-create-password"]', PW)
  await page.selectOption('[data-testid="admin-user-create-role"]', '1')
  await page.click('[data-testid="admin-user-create-submit"]')
  await page.waitForTimeout(800)
  const t = await findUser(adminToken, tName)
  check('新建教师落库且角色=1', t && t.role === 1 && t.status === 1)
  await page.fill('[data-testid="admin-users-keyword"]', tName)
  await page.click('[data-testid="admin-users-search"]')
  await page.waitForSelector(`[data-testid="admin-user-row-${t.id}"]`)
  check('列表搜索到新建教师', true)

  // 重名
  const dup = await api('POST', '/system-admin/users', adminToken, { username: tName, password: PW, realName: 'x', role: 2 })
  check('重名创建被拒', dup.body?.code !== 0, `http=${dup.status} code=${dup.body?.code}`)

  // 3. 教师登录拿令牌
  const tl = await login(tName, PW)
  const tToken = tl.body?.data?.token
  check('新教师可登录', !!tToken)
  check('新教师 /me 200', (await api('GET', '/me', tToken)).status === 200)

  // 4. UI 禁用 → 已登录令牌立即 401、再登录失败
  await page.click(`[data-testid="admin-user-toggle-${t.id}"]`)
  await page.waitForTimeout(800)
  check('状态标签变禁用', (await page.textContent(`[data-testid="admin-user-status-tag-${t.id}"]`)).includes('禁用'))
  const meAfter = await api('GET', '/me', tToken)
  check('禁用后旧令牌 /me 401', meAfter.status === 401, `http=${meAfter.status}`)
  const tl2 = await login(tName, PW)
  check('禁用后登录失败', !tl2.body?.data?.token, tl2.body?.message)

  // 禁用自己被拒
  const me = (await api('GET', '/me', adminToken)).body.data
  const selfDis = await api('PATCH', `/system-admin/users/${me.id}/status`, adminToken, { status: 0 })
  check('不能禁用自己', selfDis.body?.code !== 0, selfDis.body?.message)

  // 5. UI 启用
  await page.click(`[data-testid="admin-user-toggle-${t.id}"]`)
  await page.waitForTimeout(800)
  check('启用后可登录', !!(await login(tName, PW)).body?.data?.token)

  // 6. UI 重置密码(留空=自动生成,只显示一次)
  await page.click(`[data-testid="admin-user-reset-${t.id}"]`)
  await page.click('[data-testid="admin-user-reset-submit"]')
  await page.waitForSelector('[data-testid="admin-user-reset-generated"]')
  const newPw = (await page.textContent('[data-testid="admin-user-reset-generated"]')).trim()
  await page.screenshot({ path: `${SHOTS}/admin-02-reset.png` })
  check('生成的新密码长度 12', newPw.length === 12, `len=${newPw.length}`)
  check('新密码可登录', !!(await login(tName, newPw)).body?.data?.token)
  check('旧密码失效', !(await login(tName, PW)).body?.data?.token)
  await page.keyboard.press('Escape')

  // 7. UI 改角色 教师→学生
  await page.click(`[data-testid="admin-user-role-${t.id}"]`)
  await page.waitForTimeout(800)
  check('角色标签变学生', (await page.textContent(`[data-testid="admin-user-role-tag-${t.id}"]`)).includes('学生'))

  // 8. 模板下载
  const tpl = await page.evaluate(async (tk) => {
    const r = await fetch('/api/system-admin/users/import-template', { headers: { Authorization: 'Bearer ' + tk } })
    return { status: r.status, ct: r.headers.get('content-type'), size: (await r.arrayBuffer()).byteLength }
  }, adminToken)
  check('导入模板可下载(xlsx)', tpl.status === 200 && /spreadsheet|octet/.test(tpl.ct || '') && tpl.size > 1000, JSON.stringify(tpl))

  // 9. UI 导入含错误行 → 全部不写入
  await page.click('[data-testid="admin-user-import"]')
  await page.setInputFiles('[data-testid="admin-user-import-file"]', badXlsx)
  await page.click('[data-testid="admin-user-import-submit"]')
  await page.waitForSelector('[data-testid="admin-user-import-errors"]')
  const errRows = await page.locator('[data-testid="admin-user-import-error-row"]').count()
  await page.screenshot({ path: `${SHOTS}/admin-03-import-errors.png` })
  check('错误行展示(重复+短密码=2)', errRows === 2, `rows=${errRows}`)
  check('含错误时一行不写入', !(await findUser(adminToken, impS1)))

  // 10. 修正后导入成功
  await page.setInputFiles('[data-testid="admin-user-import-file"]', goodXlsx)
  await page.click('[data-testid="admin-user-import-submit"]')
  await page.waitForSelector('[data-testid="admin-user-import-success"]')
  const s1 = await findUser(adminToken, impS1)
  const t1 = await findUser(adminToken, impT1)
  const s2 = await findUser(adminToken, impS2)
  check('合法文件导入 3 人且角色正确', s1?.role === 2 && t1?.role === 1 && s2?.role === 2)
  check('导入用户可登录', !!(await login(impS1, PW)).body?.data?.token)
  await page.keyboard.press('Escape')

  // 11. 越权探针
  const dt = (await login('demo_teacher', 'demo')).body?.data?.token
  if (dt) {
    const f = await api('GET', '/system-admin/users', dt)
    check('教师调管理接口 403', f.status === 403, `http=${f.status}`)
  } else {
    console.log('  (跳过教师探针:demo_teacher/demo 登录失败)')
  }
  const anon = await api('GET', '/system-admin/users', null)
  check('未登录调管理接口 401', anon.status === 401, `http=${anon.status}`)

  // 12. 退出 → 回登录页;教师 UI 直访 /admin 被踢回
  await page.click('[data-testid="admin-logout"]')
  await page.waitForURL(base + '/', { timeout: 5000 })
  check('退出回登录页', true)
  const s2Token = (await login(impS2, PW)).body?.data
  await page.evaluate((d) => { localStorage.setItem('wj_token', d.token); localStorage.setItem('wj_user', JSON.stringify(d.user)) }, s2Token)
  await page.goto(base + '/admin/users', { waitUntil: 'networkidle' })
  check('学生直访 /admin 被踢回 /', new URL(page.url()).pathname === '/', page.url())
} catch (e) {
  console.error('脚本异常:', e)
  fail.push('exception')
  await page.screenshot({ path: `${SHOTS}/admin-ERR.png` }).catch(() => {})
} finally {
  await browser.close()
}
console.log(fail.length ? `\nFAIL ${fail.length}: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
