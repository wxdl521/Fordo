import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../../api/graph.js', () => ({
  fetchGraph: vi.fn()
}))

import { fetchGraph } from '../../api/graph.js'
import { useGraphStore } from '../graph.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('graphStore', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    fetchGraph.mockReset()
    // graph.js keeps its latest-request gate at module scope.
    await useGraphStore().load(null)
    fetchGraph.mockClear()
  })

  afterEach(async () => {
    await useGraphStore().load(null)
  })

  it.each([null, undefined, '', 0, -1, 'invalid'])(
    'fails closed and skips the API for invalid course ID %s',
    async (courseId) => {
      const store = useGraphStore()
      store.$patch({
        course: { id: 9, name: 'Stale course' },
        nodes: [{ nodeCode: 'STALE' }],
        edges: [{ source: 'STALE', target: 'OLD' }],
        loading: true,
        error: 'stale error',
        loadedCourseId: 9
      })

      await store.load(courseId)

      expect(fetchGraph).not.toHaveBeenCalled()
      expect(store.course).toBeNull()
      expect(store.nodes).toEqual([])
      expect(store.edges).toEqual([])
      expect(store.loadedCourseId).toBeNull()
      expect(store.loading).toBe(false)
      expect(store.error).toBe('')
    }
  )

  it('normalizes a string ID and publishes the current successful response', async () => {
    const pending = deferred()
    const course = { id: 7, name: 'Course 7' }
    const nodes = [{ nodeCode: 'N7', chapter: 'Basics' }]
    const edges = [{ type: 'related', source: 'N7', target: 'N8' }]
    fetchGraph.mockReturnValueOnce(pending.promise)
    const store = useGraphStore()

    const loading = store.load('7')
    const config = fetchGraph.mock.calls[0][1]

    expect(fetchGraph).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(config.signal.aborted).toBe(false)
    expect(store.loading).toBe(true)
    expect(store.error).toBe('')

    pending.resolve({ course, nodes, edges })
    await loading

    expect(store.course).toEqual(course)
    expect(store.nodes).toEqual(nodes)
    expect(store.edges).toEqual(edges)
    expect(store.loadedCourseId).toBe(7)
    expect(store.loading).toBe(false)
    expect(store.error).toBe('')
  })

  it('keeps course B when course A resolves late and aborts A signal', async () => {
    const courseA = deferred()
    const courseB = deferred()
    fetchGraph
      .mockReturnValueOnce(courseA.promise)
      .mockReturnValueOnce(courseB.promise)
    const store = useGraphStore()

    const loadingA = store.load(1)
    const configA = fetchGraph.mock.calls[0][1]
    const loadingB = store.load(2)
    const configB = fetchGraph.mock.calls[1][1]

    expect(configA.signal).toEqual(expect.any(AbortSignal))
    expect(configA.signal.aborted).toBe(true)
    expect(configB.signal.aborted).toBe(false)

    courseB.resolve({
      course: { id: 2, name: 'Course B' },
      nodes: [{ nodeCode: 'B' }],
      edges: [{ source: 'B', target: 'B2' }]
    })
    await loadingB

    courseA.resolve({
      course: { id: 1, name: 'Course A' },
      nodes: [{ nodeCode: 'A' }],
      edges: [{ source: 'A', target: 'A2' }]
    })
    await loadingA

    expect(store.course).toEqual({ id: 2, name: 'Course B' })
    expect(store.nodes).toEqual([{ nodeCode: 'B' }])
    expect(store.edges).toEqual([{ source: 'B', target: 'B2' }])
    expect(store.loadedCourseId).toBe(2)
    expect(store.loading).toBe(false)
    expect(store.error).toBe('')
  })

  it('publishes an ordinary error from the current request', async () => {
    fetchGraph.mockRejectedValueOnce(new Error('graph unavailable'))
    const store = useGraphStore()

    await store.load(4)

    expect(store.error).toBe('graph unavailable')
    expect(store.loading).toBe(false)
    expect(store.loadedCourseId).toBeNull()
    expect(store.course).toBeNull()
    expect(store.nodes).toEqual([])
    expect(store.edges).toEqual([])
  })

  it.each(['AbortError', 'CanceledError'])(
    'suppresses %s from the current request',
    async (name) => {
      const error = Object.assign(new Error('request stopped'), { name })
      fetchGraph.mockRejectedValueOnce(error)
      const store = useGraphStore()

      await store.load(5)

      expect(store.error).toBe('')
      expect(store.loading).toBe(false)
      expect(store.loadedCourseId).toBeNull()
    }
  )

  it('derives nodeMap, unique chapters, and valid prerequisite sources', () => {
    const store = useGraphStore()
    const basics = { nodeCode: 'BASIC', chapter: 'Foundations' }
    const algebra = { nodeCode: 'ALG', chapter: 'Foundations' }
    const calculus = { nodeCode: 'CALC', chapter: 'Advanced' }
    const ungrouped = { nodeCode: 'OTHER', chapter: '' }
    store.nodes = [basics, algebra, calculus, ungrouped]
    store.edges = [
      { type: '\u524d\u7f6e', source: 'BASIC', target: 'CALC' },
      { type: '\u524d\u7f6e', source: 'ALG', target: 'CALC' },
      { type: '\u524d\u7f6e', source: 'MISSING', target: 'CALC' },
      { type: 'related', source: 'OTHER', target: 'CALC' },
      { type: '\u524d\u7f6e', source: 'OTHER', target: 'ALG' }
    ]

    expect(store.nodeMap).toBeInstanceOf(Map)
    expect(store.nodeMap.get('BASIC')).toEqual(basics)
    expect(store.nodeMap.get('CALC')).toEqual(calculus)
    expect(store.chapters).toEqual(['Foundations', 'Advanced'])
    expect(store.prerequisitesOf('CALC')).toEqual([basics, algebra])
  })
})
