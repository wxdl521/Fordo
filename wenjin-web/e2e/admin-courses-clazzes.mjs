// 管理后台 A3 课程与班级 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;库已执行 V16、V17;有一个启用的管理员。
// 账号:WJ_ADMIN_USER / WJ_ADMIN_PASS(默认读 ../docs/admin/local-admin.txt);demo_teacher/demo(越权探针)。
// 覆盖:管理员建课(指定教师)/发布/转让/归档恢复、建班级、粘贴用户名加学生(含不存在)、
//       关联课程即批量选课、转班、解除/删班不删选课、审计留痕、越权 403。
// 脚本建的 e2e_* 用户/课程/班级会留在库里。
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
const badResponses = []
page.on('response', (r) => {
  const st = r.status()
  // 故意发的反例探针带 X-E2E-Probe 头,不计
  if (st >= 400 && st !== 401 && st !== 403 && !r.request().headers()['x-e2e-probe']) badResponses.push(`${st} ${r.request().method()} ${r.url()}`)
})
const fail = []
const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}

async function api(method, path, token, body, probe = false) {
  return await page.evaluate(async ({ method, path, token, body, probe }) => {
    const res = await fetch('/api' + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token != null ? { Authorization: 'Bearer ' + token } : {}), ...(probe ? { 'X-E2E-Probe': '1' } : {}) },
      body: body ? JSON.stringify(body) : undefined
    })
    const json = await res.json().catch(() => null)
    return { status: res.status, body: json }
  }, { method, path, token, body, probe })
}
const login = async (u, p) => (await api('POST', '/login', null, { username: u, password: p })).body?.data?.token
const myCourseIds = async (tok) => ((await api('GET', '/course/my', tok)).body?.data || []).map((c) => c.courseId)
const teacherCourseIds = async (tok) => ((await api('GET', '/teacher/courses', tok)).body?.data || []).map((c) => c.id)

