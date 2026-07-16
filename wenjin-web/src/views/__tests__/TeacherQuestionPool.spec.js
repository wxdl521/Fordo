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
})
