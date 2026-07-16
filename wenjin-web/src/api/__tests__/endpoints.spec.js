import { beforeEach, describe, expect, it, vi } from 'vitest'

const { http, clearSessionAndRedirect } = vi.hoisted(() => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn()
  },
  clearSessionAndRedirect: vi.fn()
}))

vi.mock('../http.js', () => ({ http, clearSessionAndRedirect }))

import * as user from '../user.js'
import * as course from '../course.js'
import * as graph from '../graph.js'
import * as diagnostic from '../diagnostic.js'
import * as admin from '../admin.js'
import * as growth from '../growth.js'
import * as path from '../path.js'
import * as practice from '../practice.js'
import * as review from '../review.js'
import * as jobs from '../jobs.js'
import * as teacher from '../teacher.js'
import * as companion from '../companion.js'

function expectMultipartCall(call, expectedPath, expectedFile, expectedConfig) {
  const [actualPath, body, config] = call
  expect(actualPath).toBe(expectedPath)
  expect(body).toBeInstanceOf(FormData)
  expect(Array.from(body.entries())).toEqual([['file', expectedFile]])
  expect(config).toEqual(expectedConfig)
}

describe('API endpoint contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('audits every export in the endpoint modules', () => {
    const expectedExports = new Map([
      [user, ['register', 'login', 'me']],
      [course, ['getMyCourses', 'getAvailableCourses', 'enroll']],
      [graph, ['fetchGraph']],
      [diagnostic, [
        'fetchPaper',
        'submitPaper',
        'fetchResult',
        'startAdaptive',
        'answerAdaptive',
        'abandonAdaptive',
        'fetchEvidence'
      ]],
      [admin, [
        'generateQuestions',
        'annotateQuestions',
        'importQuestionJson',
        'importQuestionExcel',
        'importGraphJson',
        'importGraphExcel',
        'extractGraphFromFile',
        'fetchExtractDraft',
        'commitExtractDraft',
        'fetchExtractionReviews',
        'importQuestionExcelLegacy'
      ]],
      [growth, ['fetchGrowth']],
      [path, ['generatePath', 'fetchCurrentPath', 'completeItem']],
      [practice, ['startPractice', 'submitPractice', 'fetchPracticeHistory']],
      [review, ['fetchTodayReview']],
      [jobs, ['getJob', 'cancelJob', 'isJobTerminal', 'isJobSucceeded']],
      [teacher, [
        'fetchTeacherGraph',
        'fetchPendingEdges',
        'acceptEdge',
        'rejectEdge',
        'createEdge',
        'createNode',
        'updateNode',
        'deleteNode',
        'fetchQuestions',
        'reviewQuestions',
        'reviewAllQuestions',
        'fetchDashboard',
        'fetchTeacherCourses',
        'createTeacherCourse',
        'deleteTeacherCourse',
        'setTeacherCourseStatus'
      ]],
      [companion, [
        'isAbortError',
        'listConversations',
        'fetchConversation',
        'deleteConversation',
        'dispatchChatStreamEvent',
        'chatStream'
      ]]
    ])

    let exportCount = 0
    for (const [module, names] of expectedExports) {
      expect(Object.keys(module).sort()).toEqual([...names].sort())
      exportCount += names.length
    }
    expect(exportCount).toBe(59)
  })

  it('maps user and course operations without forwarding privilege fields', () => {
    user.register({
      username: 'alice',
      password: 'secret',
      realName: 'Alice',
      role: 1,
      studentId: 99
    })
    user.login({ username: 'alice', password: 'secret', role: 1 })
    user.me()
    course.getMyCourses()
    course.getAvailableCourses()
    course.enroll(17)

    expect(http.get.mock.calls).toEqual([
      ['/me'],
      ['/course/my'],
      ['/course/available']
    ])
    expect(http.post.mock.calls).toEqual([
      ['/register', { username: 'alice', password: 'secret', realName: 'Alice' }],
      ['/login', { username: 'alice', password: 'secret' }],
      ['/course/enroll', { courseId: 17 }]
    ])
  })

  it('maps graph and growth reads with their exact config shapes', () => {
    const signal = new AbortController().signal
    const graphConfig = { signal, headers: { 'X-Trace': 'graph' } }

    graph.fetchGraph(12, graphConfig)
    growth.fetchGrowth(12)

    expect(http.get.mock.calls).toEqual([
      ['/graph/12', graphConfig],
      ['/growth', { params: { courseId: 12 } }]
    ])
  })

  it('maps classic diagnostic reads and conditionally includes courseId on submit', () => {
    const answers = [{ questionId: 1, optionKey: 'A' }]

    diagnostic.fetchPaper(8)
    diagnostic.fetchResult(8)
    diagnostic.fetchEvidence(8)
    diagnostic.submitPaper({
      sessionToken: 'session-1',
      answers,
      courseId: 8,
      studentId: 99,
      ignored: true
    })
    diagnostic.submitPaper({
      sessionToken: 'session-2',
      answers,
      courseId: null,
      studentId: 99
    })

    expect(http.get.mock.calls).toEqual([
      ['/diagnostic/paper', { params: { courseId: 8 } }],
      ['/diagnostic/result', { params: { courseId: 8 } }],
      ['/diagnostic/adaptive/evidence', { params: { courseId: 8 } }]
    ])
    expect(http.post.mock.calls).toEqual([
      ['/diagnostic/submit', { sessionToken: 'session-1', answers, courseId: 8 }],
      ['/diagnostic/submit', { sessionToken: 'session-2', answers }]
    ])
  })

  it('maps every adaptive diagnostic action and strips studentId', () => {
    const startPayload = { courseId: 8, mode: 'fast', studentId: 99 }
    const answerPayload = {
      answers: [{ questionId: 2, studentAnswer: 'B' }],
      clientTrace: 'trace-1',
      studentId: 99
    }
    const abandonPayload = { reason: 'closed', studentId: 99 }

    diagnostic.startAdaptive(startPayload)
    diagnostic.answerAdaptive('adaptive-1', answerPayload)
    diagnostic.abandonAdaptive('adaptive-1', abandonPayload)
    diagnostic.abandonAdaptive('adaptive-2')

    expect(http.post.mock.calls).toEqual([
      ['/diagnostic/adaptive/start', { courseId: 8, mode: 'fast' }],
      ['/diagnostic/adaptive/adaptive-1/answer', {
        answers: [{ questionId: 2, studentAnswer: 'B' }],
        clientTrace: 'trace-1'
      }],
      ['/diagnostic/adaptive/adaptive-1/abandon', { reason: 'closed' }],
      ['/diagnostic/adaptive/adaptive-2/abandon', {}]
    ])
    expect(startPayload.studentId).toBe(99)
    expect(answerPayload.studentId).toBe(99)
    expect(abandonPayload.studentId).toBe(99)
  })

  it('maps admin AI job dispatches and header defaults', () => {
    const request = { ids: [1, 2], model: 'strict' }

    admin.generateQuestions(3, 'NODE-1', 6, {
      headers: { 'Idempotency-Key': 'job-key', 'X-Trace': 'trace-1' }
    })
    admin.annotateQuestions(3, request)

    expect(http.post.mock.calls).toEqual([
      ['/admin/question/generate', null, {
        params: { courseId: 3, nodeCode: 'NODE-1', count: 6 },
        headers: { 'Idempotency-Key': 'job-key', 'X-Trace': 'trace-1' }
      }],
      ['/admin/question/annotate', request, {
        params: { courseId: 3 },
        headers: {}
      }]
    ])
  })

  it('maps all question imports including the legacy upload alias', () => {
    const jsonFile = new File(['{}'], 'questions.json', { type: 'application/json' })
    const excelFile = new File(['excel'], 'questions.xlsx')
    const legacyFile = new File(['legacy'], 'legacy.xlsx')
    const onProgress = vi.fn()
    const onLegacyProgress = vi.fn()

    admin.importQuestionJson(4, jsonFile)
    admin.importQuestionExcel(4, excelFile, {
      headers: { 'Idempotency-Key': 'excel-key' },
      onUploadProgress: onProgress
    })
    admin.importQuestionExcelLegacy(4, legacyFile, onLegacyProgress)

    expect(http.post).toHaveBeenCalledTimes(3)
    expectMultipartCall(
      http.post.mock.calls[0],
      '/admin/question/import/json',
      jsonFile,
      {
        params: { courseId: 4 },
        headers: { 'Content-Type': 'multipart/form-data' }
      }
    )
    expectMultipartCall(
      http.post.mock.calls[1],
      '/admin/question/import/excel',
      excelFile,
      {
        params: { courseId: 4 },
        headers: {
          'Content-Type': 'multipart/form-data',
          'Idempotency-Key': 'excel-key'
        },
        onUploadProgress: onProgress
      }
    )
    expectMultipartCall(
      http.post.mock.calls[2],
      '/admin/question/import/excel',
      legacyFile,
      {
        params: { courseId: 4 },
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: onLegacyProgress
      }
    )
  })

  it('maps graph JSON, Excel, and extraction imports', () => {
    const graphData = { nodes: [{ code: 'N1' }], edges: [] }
    const excelFile = new File(['excel'], 'graph.xlsx')
    const standardFile = new File(['standard'], 'standard.pdf')
    const onProgress = vi.fn()

    admin.importGraphJson('COURSE-A', graphData)
    admin.importGraphExcel('COURSE-A', excelFile, {
      headers: { 'Idempotency-Key': 'graph-key' },
      onUploadProgress: onProgress
    })
    admin.extractGraphFromFile('COURSE-A', standardFile)

    expect(http.post).toHaveBeenCalledTimes(3)
    expect(http.post.mock.calls[0]).toEqual([
      '/admin/graph/import',
      graphData,
      { params: { courseCode: 'COURSE-A' } }
    ])
    expectMultipartCall(
      http.post.mock.calls[1],
      '/admin/graph/import/excel',
      excelFile,
      {
        params: { courseCode: 'COURSE-A' },
        headers: {
          'Content-Type': 'multipart/form-data',
          'Idempotency-Key': 'graph-key'
        },
        onUploadProgress: onProgress
      }
    )
    expectMultipartCall(
      http.post.mock.calls[2],
      '/admin/graph/extract',
      standardFile,
      {
        params: { courseCode: 'COURSE-A' },
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: undefined
      }
    )
  })

  it('maps extraction draft reads, commits, and config merging', () => {
    const finalGraph = { nodes: [], edges: [] }
    const signal = new AbortController().signal

    admin.fetchExtractDraft('draft/with space')
    admin.commitExtractDraft('draft/with space', finalGraph)
    admin.fetchExtractionReviews('COURSE-A', {
      signal,
      timeout: 123,
      params: { page: 2, courseCode: 'WRONG' }
    })

    expect(http.get.mock.calls).toEqual([
      ['/admin/graph/extract/draft/with space'],
      ['/admin/graph/extract/reviews', {
        signal,
        timeout: 123,
        params: { page: 2, courseCode: 'COURSE-A' }
      }]
    ])
    expect(http.post.mock.calls).toEqual([
      ['/admin/graph/extract/draft/with space/commit', finalGraph]
    ])
  })

  it('maps path and practice APIs while preserving caller payloads', () => {
    const pathPayload = { courseId: 5, targetNodeId: 9, studentId: 77 }
    const startPayload = { courseId: 5, nodeId: 9, size: 4, studentId: 77 }
    const submitPayload = {
      answers: [{ questionId: 3, studentAnswer: 'C' }],
      studentId: 77
    }

    path.generatePath(pathPayload)
    path.fetchCurrentPath(5)
    path.completeItem(18)
    practice.startPractice(startPayload)
    practice.submitPractice(23, submitPayload)
    practice.fetchPracticeHistory(5, 9)

    expect(http.get.mock.calls).toEqual([
      ['/path/current', { params: { courseId: 5 } }],
      ['/practice/history', { params: { courseId: 5, nodeId: 9 } }]
    ])
    expect(http.post.mock.calls).toEqual([
      ['/path/generate', { courseId: 5, targetNodeId: 9 }],
      ['/path/item/complete', null, { params: { itemId: 18 } }],
      ['/practice/start', { courseId: 5, nodeId: 9, size: 4 }],
      ['/practice/23/submit', { answers: [{ questionId: 3, studentAnswer: 'C' }] }]
    ])
    expect(pathPayload.studentId).toBe(77)
    expect(startPayload.studentId).toBe(77)
    expect(submitPayload.studentId).toBe(77)
  })

  it('maps review and job endpoints with exact parameter precedence', () => {
    const signal = new AbortController().signal

    review.fetchTodayReview(6, {
      signal,
      headers: { 'X-Trace': 'review' },
      params: { page: 3, courseId: 999 }
    })
    jobs.getJob('job-7')
    jobs.cancelJob('job-7')

    expect(http.get.mock.calls).toEqual([
      ['/review/today', {
        signal,
        headers: { 'X-Trace': 'review' },
        params: { page: 3, courseId: 6 }
      }],
      ['/teacher/jobs/job-7']
    ])
    expect(http.post.mock.calls).toEqual([
      ['/teacher/jobs/job-7/cancel']
    ])
  })

  it('covers every job status helper branch', () => {
    for (const status of [2, 3, 4]) {
      expect(jobs.isJobTerminal({ status })).toBe(true)
    }
    for (const job of [{ status: 0 }, { status: 1 }, { status: 5 }, {}, null, undefined]) {
      expect(jobs.isJobTerminal(job)).toBe(false)
    }

    expect(jobs.isJobSucceeded({ status: 2 })).toBe(true)
    for (const job of [{ status: 3 }, {}, null, undefined]) {
      expect(jobs.isJobSucceeded(job)).toBe(false)
    }
  })

  it('maps every teacher graph operation and merges read configs', () => {
    const signal = new AbortController().signal
    const edge = { sourceNodeCode: 'N1', targetNodeCode: 'N2', relationType: 1 }
    const node = { nodeCode: 'N3', name: 'Node 3' }
    const update = { name: 'Updated' }

    teacher.fetchTeacherGraph(4, {
      signal,
      headers: { 'X-Trace': 'teacher-graph' },
      params: { locale: 'zh', courseId: 999 }
    })
    teacher.fetchPendingEdges(4)
    teacher.acceptEdge(11)
    teacher.rejectEdge(12)
    teacher.createEdge(4, edge)
    teacher.createNode(4, node)
    teacher.updateNode(13, update)
    teacher.deleteNode(14)

    expect(http.get.mock.calls).toEqual([
      ['/teacher/graph', {
        signal,
        headers: { 'X-Trace': 'teacher-graph' },
        params: { locale: 'zh', courseId: 4 }
      }],
      ['/teacher/graph/pending-edges', { params: { courseId: 4 } }]
    ])
    expect(http.post.mock.calls).toEqual([
      ['/teacher/graph/edges/11/accept'],
      ['/teacher/graph/edges/12/reject'],
      ['/teacher/graph/edges', edge, { params: { courseId: 4 } }],
      ['/teacher/graph/nodes', node, { params: { courseId: 4 } }]
    ])
    expect(http.put.mock.calls).toEqual([
      ['/teacher/graph/nodes/13', update]
    ])
    expect(http.delete.mock.calls).toEqual([
      ['/teacher/graph/nodes/14']
    ])
  })

  it('maps teacher question review and dashboard operations', () => {
    const signal = new AbortController().signal

    teacher.fetchQuestions({
      courseId: 4,
      status: 0,
      nodeCode: 'N1',
      conf: 0.8,
      page: 2,
      size: 30
    }, {
      signal,
      params: { sort: 'confidence', courseId: 999, status: 9 }
    })
    teacher.reviewQuestions(4, [1, 2], 'accept')
    teacher.reviewAllQuestions(4, {
      status: 0,
      conf: 0.8,
      nodeCode: 'N1',
      action: 'reject',
      ignored: true
    })
    teacher.fetchDashboard(4)

    expect(http.get.mock.calls).toEqual([
      ['/teacher/questions', {
        signal,
        params: {
          sort: 'confidence',
          courseId: 4,
          status: 0,
          nodeCode: 'N1',
          conf: 0.8,
          page: 2,
          size: 30
        }
      }],
      ['/teacher/dashboard', { params: { courseId: 4 } }]
    ])
    expect(http.post.mock.calls).toEqual([
      ['/teacher/questions/review', { ids: [1, 2], action: 'accept' }, { params: { courseId: 4 } }],
      ['/teacher/questions/review-all', {
        status: 0,
        conf: 0.8,
        nodeCode: 'N1',
        action: 'reject'
      }, { params: { courseId: 4 } }]
    ])
  })

  it('maps every teacher course operation', () => {
    const signal = new AbortController().signal
    const config = { signal, params: { includeArchived: true } }

    teacher.fetchTeacherCourses(config)
    teacher.createTeacherCourse('Algorithms')
    teacher.deleteTeacherCourse(21)
    teacher.setTeacherCourseStatus(22, false)

    expect(http.get.mock.calls).toEqual([
      ['/teacher/courses', config]
    ])
    expect(http.post.mock.calls).toEqual([
      ['/teacher/courses', { name: 'Algorithms' }]
    ])
    expect(http.delete.mock.calls).toEqual([
      ['/teacher/courses/21']
    ])
    expect(http.patch.mock.calls).toEqual([
      ['/teacher/courses/22/status', { published: false }]
    ])
  })

  it('maps companion list, detail, and delete endpoints', () => {
    const listSignal = new AbortController().signal
    const detailConfig = { signal: new AbortController().signal, timeout: 50 }
    const deleteConfig = { signal: new AbortController().signal }

    companion.listConversations(7)
    companion.listConversations(7, {
      signal: listSignal,
      headers: { 'X-Trace': 'companion' },
      params: { page: 2, courseId: 99 }
    })
    companion.fetchConversation('conversation-8', detailConfig)
    companion.deleteConversation('conversation-8', deleteConfig)

    expect(http.get.mock.calls).toEqual([
      ['/companion/conversations', { params: { courseId: 7 } }],
      ['/companion/conversations', {
        signal: listSignal,
        headers: { 'X-Trace': 'companion' },
        params: { courseId: 99, page: 2 }
      }],
      ['/companion/conversations/conversation-8', detailConfig]
    ])
    expect(http.delete.mock.calls).toEqual([
      ['/companion/conversations/conversation-8', deleteConfig]
    ])
  })

  it('covers companion helper fallback branches outside the SSE transport', () => {
    expect(companion.isAbortError({ message: 'The operation was aborted.' })).toBe(true)
    expect(companion.isAbortError({ message: 'not canceled' })).toBe(false)

    const onToken = vi.fn()
    const onError = vi.fn()
    expect(companion.dispatchChatStreamEvent('token', undefined, { onToken })).toEqual({
      handled: true,
      done: false
    })
    expect(companion.dispatchChatStreamEvent('error', undefined, { onError })).toEqual({
      handled: true,
      done: false
    })
    expect(companion.dispatchChatStreamEvent('unknown', {}, {})).toEqual({
      handled: false,
      done: false
    })
    expect(onToken).toHaveBeenCalledWith('')
    expect(onError).toHaveBeenCalledWith('网络错误')
  })
})
