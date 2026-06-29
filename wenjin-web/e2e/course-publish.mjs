// 课程发布闭环 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起。账号:demo_teacher/demo(教师)、demo_student/demo(学生)。
// 流程:教师建草稿课 → 学生广场不可见 → 教师发布 → 学生广场可见可选 → 选课进我的课程 → 教师下架 → 学生消失。
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
const fail = []
const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}

// 经 vite 代理直接调后端；带 X-User-Id 头模拟身份
async function api(method, path, userId, body) {
  return await page.evaluate(async ({ method, path, userId, body }) => {
    const res = await fetch('/api' + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(userId != null ? { 'X-User-Id': String(userId) } : {}) },
      body: body ? JSON.stringify(body) : undefined
    })
    const json = await res.json().catch(() => null)
    return { status: res.status, body: json }
  }, { method, path, userId, body })
}

try {
  await page.goto(base, { waitUntil: 'networkidle' })

  // 登录拿到教师/学生 id
  const tLogin = await api('POST', '/login', null, { username: 'demo_teacher', password: 'demo' })
  const teacherId = tLogin.body?.data?.id
  check('教师登录成功', !!teacherId, `id=${teacherId}`)
  const sLogin = await api('POST', '/login', null, { username: 'demo_student', password: 'demo' })
  const studentId = sLogin.body?.data?.id
  check('学生登录成功', !!studentId, `id=${studentId}`)

  // 教师建草稿课
  const cname = '发布验收课-' + Date.now()
  const created = await api('POST', '/teacher/courses', teacherId, { name: cname })
  const courseId = created.body?.data?.id
  check('教师建课成功(默认草稿)', !!courseId, `id=${courseId}`)

  // 学生视角:available 不含草稿课
  const avail1 = await api('GET', '/course/available', studentId)
  const names1 = (avail1.body?.data || []).map(c => c.name)
  check('草稿课对学生不可见', !names1.includes(cname))

  // 教师发布
  const pub = await api('PATCH', `/teacher/courses/${courseId}/status`, teacherId, { published: true })
  check('发布端点返回成功', pub.status === 200 && pub.body?.code === 0, `status=${pub.status}`)

  // 学生视角:available 现含该课
  const avail2 = await api('GET', '/course/available', studentId)
  const names2 = (avail2.body?.data || []).map(c => c.name)
  check('发布后学生广场可见', names2.includes(cname))

  // 学生端 UI:登录后进课程页,广场出现该课并可选课
  await page.evaluate((u) => localStorage.setItem('wj_user', JSON.stringify(u)),
    { id: studentId, role: 2, name: 'demo_student' })
  await page.goto(base + '/', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const enrollBtn = page.locator(`[data-testid="enroll-course-${courseId}"]`)
  check('学生课程页出现选课按钮', await enrollBtn.count() > 0)
  await page.screenshot({ path: SHOTS + '/publish-plaza.png' })
  if (await enrollBtn.count() > 0) {
    await enrollBtn.first().click()
    await page.waitForTimeout(1000)
  }
  // 选课后进入「我的课程」
  const my = await api('GET', `/course/my?studentId=${studentId}`, studentId)
  const myNames = (my.body?.data || []).map(c => c.name)
  check('选课后进入我的课程', myNames.includes(cname))

  // deep-link 守卫:已选 + 已发布 → 学生数据端点放行(code=0)
  const g1 = await api('GET', `/graph/${courseId}?studentId=${studentId}`, studentId)
  check('已选已发布课:图谱数据放行', g1.status === 200 && g1.body?.code === 0, `code=${g1.body?.code}`)

  // 教师下架
  const unpub = await api('PATCH', `/teacher/courses/${courseId}/status`, teacherId, { published: false })
  check('下架端点返回成功', unpub.status === 200 && unpub.body?.code === 0)

  // 下架后:学生我的课程 + 广场均不含
  const my2 = await api('GET', `/course/my?studentId=${studentId}`, studentId)
  const my2Names = (my2.body?.data || []).map(c => c.name)
  check('下架后我的课程消失', !my2Names.includes(cname))
  const avail3 = await api('GET', '/course/available', studentId)
  const names3 = (avail3.body?.data || []).map(c => c.name)
  check('下架后广场消失', !names3.includes(cname))

  // deep-link 守卫核心:下架后,即便此前已选,用缓存 courseId 直连数据端点也被拒(code=403)
  const g2 = await api('GET', `/graph/${courseId}?studentId=${studentId}`, studentId)
  check('下架后图谱 deep-link 被拒', g2.body?.code === 403, `code=${g2.body?.code} msg=${g2.body?.message}`)
  const p2 = await api('GET', `/diagnostic/paper?courseId=${courseId}`, studentId)
  check('下架后诊断卷 deep-link 被拒', p2.body?.code === 403, `code=${p2.body?.code}`)

  // 清理:删除验收课
  await api('DELETE', `/teacher/courses/${courseId}`, teacherId)
} catch (e) {
  console.log('✗ 脚本异常:', e.message)
  fail.push('exception')
  await page.screenshot({ path: SHOTS + '/publish-error.png' }).catch(() => {})
}

await browser.close()
console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
