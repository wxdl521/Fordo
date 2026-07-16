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

const useJobControls = []

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

vi.mock('../../composables/useJob.js', () => ({
  useJob: (options) => {
    const valueRef = (value) => ({ __v_isRef: true, value })
    const control = {
      options,
      loading: valueRef(false),
      progress: valueRef(0),
      start: vi.fn().mockResolvedValue({ id: 1, status: 0 })
    }
    useJobControls.push(control)
    return control
  }
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
    useJobControls.length = 0
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
    delete window.__wjTeacherQuestions
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

  it('does not reload or write state when review resolves after unmount', async () => {
    const review = deferred()
    mocks.fetchQuestions.mockResolvedValue(questionPage('A'))
    mocks.reviewQuestions.mockReturnValue(review.promise)
    const wrapper = await mountPool()
    const hook = window.__wjTeacherQuestions
    expect(hook).toBeTruthy()
    const mountedState = hook.state()

    mocks.fetchQuestions.mockReset()
    mocks.fetchQuestions.mockResolvedValue(questionPage('late'))
    mocks.fetchTeacherGraph.mockClear()
    const passButton = wrapper.findAll('button').find((button) => button.text() === '通过')
    expect(passButton).toBeTruthy()
    await passButton.trigger('click')
    expect(mocks.reviewQuestions).toHaveBeenCalledWith(1, ['A-question'], 'pass')

    wrapper.unmount()
    expect.soft(window.__wjTeacherQuestions).toBeUndefined()

    review.resolve()
    await flushPromises()

    expect.soft(mocks.fetchQuestions).not.toHaveBeenCalled()
    expect.soft(mocks.fetchTeacherGraph).not.toHaveBeenCalled()
    expect(hook.state()).toBe(mountedState)
  })

  it.each([
    ['success', 'resolve'],
    ['failure', 'reject']
  ])('keeps course B approve-all state when course A %s arrives late', async (_label, outcome) => {
    mocks.fetchQuestions.mockResolvedValue(questionPage('A'))
    const action = deferred()
    mocks.reviewAllQuestions.mockReturnValue(action.promise)
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    const actionPromise = state.handleApproveAll()

    mocks.fetchQuestions.mockResolvedValue(questionPage('B'))
    mocks.fetchTeacherGraph.mockResolvedValue(graphPage('B'))
    courseState.currentCourse.value = { id: 2, name: 'Course B', code: 'B' }
    courseState.courseId.value = 2
    await nextTick()
    await flushPromises()
    mocks.fetchQuestions.mockClear()
    state.checked = { 'B-question': true }
    state.approveAllLoading = true
    state.approveAllMessage = 'B approval in progress'

    if (outcome === 'resolve') action.resolve(7)
    else action.reject(new Error('course A approval failed'))
    await actionPromise
    await flushPromises()

    expect.soft(mocks.fetchQuestions).not.toHaveBeenCalled()
    expect.soft(state.checked).toEqual({ 'B-question': true })
    expect.soft(state.approveAllLoading).toBe(true)
    expect(state.approveAllMessage).toBe('B approval in progress')
    wrapper.unmount()
  })

  it('keeps course B JSON import state when course A import resolves late', async () => {
    const action = deferred()
    mocks.importQuestionJson.mockReturnValue(action.promise)
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    const file = new File(['{}'], 'questions.json', { type: 'application/json' })
    state.handleImportSelect({ target: { files: [file] } })
    const importPromise = state.handleImportFile()

    mocks.fetchQuestions.mockResolvedValue(questionPage('B'))
    mocks.fetchTeacherGraph.mockResolvedValue(graphPage('B'))
    courseState.currentCourse.value = { id: 2, name: 'Course B', code: 'B' }
    courseState.courseId.value = 2
    await nextTick()
    await flushPromises()
    mocks.fetchQuestions.mockClear()
    state.importLoading = true
    state.importResult = { imported: 22, skipped: 0, course: 'B' }
    state.importError = 'B import in progress'

    action.resolve({ imported: 1, skipped: 0, course: 'A' })
    await importPromise
    await flushPromises()

    expect.soft(mocks.fetchQuestions).not.toHaveBeenCalled()
    expect.soft(state.importLoading).toBe(true)
    expect.soft(state.importError).toBe('B import in progress')
    expect(state.importResult).toEqual({ imported: 22, skipped: 0, course: 'B' })
    wrapper.unmount()
  })

  it('clears completed course A action UI state when switching to course B', async () => {
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    const importFile = new File(['{}'], 'course-a.json', { type: 'application/json' })

    state.checked = { 'A-question': true }
    state.genResult = { generated: 3, course: 'A' }
    state.genError = 'course A generation error'
    state.batchLoading = true
    state.batchProgress = { index: 1, total: 1, nodeCode: 'A-NODE', nodeName: 'A node' }
    state.batchResult = { generated: 3, dropped: 0, duplicated: 0, failed: [], cancelled: false, course: 'A' }
    state.batchError = 'course A batch error'
    state.batchCancelRequested = true
    state.batchCancelAccepted = true
    state.batchCancelFailed = true
    state.importLoading = true
    state.importResult = { imported: 4, course: 'A' }
    state.importError = 'course A import error'
    state.importFile = importFile
    state.importDrag = true
    state.annotateResult = [{ id: 'A-annotation' }]
    state.annotateError = 'course A annotation error'
    state.approveAllLoading = true
    state.approveAllMessage = 'course A approval result'

    courseState.currentCourse.value = { id: 2, name: 'Course B', code: 'B' }
    courseState.courseId.value = 2
    await nextTick()

    expect.soft(state.checked).toEqual({})
    expect.soft(state.genResult).toBeNull()
    expect.soft(state.genError).toBe('')
    expect.soft(state.batchLoading).toBe(false)
    expect.soft(state.batchProgress).toBeNull()
    expect.soft(state.batchResult).toBeNull()
    expect.soft(state.batchError).toBe('')
    expect.soft(state.batchCancelRequested).toBe(false)
    expect.soft(state.batchCancelAccepted).toBe(false)
    expect.soft(state.batchCancelFailed).toBe(false)
    expect.soft(state.importLoading).toBe(false)
    expect.soft(state.importResult).toBeNull()
    expect.soft(state.importError).toBe('')
    expect.soft(state.importFile).toBeNull()
    expect.soft(state.importDrag).toBe(false)
    expect.soft(state.annotateResult).toBeNull()
    expect.soft(state.annotateError).toBe('')
    expect.soft(state.approveAllLoading).toBe(false)
    expect(state.approveAllMessage).toBe('')
    wrapper.unmount()
  })

  it('does not publish a stale useJob wrapper error after switching courses', async () => {
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    const action = deferred()
    useJobControls[0].start.mockReturnValue(action.promise)
    const generatePromise = state.handleGenerate()

    courseState.currentCourse.value = { id: 2, name: 'Course B', code: 'B' }
    courseState.courseId.value = 2
    await nextTick()
    state.genError = 'B generation error'

    action.reject(new Error('course A generation failed'))
    await generatePromise

    expect(state.genError).toBe('B generation error')
    wrapper.unmount()
  })

  it('stops the course A custom batch without sending course B nodes or changing B state', async () => {
    const action = deferred()
    mocks.generateQuestions.mockReturnValueOnce(action.promise)
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    const batchPromise = state.handleBatchGenerate()

    mocks.fetchQuestions.mockResolvedValue(questionPage('B'))
    mocks.fetchTeacherGraph.mockResolvedValue({
      nodes: [
        { nodeCode: 'B-N1', name: 'B node 1' },
        { nodeCode: 'B-N2', name: 'B node 2' }
      ],
      edges: []
    })
    courseState.currentCourse.value = { id: 2, name: 'Course B', code: 'B' }
    courseState.courseId.value = 2
    await nextTick()
    await flushPromises()
    mocks.fetchQuestions.mockClear()
    state.batchLoading = true
    state.batchProgress = { index: 1, total: 2, nodeCode: 'B-N1', nodeName: 'B node 1' }
    state.batchResult = { generated: 20, dropped: 0, duplicated: 0, failed: [], cancelled: false, course: 'B' }
    state.batchError = 'B batch in progress'

    action.resolve({ id: 71, status: 2, result: { generated: 3, dropped: 0, duplicated: 0 } })
    await batchPromise
    await flushPromises()

    expect.soft(mocks.generateQuestions).toHaveBeenCalledTimes(1)
    expect.soft(mocks.fetchQuestions).not.toHaveBeenCalled()
    expect.soft(state.batchLoading).toBe(true)
    expect.soft(state.batchProgress?.nodeCode).toBe('B-N1')
    expect.soft(state.batchError).toBe('B batch in progress')
    expect(state.batchResult).toEqual({ generated: 20, dropped: 0, duplicated: 0, failed: [], cancelled: false, course: 'B' })
    wrapper.unmount()
  })

  it('does not commit custom batch completion after unmount', async () => {
    const action = deferred()
    mocks.generateQuestions.mockReturnValue(action.promise)
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    const batchPromise = state.handleBatchGenerate()
    const snapshot = {
      loading: state.batchLoading,
      progress: state.batchProgress,
      result: { ...state.batchResult }
    }

    wrapper.unmount()
    action.resolve({ id: 72, status: 2, result: { generated: 4, dropped: 0, duplicated: 0 } })
    await batchPromise
    await flushPromises()

    expect(state.batchLoading).toBe(snapshot.loading)
    expect(state.batchProgress).toEqual(snapshot.progress)
    expect(state.batchResult).toEqual(snapshot.result)
  })

  it('ignores a course A cancellation response after switching to course B', async () => {
    const cancellation = deferred()
    mocks.generateQuestions.mockResolvedValue({ id: 73, status: 1, progress: 0 })
    mocks.cancelJob.mockReturnValue(cancellation.promise)
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState
    void state.handleBatchGenerate()
    await flushPromises()
    expect(mocks.generateQuestions).toHaveBeenCalledTimes(1)
    const cancelPromise = state.cancelBatchGenerate()
    expect(mocks.cancelJob).toHaveBeenCalledWith(73)

    courseState.currentCourse.value = { id: 2, name: 'Course B', code: 'B' }
    courseState.courseId.value = 2
    await nextTick()
    state.batchLoading = true
    state.batchResult = { generated: 9, dropped: 0, duplicated: 0, failed: [], cancelled: false, course: 'B' }
    state.batchError = 'B cancellation state'

    cancellation.resolve({ id: 73, status: 4, progress: 0, errorCode: 'CANCELLED' })
    await cancelPromise
    await flushPromises()

    expect.soft(state.batchLoading).toBe(true)
    expect.soft(state.batchError).toBe('B cancellation state')
    expect(state.batchResult).toEqual({ generated: 9, dropped: 0, duplicated: 0, failed: [], cancelled: false, course: 'B' })
    wrapper.unmount()
  })

  it.each([
    ['success', 'resolve'],
    ['failure', 'reject']
  ])('ignores same-course run 1 cancellation %s after run 2 starts', async (_label, outcome) => {
    const staleCancellation = deferred()
    mocks.fetchTeacherGraph.mockResolvedValue({
      nodes: [{ nodeCode: 'N1', name: '节点一' }],
      edges: []
    })
    mocks.generateQuestions
      .mockResolvedValueOnce({ id: 73, status: 1, progress: 0 })
      .mockResolvedValueOnce({ id: 74, status: 1, progress: 0 })
    mocks.cancelJob.mockReturnValue(staleCancellation.promise)
    const wrapper = await mountPool()
    const state = wrapper.vm.$.setupState

    const run1Promise = state.handleBatchGenerate()
    await flushPromises()
    expect(mocks.generateQuestions).toHaveBeenCalledTimes(1)
    const staleCancelPromise = state.cancelBatchGenerate()
    expect(mocks.cancelJob).toHaveBeenCalledWith(73)

    const run2Promise = state.handleBatchGenerate()
    await flushPromises()
    expect(mocks.generateQuestions).toHaveBeenCalledTimes(2)
    const run2Progress = { ...state.batchProgress }
    const run2Result = { ...state.batchResult, failed: [...state.batchResult.failed] }

    if (outcome === 'resolve') {
      staleCancellation.resolve({ id: 73, status: 4, progress: 0, errorCode: 'CANCELLED' })
    } else {
      staleCancellation.reject(new Error('run 1 cancellation failed'))
    }
    await staleCancelPromise
    await flushPromises()

    expect.soft(state.batchLoading).toBe(true)
    expect.soft(state.batchProgress).toEqual(run2Progress)
    expect.soft(state.batchResult).toEqual(run2Result)
    expect.soft(state.batchError).toBe('')
    expect.soft(state.batchCancelRequested).toBe(false)
    expect.soft(state.batchCancelAccepted).toBe(false)
    expect.soft(state.batchCancelFailed).toBe(false)
    expect.soft(state.activeBatchJobId).toBe(74)
    expect.soft(state.activeBatchCancel).toBeNull()
    expect.soft(state.batchActionCourseId).toBe(1)
    expect.soft(mocks.cancelJob).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(1000)
    await Promise.all([run1Promise, run2Promise])
  })
})
