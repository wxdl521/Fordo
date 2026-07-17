import { test as base, expect } from '@playwright/test'

const COURSE_ID = 1

const STUDENT = Object.freeze({
  id: 9101,
  username: 'e2e_student',
  realName: '端到端学生',
  role: 2
})

const TEACHER = Object.freeze({
  id: 9201,
  username: 'e2e_teacher',
  realName: '端到端教师',
  role: 1
})

const COURSE = Object.freeze({
  id: COURSE_ID,
  code: 'E2E-SE',
  name: '软件工程测试课',
  published: true
})

const GRAPH = Object.freeze({
  nodes: [
    {
      id: 101,
      nodeCode: 'ROOT',
      name: '软件工程基础',
      chapter: '软件工程概述',
      difficulty: 2,
      isKey: true,
      bloom: '理解',
      description: '理解软件工程的基本目标与生命周期。',
      mastery: 'weak',
      masteryScore: 42
    },
    {
      id: 102,
      nodeCode: 'REQ',
      name: '需求分析',
      chapter: '需求确定',
      difficulty: 3,
      isKey: true,
      bloom: '应用',
      description: '识别并表达可验证的软件需求。',
      mastery: 'mastered',
      masteryScore: 86
    },
    {
      id: 103,
      nodeCode: 'TEST',
      name: '软件测试',
      chapter: '软件测试',
      difficulty: 3,
      isKey: false,
      bloom: '应用',
      description: '使用测试活动验证软件质量。',
      mastery: 'unlearned',
      masteryScore: null
    }
  ],
  edges: [
    { id: 201, source: 'ROOT', target: 'REQ', type: '前置', pending: false },
    { id: 202, source: 'REQ', target: 'TEST', type: '前置', pending: false }
  ]
})

const CONVERSATIONS = Object.freeze([
  {
    id: 301,
    title: 'E2E 学习计划',
    createdAt: '2026-07-16T12:00:00'
  }
])

const PENDING_EDGES = Object.freeze([
  {
    id: 401,
    source: 'ROOT',
    target: 'REQ',
    sourceName: '软件工程基础',
    targetName: '需求分析',
    type: '前置',
    confidence: 95,
    reason: 'E2E 待复核关系'
  }
])

export const API_CALLS = Object.freeze({
  me: 'GET /me',
  graph: 'GET /graph/1',
  review: 'GET /review/today?courseId=1',
  companion: 'GET /companion/conversations?courseId=1',
  teacherCourses: 'GET /teacher/courses',
  teacherGraph: 'GET /teacher/graph?courseId=1',
  teacherPending: 'GET /teacher/graph/pending-edges?courseId=1'
})

const apiCallsByPage = new WeakMap()

function result(data) {
  return JSON.stringify({ code: 0, message: 'ok', data })
}

async function fulfill(route, data) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: result(data)
  })
}

async function reject(route, status, message) {
  await route.fulfill({
    status,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify({ code: status, message, data: null })
  })
}

async function installApiMocks(page, user, token) {
  const calls = new Set()
  apiCallsByPage.set(page, calls)

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '')
    const method = request.method()

    if (request.headers().authorization !== `Bearer ${token}`) {
      await reject(route, 401, 'Missing or invalid E2E Bearer token')
      return
    }

    if (method === 'GET' && path === '/me') {
      calls.add(API_CALLS.me)
      await fulfill(route, user)
      return
    }
    if (method === 'GET' && path === `/graph/${COURSE_ID}`) {
      calls.add(API_CALLS.graph)
      await fulfill(route, GRAPH)
      return
    }
    if (method === 'GET' && path === '/review/today' && url.searchParams.get('courseId') === String(COURSE_ID)) {
      calls.add(API_CALLS.review)
      await fulfill(route, { items: [], totalDecayingCount: 0 })
      return
    }
    if (method === 'GET' && path === '/companion/conversations' && url.searchParams.get('courseId') === String(COURSE_ID)) {
      calls.add(API_CALLS.companion)
      await fulfill(route, CONVERSATIONS)
      return
    }
    if (method === 'GET' && path === '/teacher/courses') {
      calls.add(API_CALLS.teacherCourses)
      await fulfill(route, [COURSE])
      return
    }
    if (method === 'GET' && path === '/teacher/graph' && url.searchParams.get('courseId') === String(COURSE_ID)) {
      calls.add(API_CALLS.teacherGraph)
      await fulfill(route, GRAPH)
      return
    }
    if (method === 'GET' && path === '/teacher/graph/pending-edges' && url.searchParams.get('courseId') === String(COURSE_ID)) {
      calls.add(API_CALLS.teacherPending)
      await fulfill(route, PENDING_EDGES)
      return
    }

    await reject(route, 404, `Unmocked E2E API: ${method} ${path}${url.search}`)
  })
}

async function installSession(page, user, token, courseKey) {
  await installApiMocks(page, user, token)
  await page.addInitScript(
    ({ authToken, currentCourseKey }) => {
      localStorage.setItem('wj_token', authToken)
      localStorage.removeItem('wj_user')
      localStorage.setItem(currentCourseKey, '1')
    },
    { authToken: token, currentCourseKey: courseKey }
  )
}

export function expectApiCalls(page, expectedCalls) {
  const calls = apiCallsByPage.get(page)
  expect(calls, 'authenticated page did not install API call tracking').toBeDefined()
  expect([...calls]).toEqual(expect.arrayContaining(expectedCalls))
}

export const test = base.extend({
  studentPage: async ({ page }, use) => {
    await installSession(page, STUDENT, 'e2e-student-token', 'wj.student.currentCourseId')
    await use(page)
  },

  teacherPage: async ({ page }, use) => {
    await installSession(page, TEACHER, 'e2e-teacher-token', 'wj.teacher.currentCourseId')
    await use(page)
  }
})

export { expect }
