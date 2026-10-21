// 管理后台 A4 系统配置 + 数据维护 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;库已执行 V16–V18;有一个启用的管理员;本机有 mysql CLI。
// 账号:WJ_ADMIN_USER / WJ_ADMIN_PASS(默认读 ../docs/admin/local-admin.txt);demo_teacher/demo(越权探针)。
// 数据维护只对脚本自建的 e2e 课程操作;测试用的伴侣会话/记忆行由 mysql CLI 直接写入(WJ_MYSQL_ARGS 可覆盖连接参数)。
// 脚本结束前把改过的配置全部恢复默认。
import { chromium } from 'playwright'
import { mkdirSync, readFileSync, existsSync } from 'fs'
import { execFileSync } from 'child_process'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })
const MYSQL_ARGS = (process.env.WJ_MYSQL_ARGS || '-uroot -proot wenjin').split(/\s+/)
const sql = (q) => execFileSync('mysql', [...MYSQL_ARGS, '-N', '-e', q], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()

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
const FAKE_KEY = `sk-e2efake${sfx}WXYZ`
const K_MODEL = 'wenjin.ai.model'
const K_KEY = 'wenjin.ai.api-key'
const K_LLM = 'wenjin.features.companion-llm-enabled'
const tid = (s) => `[data-testid="${s}"]`

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
    const text = await res.text()
    let json = null
    try { json = JSON.parse(text) } catch { /* 非 JSON */ }
    return { status: res.status, body: json, text }
  }, { method, path, token, body })
}
const login = async (u, p) => (await api('POST', '/login', null, { username: u, password: p })).body?.data?.token
let at = null
const cfgItem = async (key) => {
  const groups = (await api('GET', '/system-admin/configs', at)).body?.data || []
  for (const g of groups) for (const it of g.items) if (it.key === key) return it
  return null
}

