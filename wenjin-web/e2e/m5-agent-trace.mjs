// M5 Agent 轨迹 — 教师端列表/明细真机验收 (Playwright + 系统 Edge, headless)
//
// 前置:
//   1. 后端 8080 + Vite 5173 已起
//   2. MySQL 已应用 V10 (agent_run / agent_step) 且 wenjin.features.agent-trace-enabled=true
//   3. demo_teacher / demo 与 demo_student / demo 可登录
//   4. 教师至少有一门可管理课程（data.sql 演示课即可）
//
// 可选环境变量:
//   WJ_BASE     前端基址，默认 http://localhost:5173
//   WJ_COURSE   优先课程 ID（教师端 localStorage），默认 1
//
// 运行:
//   cd wenjin-web
//   node .\e2e\m5-agent-trace.mjs
//
// 验收:
//   1. demo_teacher 登录成功
//   2. 打开 /teacher/agent-runs，列表 UI 就绪（表头 + 行或「暂无轨迹」空态）
//   3. GET /api/teacher/agent-runs 教师令牌 → HTTP 200 / code 0
//   4. 学生令牌 / 无令牌访问同接口 → 401 或 403
//   5. 若列表有 run：点击展开 steps；steps API 200 且 steps 表可见
//   6. 截图 → 测试截图/m5-agent-trace.png
//
// 无后端时：请跑 Vitest 合约测试 test/m5-e2e-script-contract.test.js
//   npm run test -- test/m5-e2e-script-contract.test.js
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const preferredCourse = Number(process.env.WJ_COURSE || 1)
const shots = '../测试截图'
mkdirSync(shots, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
const fail = []

const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}
const info = (message) => console.log('  ·', message)

async function api(method, path, token, body) {
  return await page.evaluate(async ({ method, path, token, body }) => {
    const res = await fetch('/api' + path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token != null ? { Authorization: 'Bearer ' + token } : {})
      },
      body: body ? JSON.stringify(body) : undefined
    })
    const json = await res.json().catch(() => null)
    return { status: res.status, body: json }
  }, { method, path, token, body })
}

function isDenied(res) {
  const code = res?.body?.code
  const status = res?.status
  return code === 401 || code === 403 || status === 401 || status === 403
}

function isOk(res) {
  return res?.status === 200 && (res?.body?.code === 0 || res?.body?.code === '0')
}

