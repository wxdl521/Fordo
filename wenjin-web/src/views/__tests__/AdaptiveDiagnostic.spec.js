import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import AdaptiveDiagnostic from '../AdaptiveDiagnostic.vue'
import * as diagnosticApi from '../../api/diagnostic.js'

vi.mock('../../api/diagnostic.js', () => ({
  fetchPaper: vi.fn(),
  submitPaper: vi.fn(),
  fetchResult: vi.fn(),
  startAdaptive: vi.fn(),
  answerAdaptive: vi.fn(),
  abandonAdaptive: vi.fn(),
  fetchEvidence: vi.fn()
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

function adaptiveFor(courseId, sessionId = courseId * 100) {
  return {
    sessionId,
    done: false,
    progress: { tested: 0, inferred: 0, unknown: 1, budgetLeft: 3 },
    summary: null,
    probe: {
      questions: [
        {
          questionId: courseId * 10 + 1,
          stem: `Probe ${courseId}`,
          chapter: 'A',
          type: 1,
          options: [
            { key: 'A', text: 'One' },
            { key: 'B', text: 'Two' }
          ]
        }
      ]
    }
  }
}

const mounted = []

async function mountAdaptive(courseId = 5) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/diagnostic/adaptive', component: AdaptiveDiagnostic },
      { path: '/map', component: { template: '<div />' } },
      { path: '/result', component: { template: '<div />' } }
    ]
  })
  await router.push(`/diagnostic/adaptive?courseId=${courseId}`)
  await router.isReady()
  const wrapper = mount(AdaptiveDiagnostic, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        AdaptiveProgressRing: { template: '<div />' },
        AdaptiveProbeCard: { template: '<div />' },
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
  mounted.push(wrapper)
  return { wrapper, router }
}

describe('AdaptiveDiagnostic request ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('wj_token', 'auth-a')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2, role: 2 }))
    diagnosticApi.startAdaptive.mockImplementation(({ courseId }) => Promise.resolve(adaptiveFor(courseId)))
    diagnosticApi.answerAdaptive.mockResolvedValue({
      ...adaptiveFor(5),
      done: true,
      probe: null,
      summary: { tested: 1, inferredPass: 0, inferredFail: 0, unknown: 0 }
    })
    diagnosticApi.abandonAdaptive.mockResolvedValue({ done: true })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
    localStorage.clear()
    delete window.__wjAdaptive
  })

  it('keeps course B when course A start resolves last', async () => {
    const courseA = deferred()
    diagnosticApi.startAdaptive.mockImplementation(({ courseId }) => (
      courseId === 5 ? courseA.promise : Promise.resolve(adaptiveFor(6))
    ))

    const { router } = await mountAdaptive(5)
    await flushPromises()
    const firstSignal = diagnosticApi.startAdaptive.mock.calls[0][1].signal

    await router.push('/diagnostic/adaptive?courseId=6')
    await flushPromises()

    expect(firstSignal.aborted).toBe(true)
    expect(window.__wjAdaptive.sessionId.value).toBe(600)
    expect(window.__wjAdaptive.questions.value[0].questionId).toBe(61)

    courseA.resolve(adaptiveFor(5))
    await flushPromises()

    expect(window.__wjAdaptive.sessionId.value).toBe(600)
    expect(window.__wjAdaptive.questions.value[0].questionId).toBe(61)
    expect(window.__wjAdaptive.loading.value).toBe(false)
  })

  it('binds an answer to the load generation, session, and course', async () => {
    const answerA = deferred()
    diagnosticApi.answerAdaptive.mockReturnValueOnce(answerA.promise)

    const { router } = await mountAdaptive(5)
    await flushPromises()
    const adaptive = window.__wjAdaptive
    adaptive.pick(0, 0)
    await nextTick()
    const answerPromise = adaptive.submitAnswer()
    await nextTick()

    const [sessionId, payload, config] = diagnosticApi.answerAdaptive.mock.calls[0]
    expect(sessionId).toBe(500)
    expect(payload.answers).toEqual([{ questionId: 51, studentAnswer: 'A' }])
    expect(config.signal).toBeInstanceOf(AbortSignal)

    await router.push('/diagnostic/adaptive?courseId=6')
    await flushPromises()
    expect(config.signal.aborted).toBe(true)

    answerA.resolve({
      ...adaptiveFor(5),
      done: true,
      probe: null,
      summary: { tested: 1, inferredPass: 0, inferredFail: 0, unknown: 0 }
    })
    await answerPromise
    await flushPromises()

    expect(adaptive.sessionId.value).toBe(600)
    expect(adaptive.questions.value[0].questionId).toBe(61)
    expect(adaptive.askedCount.value).toBe(0)
    expect(adaptive.done.value).toBe(false)
    expect(adaptive.submitting.value).toBe(false)
  })

  it('blocks abandon while an answer is pending', async () => {
    const pendingAnswer = deferred()
    diagnosticApi.answerAdaptive.mockReturnValueOnce(pendingAnswer.promise)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)

    const { wrapper } = await mountAdaptive(5)
    await flushPromises()
    const adaptive = window.__wjAdaptive
    adaptive.pick(0, 0)
    await nextTick()

    const answer = adaptive.submitAnswer()
    await nextTick()

    expect(adaptive.submitting.value).toBe(true)
    expect(wrapper.find('button.ad-exit-btn').element.disabled).toBe(true)
    await adaptive.abandon()
    expect(confirm).not.toHaveBeenCalled()
    expect(diagnosticApi.abandonAdaptive).not.toHaveBeenCalled()

    pendingAnswer.resolve({
      ...adaptiveFor(5),
      done: true,
      probe: null,
      summary: { tested: 1, inferredPass: 0, inferredFail: 0, unknown: 0 }
    })
    await answer
    await flushPromises()
  })

  it('blocks answer while abandon is pending', async () => {
    const pendingAbandon = deferred()
    diagnosticApi.abandonAdaptive.mockReturnValueOnce(pendingAbandon.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    const { wrapper } = await mountAdaptive(5)
    await flushPromises()
    const adaptive = window.__wjAdaptive
    adaptive.pick(0, 0)
    await nextTick()

    const abandon = adaptive.abandon()
    await nextTick()

    expect(adaptive.abandoning.value).toBe(true)
    expect(wrapper.find('.ad-submit').element.disabled).toBe(true)
    await adaptive.submitAnswer()
    expect(diagnosticApi.answerAdaptive).not.toHaveBeenCalled()

    pendingAbandon.resolve()
    await abandon
    await flushPromises()
  })

  it('cancels an in-flight start when unmounted', async () => {
    const pending = deferred()
    diagnosticApi.startAdaptive.mockReturnValueOnce(pending.promise)
    const { wrapper } = await mountAdaptive(5)
    await flushPromises()
    const signal = diagnosticApi.startAdaptive.mock.calls[0][1].signal

    wrapper.unmount()

    expect(signal.aborted).toBe(true)
    pending.resolve(adaptiveFor(5))
    await flushPromises()
  })
})