try {
  const admin = adminCreds()
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="如 2023302481"]', admin.username)
  await page.fill('input[type="password"]', admin.password)
  await page.click('button:has-text("登 录")')
  await page.waitForURL('**/admin/overview', { timeout: 10000 })
  at = await page.evaluate(() => localStorage.getItem('wj_token'))

  // ── 系统配置 ──
  await page.click(tid('admin-nav-settings'))
  await page.waitForURL('**/admin/settings')
  await page.waitForSelector(tid(`admin-config-${K_MODEL}`))
  check('配置页渲染 AI/开关/限额分组', (await page.locator('[data-testid^="admin-config-wenjin."]').count()) >= 20)
  const modelBefore = await cfgItem(K_MODEL)

  // 1. 改模型名 → 立即生效(测试连接回显的 model 即当前生效值)
  await page.fill(tid(`admin-config-input-${K_MODEL}`), `e2e-model-${sfx}`)
  await page.click(tid(`admin-config-save-${K_MODEL}`))
  await page.waitForSelector(tid(`admin-config-overridden-${K_MODEL}`))
  check('模型名保存后显示已覆盖', true)
  const it1 = await cfgItem(K_MODEL)
  check('接口返回新值且 overridden', it1.value === `e2e-model-${sfx}` && it1.overridden === true)

  // 2. 换一个假 key → 页面/接口/库里都不出现明文
  await page.fill(tid(`admin-config-input-${K_KEY}`), FAKE_KEY)
  await page.click(tid(`admin-config-save-${K_KEY}`))
  await page.waitForSelector(tid(`admin-config-overridden-${K_KEY}`))
  const keyItem = await cfgItem(K_KEY)
  check('secret 接口只给 configured+末4位', keyItem.configured === true && keyItem.masked === 'WXYZ' && keyItem.value == null)
  check('页面 HTML 不含明文 key', !(await page.content()).includes(FAKE_KEY))
  check('配置列表接口原文不含明文 key', !(await api('GET', '/system-admin/configs', at)).text.includes(FAKE_KEY))
  const row = sql(`SELECT encrypted, config_value FROM sys_config WHERE config_key='${K_KEY}'`)
  check('库里 encrypted=1 且不含明文', row.startsWith('1') && !row.includes(FAKE_KEY))
  check('页面显示 ••••WXYZ', (await page.textContent(tid(`admin-config-${K_KEY}`))).includes('••••WXYZ'))

  // 3. 测试连接:回显新模型名,错误里不泄露 key
  await page.click(tid('admin-ai-test-text'))
  await page.waitForSelector(tid('admin-ai-test-result-text'), { timeout: 30000 })
  await page.waitForFunction((sel) => !/测试中/.test(document.querySelector(sel)?.textContent || ''), `[data-testid="admin-ai-test-result-text"]`, { timeout: 30000 })
  const testRes = (await api('POST', '/system-admin/configs/ai-test', at, { target: 'text' }))
  check('测试连接回显当前生效模型', testRes.body?.data?.model === `e2e-model-${sfx}`, JSON.stringify(testRes.body?.data))
  check('假 key 测试失败且错误不含 key', testRes.body?.data?.ok === false && !testRes.text.includes(FAKE_KEY))
  const resultText = await page.textContent(tid('admin-ai-test-result-text'))
  check('页面测试结果不含 key', !resultText.includes(FAKE_KEY), resultText.slice(0, 80))
  await page.screenshot({ path: `${SHOTS}/admin-11-settings.png`, fullPage: false })

  // 4. 开关:关伴侣 LLM
  const llmInput = page.locator(tid(`admin-config-input-${K_LLM}`))
  await llmInput.click()
  await page.waitForTimeout(800)
  check('关闭伴侣 LLM 开关生效', (await cfgItem(K_LLM)).value === false)

  // 5. 校验:越界数字被拒
  const bad = await api('PUT', '/system-admin/configs/wenjin.limits.companion.max-recent-messages', at, { value: 99999 })
  check('越界数值被后端拒绝', bad.body?.code !== 0, bad.body?.message)
  const notWl = await api('PUT', '/system-admin/configs/spring.datasource.password', at, { value: 'x' })
  check('白名单外 key 被拒', notWl.body?.code !== 0, notWl.body?.message)

  // 6. 审计:有 CONFIG_UPDATE,target=key,且不含值
  const audits = (await api('GET', '/system-admin/audit-logs?action=CONFIG_UPDATE&size=20', at))
  check('审计记 CONFIG_UPDATE(target=key)', (audits.body?.data?.records || []).some((r) => r.targetId === K_KEY))
  check('审计接口原文不含 key 明文/模型值', !audits.text.includes(FAKE_KEY))

  // 7. 全部恢复默认(UI 点一个,其余 API)
  await page.click(tid(`admin-config-reset-${K_MODEL}`))
  await page.waitForTimeout(800)
  for (const k of [K_KEY, K_LLM]) await api('DELETE', `/system-admin/configs/${k}`, at)
  const mAfter = await cfgItem(K_MODEL)
  check('恢复默认后值回到启动默认', mAfter.overridden === false && mAfter.value === modelBefore.value)
  check('恢复后 key/开关不再覆盖', !(await cfgItem(K_KEY)).overridden && !(await cfgItem(K_LLM)).overridden)
  check('库中覆盖已清空', sql(`SELECT COUNT(*) FROM sys_config WHERE config_key IN ('${K_MODEL}','${K_KEY}','${K_LLM}')`) === '0')

  // ── 数据维护 ──
  const stu = (await api('POST', '/system-admin/users', at, { username: `e2e_rs_${sfx}`, password: PW, realName: '重置验收', role: 2 })).body.data
  const t = (await api('GET', '/system-admin/teachers', at)).body.data[0]
  const mkCourse = async (name) => (await api('POST', '/system-admin/courses', at, { name, teacherId: t.id })).body.data
  const c1 = await mkCourse(`e2e重置课A_${sfx}`)
  const c2 = await mkCourse(`e2e重置课B_${sfx}`)
  const cl = (await api('POST', '/system-admin/clazzes', at, { name: `e2e重置班_${sfx}` })).body.data
  await api('POST', `/system-admin/clazzes/${cl.id}/students`, at, { usernames: [stu.username] })
  await api('POST', `/system-admin/clazzes/${cl.id}/courses`, at, { courseId: c1.id })
  await api('POST', `/system-admin/clazzes/${cl.id}/courses`, at, { courseId: c2.id })
  for (const c of [c1, c2]) {
    sql(`INSERT INTO companion_conversation(student_id, course_id) VALUES (${stu.id}, ${c.id})`)
    sql(`INSERT INTO student_memory(student_id, course_id, kind, content) VALUES (${stu.id}, ${c.id}, 0, 'e2e')`)
  }
  const cnt = (table, cid) => sql(`SELECT COUNT(*) FROM ${table} WHERE student_id=${stu.id} AND course_id=${cid}`)
  check('造数:两门课各 1 会话 1 记忆', cnt('companion_conversation', c1.id) === '1' && cnt('student_memory', c2.id) === '1')

  await page.click(tid('admin-nav-maintenance'))
  await page.waitForURL('**/admin/maintenance')
  await page.waitForFunction((id) => !!document.querySelector(`[data-testid="admin-maint-course"] option[value="${id}"]`), String(c1.id), { timeout: 10000 })
  await page.selectOption(tid('admin-maint-course'), String(c1.id))
  await page.fill(tid('admin-maint-confirm'), c1.name + 'x')
  check('确认文本不符时按钮禁用', await page.isDisabled(tid('admin-maint-submit')))
  await page.fill(tid('admin-maint-confirm'), c1.name)
  check('确认文本一致时按钮可用', !(await page.isDisabled(tid('admin-maint-submit'))))
  await page.click(tid('admin-maint-submit'))
  await page.waitForSelector(tid('admin-maint-result'), { timeout: 15000 })
  await page.screenshot({ path: `${SHOTS}/admin-12-maintenance.png` })
  check('结果表含伴侣会话行', (await page.locator('[data-testid="admin-maint-result-row-companion_conversation"]').count()) === 1)
  check('目标课程学习数据已清空', cnt('companion_conversation', c1.id) === '0' && cnt('student_memory', c1.id) === '0')
  check('另一门课不受影响', cnt('companion_conversation', c2.id) === '1' && cnt('student_memory', c2.id) === '1')
  check('选课关系保留', sql(`SELECT COUNT(*) FROM student_course WHERE student_id=${stu.id} AND course_id=${c1.id}`) === '1')
  const wrongConfirm = await api('POST', '/system-admin/data-reset', at, { courseId: c2.id, confirmText: '随便写' })
  check('接口确认文本不符被拒', wrongConfirm.body?.code !== 0 && cnt('companion_conversation', c2.id) === '1', wrongConfirm.body?.message)
  const audR = (await api('GET', '/system-admin/audit-logs?action=DATA_RESET&size=5', at)).body?.data?.records || []
  check('审计记 DATA_RESET(target=课程id)', audR.some((r) => r.targetId === String(c1.id) && r.success))

  // ── 越权 ──
  const dt = await login('demo_teacher', 'demo')
  if (dt) {
    check('教师读配置 403', (await api('GET', '/system-admin/configs', dt)).status === 403)
    check('教师调数据重置 403', (await api('POST', '/system-admin/data-reset', dt, { courseId: c2.id, confirmText: c2.name })).status === 403)
  }
} catch (e) {
  console.error('脚本异常:', e)
  fail.push('exception')
  await page.screenshot({ path: `${SHOTS}/admin-a4-ERR.png` }).catch(() => {})
} finally {
  // 兜底:无论成败都把本脚本可能改过的配置恢复默认
  if (at) for (const k of [K_MODEL, K_KEY, K_LLM]) await api('DELETE', `/system-admin/configs/${k}`, at).catch(() => {})
  await browser.close()
}
console.log(fail.length ? `\nFAIL ${fail.length}: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
