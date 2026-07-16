import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

const createEdgeMock = vi.fn()
const fetchTeacherGraphMock = vi.fn()
const fetchPendingEdgesMock = vi.fn()
const fetchTeacherCoursesMock = vi.fn()
const fetchExtractionReviewsMock = vi.fn()
const extractGraphFromFileMock = vi.fn()
const importGraphExcelMock = vi.fn()
const useJobControls = []

vi.mock('../../utils/echartsGraph.js', () => {
  const chart = {
    setOption: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
    dispose: vi.fn(),
    resize: vi.fn(),
    convertFromPixel: vi.fn(),
    getOption: vi.fn(() => ({ series: [{ data: [], links: [] }] }))
  }
  return {
    initChart: () => chart,
    disposeChart: vi.fn(),
    graphAriaOption: { aria: { show: true } },
    echarts: { init: () => chart, dispose: vi.fn() },
    default: { init: () => chart, dispose: vi.fn() }
  }
})

vi.mock('../../api/teacher.js', () => ({
  fetchTeacherGraph: (...a) => fetchTeacherGraphMock(...a),
  fetchPendingEdges: (...a) => fetchPendingEdgesMock(...a),
  acceptEdge: vi.fn(),
  rejectEdge: vi.fn(),
  createEdge: (...a) => createEdgeMock(...a),
  createNode: vi.fn(),
  updateNode: vi.fn(),
  deleteNode: vi.fn(),
  fetchTeacherCourses: (...a) => fetchTeacherCoursesMock(...a),
  createTeacherCourse: vi.fn(),
  deleteTeacherCourse: vi.fn(),
  setTeacherCourseStatus: vi.fn()
}))

vi.mock('../../api/admin.js', () => ({
  importGraphJson: vi.fn(),
  importGraphExcel: (...a) => importGraphExcelMock(...a),
  extractGraphFromFile: (...a) => extractGraphFromFileMock(...a),
  fetchExtractionReviews: (...a) => fetchExtractionReviewsMock(...a)
}))

vi.mock('../../composables/useJob.js', () => ({
  useJob: (options) => {
    const control = {
      options,
      loading: { value: false },
      progress: { value: 0 },
      start: vi.fn().mockResolvedValue({ id: 1, status: 0, progress: 0 })
    }
    useJobControls.push(control)
    return control
  }
}))

vi.mock('../../api/jobs.js', () => ({
  isJobSucceeded: () => false
}))

vi.mock('../../utils/graphSvgRenderer.js', () => ({
  renderGraphSvg: () => '<svg></svg>'
}))

import TeacherGraphReview from '../TeacherGraphReview.vue'

