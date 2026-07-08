// M4 伴侣工具调用 — 真机验收(Playwright + 系统 Edge, headless)
// 前置: 后端 8080 + Vite 5173 已起; WENJIN_AI_API_KEY 可用; demo_student/demo 可登录。
// 验收:
//   1. 打开 AI 伴侣, 询问薄弱点 -> 对话流出现工具胶囊。
//   2. 请求指定节点练习 -> 对话流出现内嵌 practice_card。
//   3. 至少作答一道题(优先客观题)并提交 -> 结果区显示掌握度/完成摘要。
import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const preferredCourse = Number(process.env.WJ_COURSE || 5)
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

function courseIdOf(course) {
  return course?.courseId ?? course?.id
}

async function chooseCourse(student) {
  let mine = await api('GET', `/course/my?studentId=${student.id}`, student.token)
  let courses = mine.body?.data || []

  if (!courses.some((course) => courseIdOf(course) === preferredCourse)) {
    await api('POST', '/course/enroll', student.token, {
      studentId: student.id,
      courseId: preferredCourse
    })
    mine = await api('GET', `/course/my?studentId=${student.id}`, student.token)
    courses = mine.body?.data || []
  }

  const selected = courses.find((course) => courseIdOf(course) === preferredCourse) || courses[0]
  return courseIdOf(selected)
}

async function findPracticeTarget(student, courseId) {
  let current = await api('GET', `/path/current?studentId=${student.id}&courseId=${courseId}`, student.token)
  let steps = current.body?.data?.steps || []

  if (!steps.some((step) => step.nodeCode && step.availableQuestionCount > 0)) {
    const generated = await api('POST', '/path/generate', student.token, {
      studentId: student.id,
      courseId,
      useAi: false
    })
    steps = generated.body?.data?.steps || steps
  }

  const step = steps.find((item) => item.nodeCode && item.availableQuestionCount > 0)
  if (step) {
    return {
      nodeCode: step.nodeCode,
      name: step.name || step.nodeName || step.nodeCode,
      source: 'path'
    }
  }

  const graph = await api('GET', `/graph/${courseId}?studentId=${student.id}`, student.token)
  const node = (graph.body?.data?.nodes || []).find((item) => item.nodeCode || item.id)
  return node ? {
    nodeCode: node.nodeCode || node.id,
    name: node.name || node.nodeCode || node.id,
    source: 'graph'
  } : null
}

async function companionState() {
  return await page.evaluate(() => {
    const h = window.__wjCompanion
    if (!h) return null

    return {
      typing: h.typing.value,
      conversationId: h.conversationId.value,
      messages: h.msgs.value.map((message) => ({
        role: message.role,
        content: message.content || '',
        name: message.name || null,
        label: message.label || null,
        ok: message.ok ?? null,
        phase: message.phase || null,
        submitError: message.submitError || '',
        submitResult: message.submitResult || null,
        card: message.card ? {
          sessionId: message.card.sessionId,
          node: message.card.node,
          questionCount: (message.card.questions || []).length,
          questions: (message.card.questions || []).map((question) => ({
            questionId: question.questionId,
            type: question.type,
            optionCount: (question.o || []).length
          }))
        } : null
      }))
    }
  })
}

async function waitCompanion(label, predicate, tries = 100, delayMs = 500) {
  let last = null
  for (let i = 0; i < tries; i++) {
    last = await companionState()
    if (last && predicate(last)) return last
    await page.waitForTimeout(delayMs)
  }
  check(label, false, last ? JSON.stringify(last.messages.slice(-4)) : 'no hook')
  return last
}

async function sendCompanionText(text) {
  await page.evaluate((text) => {
    const h = window.__wjCompanion
    h.input.value = text
    h.sendText()
  }, text)
}

