import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'

const mocks = vi.hoisted(() => ({
  annotateQuestions: vi.fn(),
  cancelJob: vi.fn(),
  fetchQuestions: vi.fn(),
  fetchTeacherGraph: vi.fn(),
  generateQuestions: vi.fn(),
  getJob: vi.fn(),
  importQuestionExcel: vi.fn(),
  importQuestionJson: vi.fn(),
  reviewAllQuestions: vi.fn(),
  reviewQuestions: vi.fn()
}))

const courseState = vi.hoisted(() => ({
  currentCourse: null,
  courseId: null
}))

vi.mock('../../api/teacher.js', () => ({
  fetchQuestions: (...args) => mocks.fetchQuestions(...args),
  reviewQuestions: (...args) => mocks.reviewQuestions(...args),
  fetchTeacherGraph: (...args) => mocks.fetchTeacherGraph(...args),
  reviewAllQuestions: (...args) => mocks.reviewAllQuestions(...args)
}))

vi.mock('../../api/admin.js', () => ({
  generateQuestions: (...args) => mocks.generateQuestions(...args),
  annotateQuestions: (...args) => mocks.annotateQuestions(...args),
  importQuestionJson: (...args) => mocks.importQuestionJson(...args),
  importQuestionExcel: (...args) => mocks.importQuestionExcel(...args)
}))

vi.mock('../../api/jobs.js', () => ({
  getJob: (...args) => mocks.getJob(...args),
  cancelJob: (...args) => mocks.cancelJob(...args),
  isJobTerminal: (job) => [2, 3, 4].includes(job?.status),
  isJobSucceeded: (job) => job?.status === 2
}))

vi.mock('../../composables/useTeacherCourse.js', () => ({
  useTeacherCourse: () => courseState
}))

import TeacherQuestionPool from '../TeacherQuestionPool.vue'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function questionPage(course) {
  return {
    items: [{
      id: `${course}-question`,
      status: 0,
      mainNodeCode: `${course}-NODE`,
      mainNodeName: `${course} node`,
      type: 1,
      difficulty: 1,
      confidence: 90,
      source: 'test',
      stem: `${course} question`,
      options: [],
      createdAt: '2026-01-01T00:00:00Z'
    }],
    total: 1,
    counts: { pending: 1, passed: 0, rejected: 0 }
  }
}

function graphPage(course) {
  return {
    nodes: [{ nodeCode: `${course}-NODE`, name: `${course} graph node`, chapter: course }],
    edges: []
  }
}

function findButton(wrapper, label) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(label))
  expect(button, `button containing ${label}`).toBeTruthy()
  return button
}

async function mountPool() {
  const wrapper = mount(TeacherQuestionPool)
  await flushPromises()
  await findButton(wrapper, '批量工具').trigger('click')
  await nextTick()
  return wrapper
}

