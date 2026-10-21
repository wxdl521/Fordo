import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, ref, unref } from 'vue'
import { useSessionStore } from '../../store/session.js'
import { useCourseStore } from '../../store/course.js'

/** 最近一次 useGraphData 收到的 courseId 源 */
let lastCourseIdSource = null
const graphData = ref(null)
const reloadMock = vi.fn()
const fetchTodayReviewMock = vi.fn()

vi.mock('../../composables/useGraphData.js', () => ({
  useGraphData: (courseIdSource) => {
    lastCourseIdSource = courseIdSource
    return {
      data: graphData,
      loading: ref(false),
      error: ref(''),
      reload: reloadMock,
      ready: () => !!graphData.value
    }
  }
}))

vi.mock('../../composables/useTheme.js', () => ({
  useTheme: () => ({ theme: ref('paper') })
}))

vi.mock('../../composables/useViewport.js', () => ({
  useViewport: () => ({ width: ref(1200) })
}))

vi.mock('../../api/review.js', () => ({
  fetchTodayReview: (...args) => fetchTodayReviewMock(...args)
}))

vi.mock('../../api/student.js', () => ({
  fetchDailyBrief: vi.fn().mockResolvedValue({
    todayPlanTasks: [],
    reviewBacklogCount: 0,
    backlogAlert: false,
    weakDrops: [],
    planSectionVisible: false,
    atRisk: false
  }),
  fetchStudentChunk: vi.fn()
}))

vi.mock('../../utils/graphLayout.js', () => ({
  computeLayout: (data) => {
    const nodes = data?.nodes || []
    const pos = {}
    const radius = {}
    const byId = {}
    nodes.forEach((n, i) => {
      pos[n.id] = [100 + i * 40, 100]
      radius[n.id] = 8
      byId[n.id] = n
    })
    return {
      pos,
      radius,
      labelPos: {},
      byId,
      inEdges: {},
      outEdges: {},
      stars: [],
      chapters: {}
    }
  },
  shortName: (n) => n?.name || n?.id || '',
  radiusOf: () => 8
}))

import ColorMap from '../ColorMap.vue'