try {
  await page.goto(base, { waitUntil: 'networkidle' })

  // ── 1. 教师登录 ──────────────────────────────────────────────
  const login = await api('POST', '/login', null, { username: 'demo_teacher', password: 'demo' })
  const teacher = {
    token: login.body?.data?.token,
    id: login.body?.data?.user?.id,
    user: login.body?.data?.user
  }
  check('demo_teacher 登录成功', !!teacher.token && !!teacher.id, `id=${teacher.id}`)
  if (!teacher.token || !teacher.id) throw new Error('demo_teacher 登录失败')

  // 选定教师课程（优先 WJ_COURSE，否则第一门）
  const coursesRes = await api('GET', '/teacher/courses', teacher.token)
  const courses = coursesRes.body?.data || []
  check('教师课程列表可读', isOk(coursesRes) && Array.isArray(courses), `n=${courses.length}`)
  if (!courses.length) throw new Error('教师无可管理课程')

  const preferred = courses.find((c) => Number(c.id ?? c.courseId) === preferredCourse)
  const course = preferred || courses[0]
  const courseId = Number(course.id ?? course.courseId)
  check('选定课程', courseId > 0, `courseId=${courseId} name=${course.name || ''}`)

  await page.evaluate(({ token, user, courseId }) => {
    localStorage.setItem('wj_token', token)
    localStorage.setItem('wj_user', JSON.stringify(user))
    localStorage.setItem('wj.teacher.currentCourseId', String(courseId))
  }, { token: teacher.token, user: teacher.user, courseId })

  // ── 2. 打开教师 Agent 轨迹页 ─────────────────────────────────
  await page.goto(`${base}/teacher/agent-runs`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)

  const titleVisible = await page.locator('.title', { hasText: 'Agent 轨迹' }).first().isVisible().catch(() => false)
  check('Agent 轨迹页标题可见', titleVisible)

  // 等待加载结束：表头 / 空态 / 错误其一出现
  await page.waitForFunction(() => {
    const body = document.body?.innerText || ''
    return body.includes('暂无轨迹')
      || body.includes('Kind')
      || body.includes('加载失败')
      || body.includes('请先在「图谱审核」选择课程')
  }, { timeout: 15000 }).catch(() => {})

  const bodyText = await page.locator('body').innerText()
  const hasTableHeader = /Kind|Status|Model|Steps/.test(bodyText)
  const hasEmpty = bodyText.includes('暂无轨迹') || bodyText.includes('请先在「图谱审核」选择课程')
  const hasError = bodyText.includes('加载失败') || (await page.locator('.empty.err').count()) > 0
  check('列表 UI 就绪（表头或空态）', hasTableHeader || hasEmpty, hasError ? 'page error' : '')

  // ── 3. API：教师令牌 list 200 ────────────────────────────────
  const listPath = `/teacher/agent-runs?courseId=${courseId}&page=1&size=20`
  const teacherList = await api('GET', listPath, teacher.token)
  check(
    'GET /api/teacher/agent-runs 教师令牌 → 200/code0',
    isOk(teacherList),
    `status=${teacherList.status} code=${teacherList.body?.code}`
  )

  const items = teacherList.body?.data?.items || teacherList.body?.data?.records || []
  const total = teacherList.body?.data?.total ?? items.length
  info(`runs total=${total} pageItems=${items.length}`)

  // ── 4. 鉴权：学生 / 无令牌 ───────────────────────────────────
  const studentLogin = await api('POST', '/login', null, { username: 'demo_student', password: 'demo' })
  const studentToken = studentLogin.body?.data?.token
  check('demo_student 登录成功（鉴权探针）', !!studentToken)

  const studentList = await api('GET', listPath, studentToken)
  check(
    '学生令牌访问 agent-runs → 401/403',
    isDenied(studentList),
    `status=${studentList.status} code=${studentList.body?.code}`
  )

  const noTokenList = await api('GET', listPath, null)
  check(
    '无令牌访问 agent-runs → 401/403',
    isDenied(noTokenList),
    `status=${noTokenList.status} code=${noTokenList.body?.code}`
  )

  // ── 5. 有 run 时展开 steps ───────────────────────────────────
  if (items.length > 0) {
    const runId = items[0].id
    info(`展开 run #${runId}`)

    // 优先点 UI 行；找不到则仍校验 steps API
    const runRow = page.locator('.run-row').first()
    const rowCount = await runRow.count()
    if (rowCount > 0) {
      await runRow.click()
      await page.waitForTimeout(800)
      const stepsVisible = await page.locator('.steps-table, .steps-msg').first().isVisible().catch(() => false)
      check('展开 run 后 steps 区域可见', stepsVisible)
    } else {
      info('页面未渲染 run 行，仅做 steps API 校验')
    }

    const stepsRes = await api('GET', `/teacher/agent-runs/${runId}/steps`, teacher.token)
    check(
      'GET /api/teacher/agent-runs/{id}/steps 教师令牌 → 200/code0',
      isOk(stepsRes),
      `status=${stepsRes.status} code=${stepsRes.body?.code}`
    )
    const steps = Array.isArray(stepsRes.body?.data) ? stepsRes.body.data : []
    info(`steps count=${steps.length}`)
    check('steps 响应为数组', Array.isArray(stepsRes.body?.data))
  } else {
    info('当前课程暂无 agent_run，跳过 steps 展开（空态亦为通过）')
    check('空列表可接受（暂无轨迹）', hasEmpty || hasTableHeader)
  }

  // ── 6. 截图 ──────────────────────────────────────────────────
  await page.screenshot({ path: `${shots}/m5-agent-trace.png`, fullPage: true }).catch(() => {})
  info('截图已存: m5-agent-trace.png')
} catch (error) {
  console.log('✗ 脚本异常:', error.message)
  fail.push('exception')
  await page.screenshot({ path: `${shots}/m5-agent-trace-error.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}

console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