async function answerLatestPracticeCard() {
  return await page.evaluate(async () => {
    const h = window.__wjCompanion
    let practice = null
    for (let i = h.msgs.value.length - 1; i >= 0; i--) {
      if (h.msgs.value[i].role === 'practice') {
        practice = h.msgs.value[i]
        break
      }
    }
    if (!practice) return { ok: false, reason: 'no practice message' }

    const questions = practice.card?.questions || []
    if (!questions.length) return { ok: false, reason: 'practice has no questions' }

    let index = questions.findIndex((question) => question.type !== 4 && (question.o || []).length > 0)
    const usedObjective = index >= 0
    if (index < 0) index = 0

    practice.currentIndex = index
    const question = questions[index]
    if (question.type === 2) {
      practice.answers.multi = { ...practice.answers.multi, [index]: [0] }
    } else if (question.type === 4) {
      practice.answers.text = { ...practice.answers.text, [index]: 'M4 e2e 简答作答' }
    } else {
      practice.answers.single = { ...practice.answers.single, [index]: 0 }
    }

    await h.submitPracticeMessage(practice)
    return {
      ok: practice.phase === 'result',
      phase: practice.phase,
      submitError: practice.submitError,
      usedObjective,
      answeredType: question.type,
      result: practice.submitResult
    }
  })
}

try {
  await page.goto(base, { waitUntil: 'networkidle' })

  const login = await api('POST', '/login', null, { username: 'demo_student', password: 'demo' })
  const student = {
    token: login.body?.data?.token,
    id: login.body?.data?.user?.id,
    user: login.body?.data?.user
  }
  check('demo_student 登录成功', !!student.token && !!student.id, `id=${student.id}`)
  if (!student.token || !student.id) throw new Error('demo_student 登录失败')

  const courseId = await chooseCourse(student)
  check('选定课程', Number(courseId) > 0, `courseId=${courseId}`)
  if (!courseId) throw new Error('无可用课程')

  const target = await findPracticeTarget(student, courseId)
  check('找到可练习目标节点', !!target?.nodeCode, target ? `${target.nodeCode} ${target.name} (${target.source})` : '')
  if (!target?.nodeCode) throw new Error('无可练习节点')

  await page.evaluate(({ token, user, courseId }) => {
    localStorage.setItem('wj_token', token)
    localStorage.setItem('wj_user', JSON.stringify(user))
    localStorage.setItem('wj.student.currentCourseId', String(courseId))
  }, { token: student.token, user: student.user, courseId })

  await page.goto(`${base}/companion?courseId=${courseId}`, { waitUntil: 'networkidle' })
  const ready = await waitCompanion('AI 伴侣 DEV hook 就绪', (state) => !!state, 40, 250)
  check('AI 伴侣页面就绪', !!ready)
  if (!ready) throw new Error('Companion hook missing')

  await sendCompanionText('我最近哪里比较薄弱？请先查询我的掌握情况，再给我一个很短的建议。')
  const weakTool = await waitCompanion(
    '弱点提问后出现工具胶囊',
    (state) => state.messages.some((message) => message.role === 'tool'),
    120,
    500
  )
  check('弱点提问触发工具胶囊', !!weakTool?.messages?.some((message) => message.role === 'tool'))
  await waitCompanion('弱点回答结束', (state) => !state.typing, 180, 500)

  await sendCompanionText(`请调用练习工具，给我出两道关于节点 ${target.nodeCode}（${target.name}）的题。`)
  const practiceState = await waitCompanion(
    '练习提问后出现 practice_card',
    (state) => state.messages.some((message) => message.role === 'practice' && message.card?.sessionId),
    180,
    500
  )
  const practice = practiceState?.messages?.find((message) => message.role === 'practice')
  check('练习卡包含会话和题目', !!practice?.card?.sessionId && practice.card.questionCount > 0,
    practice?.card ? `session=${practice.card.sessionId} questions=${practice.card.questionCount}` : '')

  const answer = await answerLatestPracticeCard()
  check('作答并提交练习卡', answer.ok, answer.ok
    ? `type=${answer.answeredType} objective=${answer.usedObjective}`
    : `${answer.phase || ''} ${answer.submitError || answer.reason || ''}`)

  const bodyText = await page.locator('body').innerText()
  check('结果文本包含掌握度或完成摘要', /掌握度|已记录本次练习|已完成当前路径项/.test(bodyText))

  await page.screenshot({ path: shots + '/m4-companion-tools.png' }).catch(() => {})
  info('截图已存: m4-companion-tools.png')
} catch (error) {
  console.log('✗ 脚本异常:', error.message)
  fail.push('exception')
  await page.screenshot({ path: shots + '/m4-companion-tools-error.png' }).catch(() => {})
} finally {
  await browser.close()
}

console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