function courseIdPassed() {
  return unref(lastCourseIdSource)
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

async function mountMap(query = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore(pinia)
  session.setSession({
    token: 't',
    user: { id: 2, role: 2, realName: '学生' }
  })
  if (query.courseId) {
    useCourseStore(pinia).setStudentCourseId(Number(query.courseId))
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: { template: '<div />' } },
      { path: '/map', name: 'map', component: ColorMap }
    ]
  })
  await router.push({ path: '/map', query })
  await router.isReady()

  const wrapper = mount(ColorMap, {
    global: {
      plugins: [pinia, router],
      stubs: {
        ReviewPanel: {
          name: 'ReviewPanel',
          props: ['items', 'totalDecayingCount', 'courseId', 'loading', 'error'],
          template: '<div data-testid="review-panel" :data-loading="String(loading)" :data-error="error"><span v-for="item in items" :key="item.id">{{ item.id }}</span><b>{{ totalDecayingCount }}</b></div>'
        },
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
  await flushPromises()
  return { wrapper, pinia, router }
}

describe('ColorMap session/course/graph wiring', () => {
  beforeEach(() => {
    localStorage.clear()
    graphData.value = null
    lastCourseIdSource = null
    reloadMock.mockReset()
    fetchTodayReviewMock.mockReset()
    fetchTodayReviewMock.mockResolvedValue({ items: [], totalDecayingCount: 0 })
  })

  afterEach(() => {
    localStorage.clear()
    delete window.__wjColorMap
  })

  it('uses store courseId (no || 1 fallback) and shows empty without course', async () => {
    const { wrapper } = await mountMap({})
    expect(wrapper.text()).toMatch(/尚未选择课程|去选课/)
    // useGraphData 收到 null，而非默认 1
    expect(courseIdPassed()).toBeNull()
    wrapper.unmount()
  })

  it('passes courseId from query/store into useGraphData', async () => {
    graphData.value = {
      nodes: [
        {
          id: 'a',
          name: '节点A',
          chapter: '软件工程概述',
          mastery: 'unlearned',
          masteryScore: null,
          difficulty: 1,
          is_key: false
        }
      ],
      edges: []
    }
    const { wrapper } = await mountMap({ courseId: '5' })
    await nextTick()
    expect(courseIdPassed()).toBe(5)
    wrapper.unmount()
  })

  it('clears layout on course switch', async () => {
    graphData.value = {
      nodes: [
        {
          id: 'a',
          name: '节点A',
          chapter: '软件工程概述',
          mastery: 'mastered',
          masteryScore: 90,
          difficulty: 2,
          is_key: false
        }
      ],
      edges: []
    }
    const { wrapper, pinia, router } = await mountMap({ courseId: '1' })
    await nextTick()
    await flushPromises()
    expect(courseIdPassed()).toBe(1)

    await router.push({ path: '/map', query: { courseId: '5' } })
    useCourseStore(pinia).setStudentCourseId(5)
    await nextTick()
    await flushPromises()

    expect(courseIdPassed()).toBe(5)
    // 切课后不应再出现「默认课程 1」回落
    expect(courseIdPassed()).not.toBe(1)
    wrapper.unmount()
  })

  it('keeps course B review data when course A succeeds last', async () => {
    const { wrapper, router } = await mountMap({ courseId: '1' })
    const reviewButton = wrapper.findAll('button').find((button) => button.text().includes('今日复习'))
    await reviewButton.trigger('click')
    await flushPromises()
    const reviewA = deferred()
    const reviewB = deferred()
    fetchTodayReviewMock.mockReset()
    fetchTodayReviewMock.mockImplementation((id) => (id === 2 ? reviewA.promise : reviewB.promise))

    await router.push({ path: '/map', query: { courseId: '2' } })
    await nextTick()
    expect(fetchTodayReviewMock).toHaveBeenCalledWith(
      2,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    await router.push({ path: '/map', query: { courseId: '3' } })
    await nextTick()
    const courseASignal = fetchTodayReviewMock.mock.calls[0][1].signal
    expect(courseASignal.aborted).toBe(true)

    reviewB.resolve({ items: [{ id: 'B-review' }], totalDecayingCount: 1 })
    await flushPromises()
    let panel = wrapper.get('[data-testid="review-panel"]')
    expect(panel.text()).toContain('B-review')
    expect(panel.attributes('data-loading')).toBe('false')
    expect(panel.attributes('data-error')).toBe('')

    reviewA.resolve({ items: [{ id: 'A-review' }], totalDecayingCount: 9 })
    await flushPromises()
    panel = wrapper.get('[data-testid="review-panel"]')
    expect(panel.text()).toContain('B-review')
    expect(panel.text()).not.toContain('A-review')
    expect(panel.text()).toContain('1')
    expect(panel.attributes('data-loading')).toBe('false')
    wrapper.unmount()
  })

  it('does not let stale review failure clear course B loading or set an error', async () => {
    const { wrapper, router } = await mountMap({ courseId: '1' })
    const reviewButton = wrapper.findAll('button').find((button) => button.text().includes('今日复习'))
    await reviewButton.trigger('click')
    await flushPromises()
    const reviewA = deferred()
    const reviewB = deferred()
    fetchTodayReviewMock.mockReset()
    fetchTodayReviewMock.mockImplementation((id) => (id === 2 ? reviewA.promise : reviewB.promise))

    await router.push({ path: '/map', query: { courseId: '2' } })
    await nextTick()
    await router.push({ path: '/map', query: { courseId: '3' } })
    await nextTick()

    reviewA.reject(new Error('course A review failed'))
    await flushPromises()
    let panel = wrapper.get('[data-testid="review-panel"]')
    expect(panel.attributes('data-loading')).toBe('true')
    expect(panel.attributes('data-error')).toBe('')

    reviewB.reject(new Error('course B review failed'))
    await flushPromises()
    panel = wrapper.get('[data-testid="review-panel"]')
    expect(panel.attributes('data-loading')).toBe('false')
    expect(panel.attributes('data-error')).toBe('course B review failed')
    wrapper.unmount()
  })

  it('aborts and clears review state when logout invalidates the course context', async () => {
    const review = deferred()
    fetchTodayReviewMock.mockReturnValue(review.promise)
    const { wrapper, pinia } = await mountMap({})
    useCourseStore(pinia).setStudentCourseId(1)
    await nextTick()
    await flushPromises()

    expect(fetchTodayReviewMock).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    const signal = fetchTodayReviewMock.mock.calls[0][1].signal
    const state = wrapper.vm.$.setupState

    useSessionStore(pinia).clearSession()
    await nextTick()

    expect.soft(signal.aborted).toBe(true)
    expect.soft(state.reviewItems).toEqual([])
    expect.soft(state.reviewError).toBe('')
    expect.soft(state.reviewLoading).toBe(false)

    review.reject(new Error('late review failed'))
    await flushPromises()

    expect(state.reviewItems).toEqual([])
    expect(state.reviewError).toBe('')
    expect(state.reviewLoading).toBe(false)
    wrapper.unmount()
  })

  it('aborts review on logout when the course id remains pinned by the route query', async () => {
    const review = deferred()
    fetchTodayReviewMock.mockReturnValue(review.promise)
    const { wrapper, pinia } = await mountMap({ courseId: '1' })
    const signal = fetchTodayReviewMock.mock.calls[0][1].signal
    const state = wrapper.vm.$.setupState

    useSessionStore(pinia).clearSession()
    await nextTick()

    expect.soft(courseIdPassed()).toBe(1)
    expect.soft(signal.aborted).toBe(true)
    expect.soft(state.reviewItems).toEqual([])
    expect.soft(state.reviewError).toBe('')
    expect.soft(state.reviewLoading).toBe(false)

    review.reject(new Error('query review failed late'))
    await flushPromises()

    expect(state.reviewItems).toEqual([])
    expect(state.reviewError).toBe('')
    expect(state.reviewLoading).toBe(false)
    wrapper.unmount()
  })

  it('removes the DEV hook and refuses captured review loads after unmount', async () => {
    const { wrapper } = await mountMap({ courseId: '1' })
    const hook = window.__wjColorMap
    expect(hook).toBeTruthy()
    fetchTodayReviewMock.mockClear()

    wrapper.unmount()
    expect.soft(window.__wjColorMap).toBeUndefined()

    await hook.loadReview()
    await flushPromises()

    expect(fetchTodayReviewMock).not.toHaveBeenCalled()
  })

  it('exposes data-tour targets on canvas, legend, review, and root for all-unlearned graphs', async () => {
    graphData.value = {
      nodes: [
        {
          id: 'a',
          name: '节点A',
          chapter: '软件工程概述',
          mastery: 'unlearned',
          masteryScore: null,
          difficulty: 1,
          is_key: false
        },
        {
          id: 'b',
          name: '节点B',
          chapter: '软件工程概述',
          mastery: 'unlearned',
          masteryScore: null,
          difficulty: 1,
          is_key: false
        }
      ],
      edges: []
    }
    const { wrapper } = await mountMap({ courseId: '5' })
    await nextTick()

    expect(wrapper.find('[data-tour="map-canvas"]').exists()).toBe(true)
    expect(wrapper.find('[data-tour="map-legend"]').exists()).toBe(true)
    expect(wrapper.find('[data-tour="map-review"]').exists()).toBe(true)
    expect(wrapper.find('[data-tour="map-root"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('示例染色 · 诊断后变成你的学情')
    wrapper.unmount()
  })
})
