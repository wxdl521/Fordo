import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, reactive, ref } from 'vue'
import { useSessionStore } from '../../store/session.js'

const mocks = vi.hoisted(() => ({
  disposeChart: vi.fn(),
  fetchDashboard: vi.fn(),
  graphStore: null,
  initChart: vi.fn()
}))

const courseState = vi.hoisted(() => ({ courseId: null }))

vi.mock('../../api/teacher.js', () => ({
  fetchDashboard: (...args) => mocks.fetchDashboard(...args)
}))

vi.mock('../../composables/useTeacherCourse.js', () => ({
  useTeacherCourse: () => courseState
}))

vi.mock('../../store/graph.js', () => ({
  useGraphStore: () => mocks.graphStore
}))

vi.mock('../../utils/echartsGraph.js', () => ({
  disposeChart: (...args) => mocks.disposeChart(...args),
  graphAriaOption: {},
  initChart: (...args) => mocks.initChart(...args)
}))

import TeacherDashboard from '../TeacherDashboard.vue'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function dashboardData(label) {
  return {
    label,
    summary: {
      totalStudents: 1,
      diagnosedStudents: 1,
      classAvgRate: 0.8,
      nodeCount: 0
    },
    nodes: [],
    weakRanking: []
  }
}

function chartStub() {
  return {
    getOption: vi.fn(() => ({ series: [{ data: [], links: [] }] })),
    off: vi.fn(),
    on: vi.fn(),
    resize: vi.fn(),
    setOption: vi.fn()
  }
}

function createGraphStoreStub() {
  return reactive({
    $state: {},
    nodes: [],
    edges: [],
    error: '',
    loadedCourseId: null,
    load: vi.fn(async (id) => {
      if (id == null) {
        mocks.graphStore.nodes = []
        mocks.graphStore.edges = []
        mocks.graphStore.error = ''
        mocks.graphStore.loadedCourseId = null
        return
      }
      mocks.graphStore.error = ''
      mocks.graphStore.loadedCourseId = Number(id)
    })
  })
}

async function mountDashboard(initialCourseId = 1, options = {}) {
  courseState.courseId = ref(initialCourseId)
  const graphStore = options.graphStore || createGraphStoreStub()
  mocks.graphStore = graphStore
  const pinia = options.pinia || createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 'teacher-token', user: { id: 3, role: 1 } })
  const wrapper = mount(TeacherDashboard, { global: { plugins: [pinia] } })
  await nextTick()
  return { wrapper, pinia, graphStore, courseId: courseState.courseId }
}

