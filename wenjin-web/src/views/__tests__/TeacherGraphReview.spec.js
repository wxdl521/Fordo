import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

const createEdgeMock = vi.fn()
const fetchTeacherGraphMock = vi.fn()
const fetchPendingEdgesMock = vi.fn()
const fetchTeacherCoursesMock = vi.fn()

vi.mock('echarts', () => {
  const chart = {
    setOption: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
    dispose: vi.fn(),
    resize: vi.fn(),
    convertFromPixel: vi.fn()
  }
  return {
    init: () => chart,
    dispose: vi.fn()
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
  importGraphExcel: vi.fn(),
  extractGraphFromFile: vi.fn(),
  fetchExtractionReviews: vi.fn().mockResolvedValue([])
}))

vi.mock('../../composables/useJob.js', () => ({
  useJob: () => ({
    loading: { value: false },
    progress: { value: 0 },
    start: vi.fn()
  })
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

describe('TeacherGraphReview edge create', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
  })

  afterEach(() => {
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
})