describe('TeacherQuestionPool batch job cancellation', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', vi.fn(() => true))
    courseState.currentCourse = ref({ id: 1, name: '软件工程', code: 'SE' })
    courseState.courseId = ref(1)
    mocks.fetchQuestions.mockResolvedValue({
      items: [],
      total: 0,
      counts: { pending: 0, passed: 0, rejected: 0 }
    })
    mocks.fetchTeacherGraph.mockResolvedValue({
      nodes: [
        { nodeCode: 'N1', name: '节点一' },
        { nodeCode: 'N2', name: '节点二' }
      ],
      edges: []
    })
    mocks.getJob.mockResolvedValue({ id: 71, status: 1, progress: 10 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('cancels the server job even when cancel is clicked before dispatch returns', async () => {
    const dispatch = deferred()
    const serverCancel = deferred()
    mocks.generateQuestions.mockReturnValueOnce(dispatch.promise)
    mocks.cancelJob.mockReturnValueOnce(serverCancel.promise)

    const wrapper = await mountPool()
    await findButton(wrapper, '一键全节点生成').trigger('click')
    await nextTick()

    expect(mocks.generateQuestions).toHaveBeenCalledTimes(1)
    expect(mocks.generateQuestions.mock.calls[0][3].headers['Idempotency-Key'])
      .toEqual(expect.any(String))

    await findButton(wrapper, '取消').trigger('click')
    expect(mocks.cancelJob).not.toHaveBeenCalled()

    dispatch.resolve({ id: 71, status: 1, progress: 0 })
    await flushPromises()

    expect(mocks.cancelJob).toHaveBeenCalledTimes(1)
    expect(mocks.cancelJob).toHaveBeenCalledWith(71)
    expect(wrapper.text()).not.toContain('已取消')

    serverCancel.resolve({ id: 71, status: 4, progress: 0, errorCode: 'CANCELLED' })
    await flushPromises()

    expect(mocks.generateQuestions).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('已取消')
    wrapper.unmount()
  })

  it('keeps course B questions and loading state when course A succeeds last', async () => {
    const wrapper = await mountPool()
    const questionsA = deferred()
    const questionsB = deferred()
    mocks.fetchQuestions.mockReset()
    mocks.fetchQuestions.mockImplementation(({ courseId }) => (
      courseId === 2 ? questionsA.promise : questionsB.promise
    ))

    courseState.currentCourse.value = { id: 2, name: 'Course A', code: 'A' }
    courseState.courseId.value = 2
    await nextTick()
    expect(mocks.fetchQuestions).toHaveBeenCalledWith(
      expect.objectContaining({ courseId: 2 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    courseState.currentCourse.value = { id: 3, name: 'Course B', code: 'B' }
    courseState.courseId.value = 3
    await nextTick()
    const courseASignal = mocks.fetchQuestions.mock.calls[0][1].signal
    expect(courseASignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('加载中')

    questionsB.resolve(questionPage('B'))
    await flushPromises()
    expect(wrapper.text()).toContain('B question')
    expect(wrapper.text()).not.toContain('A question')
    expect(wrapper.text()).not.toContain('加载中')

    questionsA.resolve(questionPage('A'))
    await flushPromises()
    expect(wrapper.text()).toContain('B question')
    expect(wrapper.text()).not.toContain('A question')
    expect(wrapper.text()).not.toContain('加载中')
    wrapper.unmount()
  })

  it('keeps course B graph nodes when course A graph succeeds last', async () => {
    const wrapper = await mountPool()
    const graphA = deferred()
    const graphB = deferred()
    mocks.fetchTeacherGraph.mockReset()
    mocks.fetchTeacherGraph.mockImplementation((courseId) => (
      courseId === 2 ? graphA.promise : graphB.promise
    ))

    courseState.currentCourse.value = { id: 2, name: 'Course A', code: 'A' }
    courseState.courseId.value = 2
    await nextTick()
    expect(mocks.fetchTeacherGraph).toHaveBeenCalledWith(
      2,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    courseState.currentCourse.value = { id: 3, name: 'Course B', code: 'B' }
    courseState.courseId.value = 3
    await nextTick()
    const courseASignal = mocks.fetchTeacherGraph.mock.calls[0][1].signal
    expect(courseASignal.aborted).toBe(true)

    graphB.resolve(graphPage('B'))
    await flushPromises()
    expect(wrapper.text()).toContain('B graph node')
    expect(wrapper.text()).not.toContain('A graph node')

    graphA.resolve(graphPage('A'))
    await flushPromises()
    expect(wrapper.text()).toContain('B graph node')
    expect(wrapper.text()).not.toContain('A graph node')
    wrapper.unmount()
  })

  it('keeps course B question error when course A abort rejects last', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const wrapper = await mountPool()
    const questionsA = deferred()
    const questionsB = deferred()
    mocks.fetchQuestions.mockReset()
    mocks.fetchQuestions.mockImplementation(({ courseId }) => (
      courseId === 2 ? questionsA.promise : questionsB.promise
    ))

    courseState.courseId.value = 2
    await nextTick()
    courseState.courseId.value = 3
    await nextTick()

    questionsB.reject(new Error('course B questions failed'))
    await flushPromises()
    expect(wrapper.get('[data-testid="question-load-error"]').text()).toContain('course B questions failed')

    questionsA.reject(Object.assign(new Error('course A aborted'), { name: 'AbortError' }))
    await flushPromises()
    expect(wrapper.get('[data-testid="question-load-error"]').text()).toContain('course B questions failed')
    expect(wrapper.text()).not.toContain('course A aborted')
    wrapper.unmount()
  })

  it('does not let stale course A question failure clear course B loading or set an error', async () => {
    const wrapper = await mountPool()
    const questionsA = deferred()
    const questionsB = deferred()
    mocks.fetchQuestions.mockReset()
    mocks.fetchQuestions.mockImplementation(({ courseId }) => (
      courseId === 2 ? questionsA.promise : questionsB.promise
    ))

    courseState.courseId.value = 2
    await nextTick()
    courseState.courseId.value = 3
    await nextTick()

    questionsA.reject(new Error('course A questions failed'))
    await flushPromises()
    expect(wrapper.text()).toContain('加载中')
    expect(wrapper.find('[data-testid="question-load-error"]').exists()).toBe(false)

    questionsB.resolve(questionPage('B'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('加载中')
    expect(wrapper.text()).toContain('B question')
    wrapper.unmount()
  })

  it('keeps course B graph error when course A graph succeeds last', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const wrapper = await mountPool()
    const graphA = deferred()
    const graphB = deferred()
    mocks.fetchTeacherGraph.mockReset()
    mocks.fetchTeacherGraph.mockImplementation((courseId) => (
      courseId === 2 ? graphA.promise : graphB.promise
    ))

    courseState.courseId.value = 2
    await nextTick()
    courseState.courseId.value = 3
    await nextTick()

    graphB.reject(new Error('course B graph failed'))
    await flushPromises()
    expect(wrapper.get('[data-testid="graph-nodes-error"]').text()).toContain('course B graph failed')

    graphA.resolve(graphPage('A'))
    await flushPromises()
    expect(wrapper.get('[data-testid="graph-nodes-error"]').text()).toContain('course B graph failed')
    expect(wrapper.text()).not.toContain('A graph node')
    wrapper.unmount()
  })
})
