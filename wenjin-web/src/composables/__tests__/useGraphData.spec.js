import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

vi.mock('../../api/graph.js', () => ({
  fetchGraph: vi.fn()
}))

import { fetchGraph } from '../../api/graph.js'
import { useGraphData } from '../useGraphData.js'
import { createLatestRequest } from '../../utils/latestRequest.js'

describe('createLatestRequest', () => {
  it('next bumps revision and aborts previous signal', () => {
    const latest = createLatestRequest()
    const a = latest.next()
    expect(a.isCurrent()).toBe(true)
    const b = latest.next()
    expect(a.isCurrent()).toBe(false)
    expect(b.isCurrent()).toBe(true)
    expect(a.signal.aborted).toBe(true)
    expect(b.signal.aborted).toBe(false)
    latest.cancel()
    expect(b.isCurrent()).toBe(false)
    expect(b.signal.aborted).toBe(true)
  })
})

describe('useGraphData', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  function mountHarness(courseIdRef) {
    let api
    const Comp = defineComponent({
      setup() {
        api = useGraphData(courseIdRef)
        return () => h('div', api.data.value ? 'ready' : 'empty')
      }
    })
    const wrapper = mount(Comp)
    return { wrapper, get api() { return api } }
  }

  it('course 1→5 switch: late response for course 1 is ignored', async () => {
    let resolve1
    let resolve5
    fetchGraph.mockImplementation((id) => {
      if (id === 1) {
        return new Promise((resolve) => {
          resolve1 = resolve
        })
      }
      if (id === 5) {
        return new Promise((resolve) => {
          resolve5 = resolve
        })
      }
      return Promise.resolve({ nodes: [], edges: [] })
    })

    const courseId = ref(1)
    const { wrapper, api } = mountHarness(courseId)

    await nextTick()
    expect(fetchGraph).toHaveBeenCalledWith(1, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    courseId.value = 5
    await nextTick()
    expect(fetchGraph).toHaveBeenCalledWith(5, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    // 迟到的课 1 响应
    resolve1({
      nodes: [{ nodeCode: 'n1', name: '旧课节点', isKey: false }],
      edges: []
    })
    await nextTick()
    await Promise.resolve()
    // 不应写入旧课数据
    expect(api.data.value?.nodes?.some((n) => n.id === 'n1')).toBeFalsy()

    resolve5({
      nodes: [{ nodeCode: 'n5', name: '新课节点', isKey: true }],
      edges: []
    })
    await nextTick()
    await Promise.resolve()
    await nextTick()

    expect(api.data.value?.nodes?.map((n) => n.id)).toEqual(['n5'])
    wrapper.unmount()
  })

  it('unmount cancels in-flight request', async () => {
    let signalSeen = null
    fetchGraph.mockImplementation((_id, config) => {
      signalSeen = config?.signal
      return new Promise(() => {})
    })

    const courseId = ref(3)
    const { wrapper } = mountHarness(courseId)
    await nextTick()
    expect(signalSeen).toBeTruthy()
    expect(signalSeen.aborted).toBe(false)

    wrapper.unmount()
    expect(signalSeen.aborted).toBe(true)
  })

  it('null courseId → data null (no fetch, no fallback to 1)', async () => {
    fetchGraph.mockResolvedValue({ nodes: [], edges: [] })
    const courseId = ref(null)
    const { wrapper, api } = mountHarness(courseId)
    await nextTick()
    expect(fetchGraph).not.toHaveBeenCalled()
    expect(api.data.value).toBeNull()
    wrapper.unmount()
  })
})