try {
  const admin = adminCreds()
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="如 2023302481"]', admin.username)
  await page.fill('input[type="password"]', admin.password)
  await page.click('button:has-text("登 录")')
  await page.waitForURL('**/admin/overview', { timeout: 10000 })
  const at = await page.evaluate(() => localStorage.getItem('wj_token'))

  // 准备:两个教师、三个学生
  const mk = async (username, role) => (await api('POST', '/system-admin/users', at, { username, password: PW, realName: username, role })).body?.data
  const t1 = await mk(`e2e_ct1_${sfx}`, 1)
  const t2 = await mk(`e2e_ct2_${sfx}`, 1)
  const s1 = await mk(`e2e_cs1_${sfx}`, 2)
  const s2 = await mk(`e2e_cs2_${sfx}`, 2)
  const s3 = await mk(`e2e_cs3_${sfx}`, 2)
  check('准备 2 教师 3 学生', !!(t1 && t2 && s1 && s2 && s3))
  const tok = { t1: await login(t1.username, PW), t2: await login(t2.username, PW), s1: await login(s1.username, PW), s2: await login(s2.username, PW), s3: await login(s3.username, PW) }

  // 1. UI 建课(指定教师 t1)
  const cName = `e2e课程_${sfx}`
  await page.click('[data-testid="admin-nav-courses"]')
  await page.waitForURL('**/admin/courses')
  await page.click('[data-testid="admin-course-create"]')
  await page.fill('[data-testid="admin-course-create-name"]', cName)
  await page.selectOption('[data-testid="admin-course-create-teacher"]', String(t1.id))
  await page.click('[data-testid="admin-course-create-submit"]')
  await page.waitForTimeout(1000)
  const course = ((await api('GET', '/system-admin/courses?keyword=' + encodeURIComponent(cName), at)).body?.data?.records || [])[0]
  check('建课落库,owner=t1,草稿', course && course.teacherId === t1.id && course.status === 0, JSON.stringify(course && { t: course.teacherId, s: course.status }))
  const cid = course.id
  check('t1 教师端可见、t2 不可见', (await teacherCourseIds(tok.t1)).includes(cid) && !(await teacherCourseIds(tok.t2)).includes(cid))

  // 2. UI 发布
  await page.fill('[data-testid="admin-courses-keyword"]', cName)
  await page.click('[data-testid="admin-courses-search"]')
  await page.waitForSelector(`[data-testid="admin-course-row-${cid}"]`)
  await page.click(`[data-testid="admin-course-publish-${cid}"]`)
  await page.waitForTimeout(800)
  check('发布后 status=1', ((await api('GET', '/system-admin/courses?keyword=' + encodeURIComponent(cName), at)).body.data.records[0]).status === 1)

  // 3. UI 转让给 t2
  await page.click(`[data-testid="admin-course-transfer-${cid}"]`)
  await page.selectOption('[data-testid="admin-course-transfer-teacher"]', String(t2.id))
  await page.click('[data-testid="admin-course-transfer-submit"]')
  await page.waitForTimeout(800)
  check('转让后 t2 可见、t1 不可见', (await teacherCourseIds(tok.t2)).includes(cid) && !(await teacherCourseIds(tok.t1)).includes(cid))
  check('列表任课教师列已更新', (await page.textContent(`[data-testid="admin-course-teacher-${cid}"]`)).includes(t2.username) ||
    (await page.textContent(`[data-testid="admin-course-teacher-${cid}"]`)).includes(t2.realName))
  // 转给学生被拒
  const badTransfer = await api('PATCH', `/system-admin/courses/${cid}/owner`, at, { teacherId: s1.id }, true)
  check('转让给学生被拒', badTransfer.body?.code !== 0, badTransfer.body?.message)
  await page.screenshot({ path: `${SHOTS}/admin-08-courses.png` })

  // 4. UI 建班级 A;API 建班级 B
  const aName = `e2e班A_${sfx}`
  await page.click('[data-testid="admin-nav-clazzes"]')
  await page.waitForURL('**/admin/clazzes')
  await page.click('[data-testid="admin-clazz-create"]')
  await page.fill('[data-testid="admin-clazz-create-name"]', aName)
  await page.click('[data-testid="admin-clazz-create-submit"]')
  await page.waitForTimeout(800)
  const clazzA = ((await api('GET', '/system-admin/clazzes?keyword=' + encodeURIComponent(aName), at)).body?.data?.records || [])[0]
  check('建班级 A', !!clazzA)
  const clazzB = (await api('POST', '/system-admin/clazzes', at, { name: `e2e班B_${sfx}` })).body?.data
  check('建班级 B', !!clazzB)
  const dupName = await api('POST', '/system-admin/clazzes', at, { name: aName }, true)
  check('班级重名被拒', dupName.body?.code !== 0, dupName.body?.message)

  // 5. 详情页粘贴用户名加学生(含一个不存在)
  await page.fill('[data-testid="admin-clazzes-keyword"]', aName)
  await page.click('[data-testid="admin-clazzes-search"]')
  await page.click(`[data-testid="admin-clazz-link-${clazzA.id}"]`)
  await page.waitForURL(`**/admin/clazzes/${clazzA.id}`)
  await page.click('[data-testid="admin-clazz-add-students"]')
  await page.fill('[data-testid="admin-clazz-students-input"]', `${s1.username}\n${s2.username}\nno_such_user_${sfx}`)
  await page.click('[data-testid="admin-clazz-students-submit"]')
  await page.waitForSelector('[data-testid="admin-clazz-students-result"]')
  const nf = await page.textContent('[data-testid="admin-clazz-students-notfound"]').catch(() => '')
  check('结果展示未找到的用户名', nf.includes(`no_such_user_${sfx}`), nf)
  await page.screenshot({ path: `${SHOTS}/admin-09-clazz-add-students.png` })
  await page.click('[data-testid="admin-clazz-students-done"]').catch(() => page.keyboard.press('Escape'))
  await page.waitForTimeout(500)
  check('学生表 2 人', (await page.locator('[data-testid^="admin-clazz-student-row-"]').count()) === 2)

  // 6. UI 关联课程 → 班内学生自动选课
  check('关联前 s1 未选该课', !(await myCourseIds(tok.s1)).includes(cid))
  await page.selectOption('[data-testid="admin-clazz-course-select"]', String(cid))
  await page.click('[data-testid="admin-clazz-add-course"]')
  await page.waitForTimeout(1000)
  check('关联课程后 s1、s2 我的课程出现该课', (await myCourseIds(tok.s1)).includes(cid) && (await myCourseIds(tok.s2)).includes(cid))
  check('s3(不在班)不受影响', !(await myCourseIds(tok.s3)).includes(cid))
  await page.screenshot({ path: `${SHOTS}/admin-10-clazz-detail.png`, fullPage: true })

  // 7. 后加入的学生补选;转班
  const addS3 = (await api('POST', `/system-admin/clazzes/${clazzA.id}/students`, at, { usernames: [s3.username] })).body?.data
  check('后加入 s3 补选 1 门', addS3?.enrolled === 1 && (await myCourseIds(tok.s3)).includes(cid), JSON.stringify(addS3))
  const move = (await api('POST', `/system-admin/clazzes/${clazzB.id}/students`, at, { usernames: [s2.username] })).body?.data
  check('s2 转到 B,movedFrom=A', move?.movedFrom?.some((m) => m.studentId === s2.id && m.fromClazzName === aName), JSON.stringify(move?.movedFrom))
  const detailA = (await api('GET', `/system-admin/clazzes/${clazzA.id}`, at)).body?.data
  check('A 剩 s1、s3', detailA.students.length === 2 && detailA.students.every((s) => [s1.id, s3.id].includes(s.id)))

  // 8. UI 解除班级课程 → 选课保留
  await page.reload({ waitUntil: 'networkidle' })
  await page.click(`[data-testid="admin-clazz-course-remove-${cid}"]`)
  await page.waitForTimeout(800)
  check('解除后班级无课程', ((await api('GET', `/system-admin/clazzes/${clazzA.id}`, at)).body.data.courses || []).length === 0)
  check('解除后 s1 仍保留选课', (await myCourseIds(tok.s1)).includes(cid))

  // 9. UI 删除班级 B → s2 选课保留
  await page.click('[data-testid="admin-nav-clazzes"]')
  await page.fill('[data-testid="admin-clazzes-keyword"]', `e2e班B_${sfx}`)
  await page.click('[data-testid="admin-clazzes-search"]')
  await page.click(`[data-testid="admin-clazz-delete-${clazzB.id}"]`)
  await page.waitForTimeout(800)
  check('班级 B 已删除', ((await api('GET', `/system-admin/clazzes/${clazzB.id}`, at, null, true)).body?.code ?? 0) !== 0)
  check('删班后 s2 仍保留选课', (await myCourseIds(tok.s2)).includes(cid))

  // 10. UI 归档 → 学生不可访问;恢复
  await page.click('[data-testid="admin-nav-courses"]')
  await page.fill('[data-testid="admin-courses-keyword"]', cName)
  await page.click('[data-testid="admin-courses-search"]')
  await page.click(`[data-testid="admin-course-archive-${cid}"]`)
  await page.waitForTimeout(800)
  const arch = (await api('GET', '/system-admin/courses?keyword=' + encodeURIComponent(cName), at)).body.data.records[0]
  check('归档后 archived=true', arch.archived === true)
  await page.click(`[data-testid="admin-course-restore-${cid}"]`)
  await page.waitForTimeout(800)
  check('恢复后 archived=false', (await api('GET', '/system-admin/courses?keyword=' + encodeURIComponent(cName), at)).body.data.records[0].archived === false)

  // 11. 审计留痕
  const actions = ((await api('GET', '/system-admin/audit-logs?size=100&operator=' + encodeURIComponent(admin.username), at)).body.data.records || []).map((r) => r.action)
  const need = ['COURSE_ADMIN_CREATE', 'COURSE_TRANSFER', 'CLAZZ_CREATE', 'CLAZZ_STUDENT_ADD', 'CLAZZ_COURSE_ADD', 'CLAZZ_COURSE_REMOVE', 'CLAZZ_DELETE']
  const missing = need.filter((a) => !actions.includes(a))
  check('审计含课程/班级操作', missing.length === 0, missing.join(','))

  // 12. 越权
  const dt = await login('demo_teacher', 'demo')
  if (dt) {
    check('教师调课程管理接口 403', (await api('GET', '/system-admin/courses', dt)).status === 403)
    check('教师调班级接口 403', (await api('GET', '/system-admin/clazzes', dt)).status === 403)
  }
  check('无非预期失败请求', badResponses.length === 0, badResponses.slice(0, 5).join(' | '))
} catch (e) {
  console.error('脚本异常:', e)
  fail.push('exception')
  await page.screenshot({ path: `${SHOTS}/admin-a3-ERR.png` }).catch(() => {})
} finally {
  await browser.close()
}
console.log(fail.length ? `\nFAIL ${fail.length}: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