async function mountReview() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: { template: '<div />' } },
      { path: '/teacher/graph', name: 'teacher-graph', component: TeacherGraphReview },
      { path: '/teacher/graph-extract-review', name: 'extract', component: { template: '<div />' } }
    ]
  })
  await router.push('/teacher/graph')
  await router.isReady()

  const wrapper = mount(TeacherGraphReview, {
    global: {
      plugins: [pinia, router],
      stubs: {
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
  await flushPromises()
  return wrapper
}

function api() {
  return window.__wjTeacherGraph
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function graphResult(course) {
  return {
    nodes: [{ id: course, nodeCode: `${course}-NODE`, name: `${course} node`, isKey: false }],
    edges: [{ id: course, source: `${course}-NODE`, target: `${course}-TARGET`, pending: true }]
  }
}

function pendingResult(course) {
  return [{ id: course, source: `${course}-NODE`, target: `${course}-TARGET` }]
}

describe('TeacherGraphReview edge create', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useJobControls.length = 0
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: '软件工程', code: 'SE', published: true }
    ])
    fetchTeacherGraphMock.mockResolvedValue({
      nodes: [
        { id: 1, nodeCode: 'N1', name: '节点1', isKey: false },
        { id: 2, nodeCode: 'N2', name: '节点2', isKey: false }
      ],
      edges: []
    })
    fetchPendingEdgesMock.mockResolvedValue([])
    createEdgeMock.mockResolvedValue(99)
    extractGraphFromFileMock.mockResolvedValue({ id: 20, status: 0, progress: 0 })
    importGraphExcelMock.mockResolvedValue({ id: 21, status: 0, progress: 0 })
    fetchExtractionReviewsMock.mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
    delete window.__wjTeacherGraph
  })

  it('success: createEdge then reload and exit link mode', async () => {
    await mountReview()
    const hook = api()
    expect(hook).toBeTruthy()

    hook.setLinkEndpoints('N1', 'N2')
    await nextTick()
    expect(hook.state().edgeTypePopup.visible).toBe(true)
    expect(hook.state().linkMode).toBe(true)

    const reloadSpy = vi.spyOn(hook, 'reload')
    // confirmEdgeType on hook is the real function; track reload via graph fetches
    fetchTeacherGraphMock.mockClear()
    fetchPendingEdgesMock.mockClear()

    await hook.confirmEdgeType('前置')
    await flushPromises()

    expect(createEdgeMock).toHaveBeenCalledTimes(1)
    expect(createEdgeMock).toHaveBeenCalledWith(1, {
      sourceNodeCode: 'N1',
      targetNodeCode: 'N2',
      relationType: 1
    })
    // exit link mode
    expect(hook.state().linkMode).toBe(false)
    expect(hook.state().edgeTypePopup.visible).toBe(false)
    expect(hook.state().edgeError).toBeNull()
    // reload fetched graph again
    expect(fetchTeacherGraphMock).toHaveBeenCalled()
    expect(fetchPendingEdgesMock).toHaveBeenCalled()
    reloadSpy.mockRestore?.()
  })

  it('saving prevents double-click', async () => {
    let resolveCreate
    createEdgeMock.mockImplementation(
      () => new Promise((resolve) => { resolveCreate = resolve })
    )
    await mountReview()
    const hook = api()
    hook.setLinkEndpoints('N1', 'N2')

    const p1 = hook.confirmEdgeType('相关')
    await nextTick()
    expect(hook.state().edgeSaving).toBe(true)

    // second click while saving is ignored
    await hook.confirmEdgeType('相关')
    expect(createEdgeMock).toHaveBeenCalledTimes(1)

    resolveCreate(10)
    await p1
    await flushPromises()
    expect(hook.state().edgeSaving).toBe(false)
  })

  it('409 keeps endpoints and shows inline error', async () => {
    const err = new Error('边已存在')
    err.code = 409
    err.httpStatus = 409
    createEdgeMock.mockRejectedValue(err)

    await mountReview()
    const hook = api()
    hook.setLinkEndpoints('N1', 'N2')

    await hook.confirmEdgeType('包含')
    await flushPromises()

    const st = hook.state()
    expect(st.edgeError).toContain('边已存在')
    expect(st.edgeTypePopup.visible).toBe(true)
    expect(st.edgeTypePopup.source).toBe('N1')
    expect(st.edgeTypePopup.target).toBe('N2')
    // link mode stays so user can retry / adjust
    expect(st.linkMode).toBe(true)
    expect(createEdgeMock).toHaveBeenCalledWith(1, {
      sourceNodeCode: 'N1',
      targetNodeCode: 'N2',
      relationType: 2
    })
  })

  it('binds both jobs to one reactive course id and lets useJob own random request keys', async () => {
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: '软件工程', code: 'SE', published: true },
      { id: 2, name: '数据库', code: 'DB', published: true }
    ])
    const wrapper = await mountReview()
    const hook = api()
    const [extractControl, importControl] = useJobControls

    expect(useJobControls).toHaveLength(2)
    expect(extractControl.options.courseId).toBe(importControl.options.courseId)
    expect(extractControl.options.courseId.value).toBe(1)

    const syllabusFile = new File(['syllabus'], 'course.pdf', { type: 'application/pdf' })
    const syllabusInput = wrapper.find('input[accept*="image/png"]')
    Object.defineProperty(syllabusInput.element, 'files', {
      configurable: true,
      value: [syllabusFile]
    })
    await syllabusInput.trigger('change')
    await flushPromises()

    expect(extractControl.start).toHaveBeenCalledTimes(1)
    const [extractDispatch, extractOptions] = extractControl.start.mock.calls[0]
    expect(extractOptions).toEqual({ courseId: 1 })
    await extractDispatch({ 'Idempotency-Key': 'random-extract-key' })
    expect(extractGraphFromFileMock).toHaveBeenCalledWith('SE', syllabusFile, {
      headers: { 'Idempotency-Key': 'random-extract-key' }
    })

    hook.openImport()
    await nextTick()
    const excelFile = new File(['excel'], 'graph.xlsx')
    const importInput = wrapper.find('input[accept=".json,.xlsx,.xls"]')
    Object.defineProperty(importInput.element, 'files', {
      configurable: true,
      value: [excelFile]
    })
    await importInput.trigger('change')
    await flushPromises()

    expect(importControl.start).toHaveBeenCalledTimes(1)
    const [importDispatch, importOptions] = importControl.start.mock.calls[0]
    expect(importOptions).toEqual({ courseId: 1 })
    await importDispatch({ 'Idempotency-Key': 'random-import-key' })
    expect(importGraphExcelMock).toHaveBeenCalledWith(
      'SE',
      excelFile,
      expect.objectContaining({ headers: { 'Idempotency-Key': 'random-import-key' } })
    )

    await hook.switchCourse(2)
    expect(extractControl.options.courseId.value).toBe(2)
    expect(importControl.options.courseId.value).toBe(2)
  })

  it('does not let stale course-A job callbacks navigate or reload course B', async () => {
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: '软件工程', code: 'SE', published: true },
      { id: 2, name: '数据库', code: 'DB', published: true }
    ])
    const wrapper = await mountReview()
    const hook = api()
    const [extractControl, importControl] = useJobControls

    const syllabusFile = new File(['syllabus'], 'course.pdf', { type: 'application/pdf' })
    const syllabusInput = wrapper.find('input[accept*="image/png"]')
    Object.defineProperty(syllabusInput.element, 'files', {
      configurable: true,
      value: [syllabusFile]
    })
    await syllabusInput.trigger('change')
    await flushPromises()

    hook.openImport()
    await nextTick()
    const excelFile = new File(['excel'], 'graph.xlsx')
    const importInput = wrapper.find('input[accept=".json,.xlsx,.xls"]')
    Object.defineProperty(importInput.element, 'files', {
      configurable: true,
      value: [excelFile]
    })
    await importInput.trigger('change')
    await flushPromises()

    await hook.switchCourse(2)
    await flushPromises()
    fetchTeacherGraphMock.mockClear()
    fetchPendingEdgesMock.mockClear()

    extractControl.options.onSuccess({ result: { draftId: 'draft-A' } })
    importControl.options.onSuccess({ result: { nodeCount: 1, edgeCount: 0 } })
    await flushPromises()

    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/teacher/graph')
    expect(wrapper.vm.$router.currentRoute.value.query.courseCode).not.toBe('DB')
    expect(fetchTeacherGraphMock).not.toHaveBeenCalled()
    expect(fetchPendingEdgesMock).not.toHaveBeenCalled()
  })

  it('keeps course B graph, pending queue, and loading state when course A succeeds last', async () => {
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: 'Course A', code: 'A', published: true },
      { id: 2, name: 'Course B', code: 'B', published: true }
    ])
    const wrapper = await mountReview()
    const hook = api()
    const graphA = deferred()
    const graphB = deferred()
    const pendingA = deferred()
    const pendingB = deferred()

    fetchTeacherGraphMock.mockReset()
    fetchPendingEdgesMock.mockReset()
    fetchTeacherGraphMock.mockImplementation((id) => (id === 1 ? graphA.promise : graphB.promise))
    fetchPendingEdgesMock.mockImplementation((id) => (id === 1 ? pendingA.promise : pendingB.promise))

    const loadA = hook.reload()
    await nextTick()
    expect(fetchTeacherGraphMock).toHaveBeenCalledWith(1, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(fetchPendingEdgesMock).toHaveBeenCalledWith(1, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    const loadB = hook.switchCourse(2)
    await nextTick()
    const graphASignal = fetchTeacherGraphMock.mock.calls[0][1].signal
    const pendingASignal = fetchPendingEdgesMock.mock.calls[0][1].signal
    expect(graphASignal.aborted).toBe(true)
    expect(pendingASignal.aborted).toBe(true)

    graphB.resolve(graphResult('B'))
    pendingB.resolve(pendingResult('B'))
    await loadB
    await flushPromises()
    expect(hook.state().edges).toEqual(graphResult('B').edges)
    expect(hook.state().pending).toEqual(pendingResult('B'))
    expect(wrapper.text()).not.toContain('加载中')

    graphA.resolve(graphResult('A'))
    pendingA.resolve(pendingResult('A'))
    await loadA
    await flushPromises()
    expect(hook.state().edges).toEqual(graphResult('B').edges)
    expect(hook.state().pending).toEqual(pendingResult('B'))
    expect(wrapper.text()).not.toContain('加载中')
    wrapper.unmount()
  })

  it('keeps course B errors when an aborted course A request rejects last', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: 'Course A', code: 'A', published: true },
      { id: 2, name: 'Course B', code: 'B', published: true }
    ])
    const wrapper = await mountReview()
    const hook = api()
    const graphA = deferred()
    const graphB = deferred()
    const pendingA = deferred()
    const pendingB = deferred()

    fetchTeacherGraphMock.mockReset()
    fetchPendingEdgesMock.mockReset()
    fetchTeacherGraphMock.mockImplementation((id) => (id === 1 ? graphA.promise : graphB.promise))
    fetchPendingEdgesMock.mockImplementation((id) => (id === 1 ? pendingA.promise : pendingB.promise))

    const loadA = hook.reload()
    await nextTick()
    const loadB = hook.switchCourse(2)
    await nextTick()

    graphB.resolve(graphResult('B'))
    pendingB.reject(new Error('course B pending failed'))
    await loadB
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('course B pending failed')

    graphA.resolve(graphResult('A'))
    pendingA.reject(Object.assign(new Error('course A aborted'), { name: 'AbortError' }))
    await loadA
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('course B pending failed')
    expect(wrapper.text()).not.toContain('course A aborted')
    expect(hook.state().edges).not.toEqual(graphResult('A').edges)
    wrapper.unmount()
  })

  it('does not let stale course A failure clear course B loading or set an error', async () => {
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: 'Course A', code: 'A', published: true },
      { id: 2, name: 'Course B', code: 'B', published: true }
    ])
    const wrapper = await mountReview()
    const graphA = deferred()
    const graphB = deferred()
    const pendingA = deferred()
    const pendingB = deferred()

    fetchTeacherGraphMock.mockReset()
    fetchPendingEdgesMock.mockReset()
    fetchTeacherGraphMock.mockImplementation((id) => (id === 1 ? graphA.promise : graphB.promise))
    fetchPendingEdgesMock.mockImplementation((id) => (id === 1 ? pendingA.promise : pendingB.promise))

    const loadA = api().reload()
    await nextTick()
    const loadB = api().switchCourse(2)
    await nextTick()

    graphA.reject(new Error('course A graph failed'))
    pendingA.resolve(pendingResult('A'))
    await loadA
    await flushPromises()
    expect(wrapper.text()).toContain('加载中')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    graphB.resolve(graphResult('B'))
    pendingB.resolve(pendingResult('B'))
    await loadB
    await flushPromises()
    expect(wrapper.text()).not.toContain('加载中')
    expect(api().state().edges).toEqual(graphResult('B').edges)
    wrapper.unmount()
  })

  it('does not let course A extraction history write after switching to course B', async () => {
    fetchTeacherCoursesMock.mockResolvedValue([
      { id: 1, name: 'Course A', code: 'A', published: true },
      { id: 2, name: 'Course B', code: 'B', published: true }
    ])
    const wrapper = await mountReview()
    const historyA = deferred()
    fetchExtractionReviewsMock.mockReset()
    fetchExtractionReviewsMock.mockReturnValue(historyA.promise)

    const graphMenu = wrapper.findAll('button').find((button) => button.text().includes('图谱操作'))
    await graphMenu.trigger('click')
    const historyButton = wrapper.findAll('button').find((button) => button.text().includes('抽取历史'))
    await historyButton.trigger('click')
    await nextTick()
    expect(fetchExtractionReviewsMock).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    const historyASignal = fetchExtractionReviewsMock.mock.calls[0][1].signal
    await api().switchCourse(2)
    expect(historyASignal.aborted).toBe(true)

    historyA.resolve([{
      createdAt: 'A-stale-history',
      nodeRecall: 1,
      nodePrecision: 1,
      nodeKeptCount: 1,
      nodeDeletedCount: 0,
      nodeAddedCount: 0,
      edgeRecall: 1
    }])
    await flushPromises()
    expect(wrapper.text()).not.toContain('A-stale-history')
    wrapper.unmount()
  })

  it('invalidates course A history when loadCourses indirectly replaces the course', async () => {
    fetchTeacherCoursesMock.mockReset()
    fetchTeacherCoursesMock
      .mockResolvedValueOnce([
        { id: 1, name: 'Course A', code: 'A', published: true },
        { id: 2, name: 'Course B', code: 'B', published: true }
      ])
      .mockResolvedValueOnce([
        { id: 2, name: 'Course B', code: 'B', published: true }
      ])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = await mountReview()
    const historyA = deferred()
    const historyB = deferred()
    fetchExtractionReviewsMock.mockReset()
    fetchExtractionReviewsMock.mockImplementation((code) => (
      code === 'A' ? historyA.promise : historyB.promise
    ))

    const graphMenu = wrapper.findAll('button').find((button) => button.text().includes('图谱操作'))
    await graphMenu.trigger('click')
    const historyButton = wrapper.findAll('button').find((button) => button.text().includes('抽取历史'))
    await historyButton.trigger('click')
    await nextTick()
    const historyASignal = fetchExtractionReviewsMock.mock.calls[0][1].signal

    const courseMenu = wrapper.findAll('button').find((button) => button.text().includes('课程管理'))
    await courseMenu.trigger('click')
    const deleteButton = wrapper.findAll('button').find((button) => button.text().includes('删除当前课程'))
    await deleteButton.trigger('click')
    await flushPromises()

    expect.soft(historyASignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('Course B')

    const closeHistory = wrapper.findAll('button').find((button) => button.text() === '关闭')
    await closeHistory.trigger('click')
    await graphMenu.trigger('click')
    const historyButtonB = wrapper.findAll('button').find((button) => button.text().includes('抽取历史'))
    await historyButtonB.trigger('click')
    historyB.resolve([{
      createdAt: 'B-history-ok',
      nodeRecall: 1,
      nodePrecision: 1,
      nodeKeptCount: 1,
      nodeDeletedCount: 0,
      nodeAddedCount: 0,
      edgeRecall: 1
    }])
    await flushPromises()
    expect(wrapper.text()).toContain('B-history-ok')

    historyA.reject(new Error('course A history failed late'))
    await flushPromises()
    expect(wrapper.text()).toContain('B-history-ok')
    wrapper.unmount()
  })

  it('stops mount work when unmounted while courses are still loading', async () => {
    const courses = deferred()
    fetchTeacherCoursesMock.mockReset()
    fetchTeacherCoursesMock.mockReturnValue(courses.promise)
    fetchTeacherGraphMock.mockClear()
    fetchPendingEdgesMock.mockClear()
    const windowAdd = vi.spyOn(window, 'addEventListener')
    const documentAdd = vi.spyOn(document, 'addEventListener')

    const wrapper = await mountReview()
    expect(fetchTeacherCoursesMock).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    const windowCallsAtUnmount = windowAdd.mock.calls.length
    const documentCallsAtUnmount = documentAdd.mock.calls.length

    courses.resolve([{ id: 1, name: 'Course A', code: 'A', published: true }])
    await flushPromises()
    await flushPromises()

    expect(fetchTeacherGraphMock).not.toHaveBeenCalled()
    expect(fetchPendingEdgesMock).not.toHaveBeenCalled()
    expect(windowAdd.mock.calls.slice(windowCallsAtUnmount).filter(([event]) => (
      event === 'resize' || event === 'keydown'
    ))).toHaveLength(0)
    expect(documentAdd.mock.calls.slice(documentCallsAtUnmount).filter(([event]) => event === 'click'))
      .toHaveLength(0)
    expect(window.__wjTeacherGraph).toBeUndefined()
  })

  it('does not register mount listeners or DEV hook after a pending reload outlives unmount', async () => {
    const graph = deferred()
    const pendingEdges = deferred()
    fetchTeacherGraphMock.mockReset()
    fetchPendingEdgesMock.mockReset()
    fetchTeacherGraphMock.mockReturnValue(graph.promise)
    fetchPendingEdgesMock.mockReturnValue(pendingEdges.promise)
    const windowAdd = vi.spyOn(window, 'addEventListener')
    const documentAdd = vi.spyOn(document, 'addEventListener')

    const wrapper = await mountReview()
    expect(fetchTeacherGraphMock).toHaveBeenCalledTimes(1)
    expect(fetchPendingEdgesMock).toHaveBeenCalledTimes(1)
    const graphSignal = fetchTeacherGraphMock.mock.calls[0][1].signal
    const pendingSignal = fetchPendingEdgesMock.mock.calls[0][1].signal

    wrapper.unmount()
    expect(graphSignal.aborted).toBe(true)
    expect(pendingSignal.aborted).toBe(true)
    const windowCallsAtUnmount = windowAdd.mock.calls.length
    const documentCallsAtUnmount = documentAdd.mock.calls.length

    graph.resolve(graphResult('A'))
    pendingEdges.resolve(pendingResult('A'))
    await flushPromises()
    await flushPromises()

    expect(windowAdd.mock.calls.slice(windowCallsAtUnmount).filter(([event]) => (
      event === 'resize' || event === 'keydown'
    ))).toHaveLength(0)
    expect(documentAdd.mock.calls.slice(documentCallsAtUnmount).filter(([event]) => event === 'click'))
      .toHaveLength(0)
    expect(window.__wjTeacherGraph).toBeUndefined()
  })

  it('removes the DEV hook and refuses captured reloads after normal unmount', async () => {
    const wrapper = await mountReview()
    const hook = api()
    expect(hook).toBeTruthy()
    fetchTeacherGraphMock.mockClear()
    fetchPendingEdgesMock.mockClear()

    wrapper.unmount()
    expect.soft(window.__wjTeacherGraph).toBeUndefined()

    await hook.reload()
    await flushPromises()

    expect(fetchTeacherGraphMock).not.toHaveBeenCalled()
    expect(fetchPendingEdgesMock).not.toHaveBeenCalled()
  })
})
