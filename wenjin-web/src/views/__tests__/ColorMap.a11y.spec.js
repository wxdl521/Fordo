import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, ref } from 'vue'
import { useSessionStore } from '../../store/session.js'
import { useCourseStore } from '../../store/course.js'

const graphData = ref(null)

vi.mock('../../composables/useGraphData.js', () => ({
  useGraphData: () => ({
    data: graphData,
    loading: ref(false),
    error: ref(''),
    reload: vi.fn(),
    ready: () => !!graphData.value
  })
}))

vi.mock('../../composables/useTheme.js', () => ({
  useTheme: () => ({ theme: ref('paper') })
}))

vi.mock('../../composables/useViewport.js', () => ({
  useViewport: () => ({ width: ref(1200) })
}))

vi.mock('../../api/review.js', () => ({
  fetchTodayReview: vi.fn().mockResolvedValue({ items: [], totalDecayingCount: 0 })
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
    const parentOf = {}
    nodes.forEach((n, i) => {
      pos[n.id] = [100 + i * 40, 100]
      radius[n.id] = 8
      byId[n.id] = n
    })
    ;(data?.edges || []).forEach((e) => {
      if (e.type === '前置') parentOf[e.target] = e.source
    })
    return {
      pos,
      radius,
      labelPos: {},
      byId,
      parentOf,
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

async function mountMap() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({
    token: 't',
    user: { id: 2, role: 2, realName: '学生' }
  })
  useCourseStore(pinia).setStudentCourseId(1)

  graphData.value = {
    nodes: [
      { id: 'N1', name: '需求工程', chapter: '需求确定', mastery: 'weak', masteryScore: 40, difficulty: 3 },
      { id: 'N2', name: '测试', chapter: '软件测试', mastery: 'mastered', masteryScore: 90, difficulty: 2 }
    ],
    edges: [{ source: 'N1', target: 'N2', type: '前置' }],
    currentNodeId: 'N1'
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/map', component: ColorMap },
      { path: '/knowledge', component: { template: '<div />' } },
      { path: '/companion', component: { template: '<div />' } },
      { path: '/path', component: { template: '<div />' } }
    ]
  })
  await router.push('/map')
  await router.isReady()

  const wrapper = mount(ColorMap, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router],
      stubs: {
        ReviewPanel: true,
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
  await flushPromises()
  await nextTick()
  await new Promise((r) => setTimeout(r, 100))
  await nextTick()
  return wrapper
}

describe('ColorMap node keyboard a11y', () => {
  beforeEach(() => {
    graphData.value = null
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('map nodes are keyboard-reachable with role=button and Enter/Space select', async () => {
    const wrapper = await mountMap()
    const mapNodes = wrapper.findAll('g[role="button"]')
    expect(mapNodes.length).toBeGreaterThanOrEqual(1)

    const first = mapNodes[0]
    expect(first.attributes('tabindex')).toBe('0')
    await first.trigger('keydown', { key: 'Enter' })
    await nextTick()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    await first.trigger('keydown', { key: ' ' })
    await nextTick()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    wrapper.unmount()
  })
})
