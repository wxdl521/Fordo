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
  fetchTodayReview: vi.fn().mockResolvedValue({ items: [], totalDecayingCount: 0 })
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
        ReviewPanel: true,
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
  })

  afterEach(() => {
    localStorage.clear()
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
})