describe('TeacherDashboard request ownership', () => {
  beforeEach(() => {
    mocks.disposeChart.mockReset()
    mocks.fetchDashboard.mockReset()
    mocks.initChart.mockReset()
  })

  afterEach(() => {
    delete window.__wjTeacherDashboard
  })

  it('handles the initial course correction as take-latest double loading', async () => {
    const staleCourse = deferred()
    const correctedCourse = deferred()
    mocks.fetchDashboard.mockImplementation((id) => (id === 1 ? staleCourse.promise : correctedCourse.promise))

    const { wrapper } = await mountDashboard(1)
    const staleSignal = mocks.fetchDashboard.mock.calls[0][1].signal
    courseState.courseId.value = 2
    await nextTick()

    expect.soft(staleSignal.aborted).toBe(true)
    expect.soft(mocks.fetchDashboard.mock.calls[1][1].signal).toEqual(expect.any(AbortSignal))
    correctedCourse.resolve(dashboardData('course-b'))
    await flushPromises()
    staleCourse.resolve(dashboardData('course-a'))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.dashboard.label).toBe('course-b')
    expect.soft(state.loading).toBe(false)
    expect(state.error).toBe('')
    wrapper.unmount()
  })

  it('does not let a stale failure clear corrected-course loading', async () => {
    const staleCourse = deferred()
    const correctedCourse = deferred()
    mocks.fetchDashboard.mockImplementation((id) => (id === 1 ? staleCourse.promise : correctedCourse.promise))

    const { wrapper } = await mountDashboard(1)
    courseState.courseId.value = 2
    await nextTick()
    staleCourse.reject(new Error('stale course failed'))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.loading).toBe(true)
    expect.soft(state.error).toBe('')
    correctedCourse.resolve(dashboardData('course-b'))
    await flushPromises()
    expect.soft(state.dashboard.label).toBe('course-b')
    expect.soft(state.loading).toBe(false)
    expect(state.error).toBe('')
    wrapper.unmount()
  })

  it.each([
    ['the graph store reports an error', 'graph B unavailable', 'graph B unavailable'],
    ['the graph store does not commit the requested course', '', '图谱加载失败，请稍后重试。']
  ])('keeps dashboard empty and clears the old chart when %s', async (_case, graphError, expectedError) => {
    const chart = chartStub()
    const graphStore = createGraphStoreStub()
    graphStore.load.mockImplementation(async (id) => {
      graphStore.error = ''
      graphStore.loadedCourseId = Number(id)
      graphStore.nodes = [{ nodeCode: `N-${id}`, name: `Course ${id} node`, isKey: false }]
      graphStore.edges = []
    })
    mocks.initChart.mockReturnValue(chart)
    mocks.fetchDashboard
      .mockResolvedValueOnce(dashboardData('course-a'))
      .mockResolvedValueOnce(dashboardData('course-b'))

    const { wrapper } = await mountDashboard(1, { graphStore })
    await flushPromises()
    expect(mocks.initChart).toHaveBeenCalledTimes(1)

    mocks.graphStore.load.mockImplementationOnce(async () => {
      mocks.graphStore.error = graphError
      // Keep loadedCourseId and nodes on course A to model a swallowed/aborted graph load.
    })
    courseState.courseId.value = 2
    await nextTick()
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.dashboard).toBeNull()
    expect.soft(state.loading).toBe(false)
    expect.soft(state.error).toBe(expectedError)
    expect(mocks.disposeChart).toHaveBeenCalledWith(chart)
    wrapper.unmount()
  })

  it('keeps course A chart bound to its committed graph snapshot when the shared store moves to B', async () => {
    const chart = chartStub()
    const sharedStore = createGraphStoreStub()
    sharedStore.load.mockImplementation(async (id) => {
      sharedStore.error = ''
      sharedStore.loadedCourseId = Number(id)
      sharedStore.nodes = [{ nodeCode: `N-${id}`, name: `Node ${id}`, isKey: false }]
      sharedStore.edges = []
    })
    mocks.initChart.mockReturnValue(chart)
    mocks.fetchDashboard.mockResolvedValue(dashboardData('course-a'))

    const { wrapper } = await mountDashboard(1, { graphStore: sharedStore })
    await flushPromises()
    const renderCount = chart.setOption.mock.calls.length
    expect(renderCount).toBeGreaterThan(0)

    sharedStore.loadedCourseId = 2
    sharedStore.nodes = [{ nodeCode: 'N-2', name: 'Node 2', isKey: false }]
    sharedStore.edges = []
    await nextTick()

    const state = wrapper.vm.$.setupState
    expect.soft(chart.setOption).toHaveBeenCalledTimes(renderCount)
    expect.soft(state.graphCourseId).toBe(1)
    expect(state.graphNodes.map((node) => node.nodeCode)).toEqual(['N-1'])
    wrapper.unmount()
  })

  it('does not let old course A unmount cancel the shared store load owned by B', async () => {
    const graphB = deferred()
    const dashboardB = deferred()
    const sharedStore = createGraphStoreStub()
    sharedStore.load.mockImplementation(async (id) => {
      if (Number(id) === 1) {
        sharedStore.error = ''
        sharedStore.loadedCourseId = 1
        sharedStore.nodes = [{ nodeCode: 'N-1', name: 'Node 1', isKey: false }]
        sharedStore.edges = []
        return
      }
      await graphB.promise
      sharedStore.error = ''
      sharedStore.loadedCourseId = 2
      sharedStore.nodes = [{ nodeCode: 'N-2', name: 'Node 2', isKey: false }]
      sharedStore.edges = []
    })
    mocks.fetchDashboard.mockImplementation((id) => (
      Number(id) === 1 ? Promise.resolve(dashboardData('course-a')) : dashboardB.promise
    ))
    mocks.initChart.mockImplementation(() => chartStub())
    const pinia = createPinia()

    const courseA = await mountDashboard(1, { graphStore: sharedStore, pinia })
    await flushPromises()
    const courseB = await mountDashboard(2, { graphStore: sharedStore, pinia })
    await nextTick()
    const bLoad = sharedStore.load.mock.calls.find(([id]) => Number(id) === 2)
    const bOwnerSignal = bLoad[1].signal

    courseA.wrapper.unmount()

    expect.soft(bOwnerSignal.aborted).toBe(false)
    expect(sharedStore.load.mock.calls.some(([id]) => id == null)).toBe(false)

    graphB.resolve()
    dashboardB.resolve(dashboardData('course-b'))
    await flushPromises()

    const stateB = courseB.wrapper.vm.$.setupState
    expect.soft(stateB.dashboard.label).toBe('course-b')
    expect.soft(stateB.graphCourseId).toBe(2)
    expect(stateB.graphNodes.map((node) => node.nodeCode)).toEqual(['N-2'])
    courseB.wrapper.unmount()
  })

  it('aborts and ignores the dashboard load after logout', async () => {
    const pending = deferred()
    mocks.fetchDashboard.mockReturnValue(pending.promise)
    const { wrapper, pinia } = await mountDashboard()
    const signal = mocks.fetchDashboard.mock.calls[0][1].signal

    useSessionStore(pinia).clearSession({ clearCourse: false })
    await nextTick()
    expect(signal.aborted).toBe(true)
    expect(mocks.graphStore.load.mock.calls.some(([id]) => id == null)).toBe(false)
    pending.resolve(dashboardData('old-owner'))
    await flushPromises()

    expect.soft(wrapper.vm.$.setupState.dashboard).toBeNull()
    expect(wrapper.vm.$.setupState.loading).toBe(false)
    wrapper.unmount()
  })

  it('aborts the active dashboard request on unmount', async () => {
    const pending = deferred()
    mocks.fetchDashboard.mockReturnValue(pending.promise)
    const { wrapper } = await mountDashboard()
    const signal = mocks.fetchDashboard.mock.calls[0][1].signal

    wrapper.unmount()
    expect.soft(signal.aborted).toBe(true)
    expect.soft(window.__wjTeacherDashboard).toBeUndefined()
    expect.soft(mocks.graphStore.load.mock.calls.some(([id]) => id == null)).toBe(false)
    pending.resolve(dashboardData('late'))
    await flushPromises()

    expect(mocks.fetchDashboard).toHaveBeenCalledTimes(1)
  })
})
