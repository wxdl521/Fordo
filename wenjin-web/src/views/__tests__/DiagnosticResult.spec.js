import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import { watch } from 'vue'
import DiagnosticResult from '../DiagnosticResult.vue'
import * as diagnosticApi from '../../api/diagnostic.js'
import * as pathApi from '../../api/path.js'

vi.mock('../../api/diagnostic.js', () => ({
  fetchPaper: vi.fn(),
  submitPaper: vi.fn(),
  fetchResult: vi.fn(),
  startAdaptive: vi.fn(),
  answerAdaptive: vi.fn(),
  abandonAdaptive: vi.fn(),
  fetchEvidence: vi.fn()
}))

vi.mock('../../api/path.js', () => ({
  generatePath: vi.fn()
}))

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function resultFor(courseId) {
  return {
    courseId,
    hasWeakness: true,
    questionsAnswered: 2,
    coverage: { covered: 1, total: 2 },
    stuckNode: { nodeCode: `N-${courseId}`, name: `Weak ${courseId}` },
    conclusionText: `Conclusion ${courseId}`,
    chain: [
      {
        nodeCode: `N-${courseId}`,
        name: `Weak ${courseId}`,
        role: 'stuck',
        masteryLevel: 1,
        masteryScore: 50
      }
    ],
    bases: [],
    pendingVerification: [],
    distribution: { mastered: courseId, weak: 1, unlearned: 0 }
  }
}

function evidenceFor(courseId) {
  return {
    courseId,
    tested: courseId,
    inferredPass: 1,
    inferredFail: 0,
    unknown: 0,
    inferredNodes: [
      {
        nodeId: courseId * 10,
        name: `Evidence ${courseId}`,
        fromName: 'Source',
        fromPass: true
      }
    ]
  }
}

const mounted = []

async function mountResult(courseId = 5) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/result', component: DiagnosticResult },
      { path: '/diagnostic', component: { template: '<div />' } },
      { path: '/path', component: { template: '<div />' } }
    ]
  })
  await router.push(`/result?courseId=${courseId}`)
  await router.isReady()
  const wrapper = mount(DiagnosticResult, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
  mounted.push(wrapper)
  return { wrapper, router }
}

describe('DiagnosticResult request ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('wj_token', 'auth-a')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2, role: 2 }))
    diagnosticApi.fetchResult.mockImplementation((courseId) => Promise.resolve(resultFor(courseId)))
    diagnosticApi.fetchEvidence.mockImplementation((courseId) => Promise.resolve(evidenceFor(courseId)))
    pathApi.generatePath.mockResolvedValue({})
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    localStorage.clear()
    delete window.__wjResult
  })

  it('keeps course B when course A result resolves last', async () => {
    const courseA = deferred()
    diagnosticApi.fetchResult.mockImplementation((courseId) => (
      courseId === 5 ? courseA.promise : Promise.resolve(resultFor(6))
    ))

    const { router } = await mountResult(5)
    await flushPromises()
    const firstSignal = diagnosticApi.fetchResult.mock.calls[0][1].signal

    await router.push('/result?courseId=6')
    await flushPromises()

    expect(firstSignal.aborted).toBe(true)
    expect(window.__wjResult.data.value.courseId).toBe(6)

    courseA.resolve(resultFor(5))
    await flushPromises()

    expect(window.__wjResult.data.value.courseId).toBe(6)
    expect(window.__wjResult.evidence.value.courseId).toBe(6)
    expect(window.__wjResult.loading.value).toBe(false)
  })

  it('does not let old evidence replace the new course evidence', async () => {
    const evidenceA = deferred()
    diagnosticApi.fetchEvidence.mockImplementation((courseId) => (
      courseId === 5 ? evidenceA.promise : Promise.resolve(evidenceFor(6))
    ))

    const { router } = await mountResult(5)
    await flushPromises()
    const firstSignal = diagnosticApi.fetchEvidence.mock.calls[0][1].signal

    await router.push('/result?courseId=6')
    await flushPromises()
    expect(firstSignal.aborted).toBe(true)
    expect(window.__wjResult.evidence.value.courseId).toBe(6)

    evidenceA.resolve(evidenceFor(5))
    await flushPromises()

    expect(window.__wjResult.data.value.courseId).toBe(6)
    expect(window.__wjResult.evidence.value.courseId).toBe(6)
  })

  it('renders the primary result while optional evidence is still pending', async () => {
    const pendingEvidence = deferred()
    diagnosticApi.fetchEvidence.mockReturnValueOnce(pendingEvidence.promise)

    const { wrapper } = await mountResult(5)
    await flushPromises()

    expect(window.__wjResult.loading.value).toBe(false)
    expect(window.__wjResult.data.value.courseId).toBe(5)
    expect(window.__wjResult.evidence.value).toBeNull()
    expect(wrapper.find('.rs-wrap').exists()).toBe(true)
    expect(wrapper.text()).toContain('Weak 5')

    pendingEvidence.resolve(evidenceFor(5))
    await flushPromises()

    expect(window.__wjResult.evidence.value.courseId).toBe(5)
    expect(wrapper.text()).toContain('Evidence 5')
  })

  it('does not navigate course B after a stale course A path generation', async () => {
    const generationA = deferred()
    pathApi.generatePath.mockReturnValueOnce(generationA.promise)

    const { router } = await mountResult(5)
    await flushPromises()
    const generation = window.__wjResult.goPath()
    await flushPromises()
    const signal = pathApi.generatePath.mock.calls[0][1].signal

    await router.push('/result?courseId=6')
    await flushPromises()
    expect(signal.aborted).toBe(true)

    generationA.resolve({})
    await generation
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/result')
    expect(router.currentRoute.value.query.courseId).toBe('6')
  })

  it('does not start a course B path from the stale course A result before the load watcher flushes', async () => {
    const resultB = deferred()
    diagnosticApi.fetchResult.mockImplementation((courseId) => (
      courseId === 5 ? Promise.resolve(resultFor(5)) : resultB.promise
    ))

    const { router } = await mountResult(5)
    await flushPromises()
    expect(window.__wjResult.data.value.courseId).toBe(5)

    let staleAction
    const stop = watch(
      () => router.currentRoute.value.query.courseId,
      (nextCourseId) => {
        if (String(nextCourseId) === '6') staleAction = window.__wjResult.goPath()
      },
      { flush: 'sync' }
    )

    await router.push('/result?courseId=6')
    stop()
    expect(staleAction).toBeDefined()
    await staleAction

    expect(pathApi.generatePath).not.toHaveBeenCalled()

    resultB.resolve(resultFor(6))
    await flushPromises()
    expect(window.__wjResult.data.value.courseId).toBe(6)
  })

  it('cancels path generation and ignores its completion after unmount', async () => {
    const generationA = deferred()
    pathApi.generatePath.mockReturnValueOnce(generationA.promise)

    const { wrapper, router } = await mountResult(5)
    await flushPromises()
    const result = window.__wjResult
    const generation = result.goPath()
    await flushPromises()
    const signal = pathApi.generatePath.mock.calls[0][1].signal

    wrapper.unmount()
    expect(signal.aborted).toBe(true)

    generationA.resolve({})
    await generation
    await flushPromises()

    expect(router.currentRoute.value.path).not.toBe('/path')
  })
})
