import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { nextTick } from 'vue'
import { createPinia } from 'pinia'
import Diagnostic from '../Diagnostic.vue'
import { useSessionStore } from '../../store/session.js'
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

function paperFor(courseId, sessionToken = `paper-${courseId}`) {
  return {
    courseId,
    sessionToken,
    total: 2,
    questions: [
      {
        questionId: courseId * 10 + 1,
        stem: `Question ${courseId}-1`,
        chapter: 'A',
        type: 1,
        options: [
          { key: 'A', text: 'One' },
          { key: 'B', text: 'Two' }
        ]
      },
      {
        questionId: courseId * 10 + 2,
        stem: `Question ${courseId}-2`,
        chapter: 'B',
        type: 1,
        options: [
          { key: 'A', text: 'Three' },
          { key: 'B', text: 'Four' }
        ]
      }
    ]
  }
}

const samplePaper = paperFor(5, 'tok-abc-123')
const mounted = []

async function mountDiag(courseId = 5, { selectFull = true } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/diagnostic', name: 'diagnostic', component: Diagnostic },
      { path: '/diagnostic/adaptive', name: 'adaptive', component: { template: '<div />' } },
      { path: '/map', name: 'map', component: { template: '<div />' } },
      { path: '/result', name: 'result', component: { template: '<div />' } }
    ]
  })
  const pinia = createPinia()
  await router.push(`/diagnostic?courseId=${courseId}`)
  await router.isReady()
  const wrapper = mount(Diagnostic, {
    global: {
      plugins: [pinia, router],
      stubs: {
        QuestionAnswerCard: {
          name: 'QuestionAnswerCard',
          template: '<div class="q-card-stub" />',
          props: ['question', 'index', 'total', 'selected', 'isLast', 'lastLabel', 'hasSelection']
        },
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
  mounted.push(wrapper)
  if (selectFull) {
    window.__wjDiag.chooseFull()
    await nextTick()
  }
  return { wrapper, router, pinia }
}

describe('Diagnostic fixed paper request ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('wj_token', 'auth-a')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2, role: 2 }))
    diagnosticApi.fetchPaper.mockResolvedValue(samplePaper)
    diagnosticApi.submitPaper.mockResolvedValue({
      total: 2,
      correctCount: 1,
      grades: [
        { questionId: 51, correct: true, correctKey: 'A' },
        { questionId: 52, correct: false, correctKey: 'A' }
      ]
    })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    localStorage.clear()
    delete window.__wjDiag
  })

  it('does not compose a fixed paper when quick diagnostic is selected', async () => {
    const { router } = await mountDiag(5, { selectFull: false })
    await flushPromises()

    expect(diagnosticApi.fetchPaper).not.toHaveBeenCalled()

    window.__wjDiag.chooseQuick()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/diagnostic/adaptive')
    expect(router.currentRoute.value.query.courseId).toBe('5')
    expect(diagnosticApi.fetchPaper).not.toHaveBeenCalled()
  })

  it('loads and submits the frozen paper session', async () => {
    await mountDiag()
    await flushPromises()

    const diag = window.__wjDiag
    expect(diagnosticApi.fetchPaper).toHaveBeenCalledWith(
      5,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(diag.sessionToken.value).toBe('tok-abc-123')

    diag.pick(0)
    await nextTick()
    diag.finish()
    await flushPromises()

    expect(diagnosticApi.submitPaper).toHaveBeenCalledTimes(1)
    const [payload, config] = diagnosticApi.submitPaper.mock.calls[0]
    expect(payload).toMatchObject({
      sessionToken: 'tok-abc-123',
      courseId: 5
    })
    expect(payload.studentId).toBeUndefined()
    expect(payload.answers).toEqual([
      { questionId: 51, optionKey: 'A' },
      { questionId: 52, optionKey: null }
    ])
    expect(config.signal).toBeInstanceOf(AbortSignal)
  })

  it('shows a failed submit and retries the same frozen paper', async () => {
    diagnosticApi.submitPaper.mockRejectedValueOnce(new Error('network down'))
    const { wrapper } = await mountDiag()
    await flushPromises()

    const diag = window.__wjDiag
    diag.pick(0)
    await nextTick()
    diag.finish()
    await flushPromises()

    expect(diag.done.value).toBe(false)
    expect(diag.submitting.value).toBe(false)
    expect(diag.submitError.value).toBe('network down')
    expect(wrapper.find('.diag-error-msg').text()).toContain('提交失败：network down')

    await wrapper.find('.diag-center-state .diag-btn-acc').trigger('click')
    await flushPromises()

    expect(diagnosticApi.submitPaper).toHaveBeenCalledTimes(2)
    expect(diagnosticApi.submitPaper.mock.calls[1][0].sessionToken).toBe('tok-abc-123')
    expect(diag.submitError.value).toBe('')
    expect(diag.done.value).toBe(true)
    expect(diag.result.value.correctCount).toBe(1)
    expect(wrapper.find('.diag-done-title').text()).toBe('诊断完成')
  })

  it('missing paper token recomposes instead of submitting stale questions', async () => {
    await mountDiag()
    await flushPromises()

    const diag = window.__wjDiag
    diag.sessionToken.value = null
    diagnosticApi.fetchPaper.mockClear()
    diagnosticApi.fetchPaper.mockResolvedValue(paperFor(5, 'tok-new'))

    await diag.submitAnswers()
    await flushPromises()

    expect(diagnosticApi.submitPaper).not.toHaveBeenCalled()
    expect(diagnosticApi.fetchPaper).toHaveBeenCalledWith(
      5,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(diag.sessionToken.value).toBe('tok-new')
  })

  it('keeps course B when course A paper resolves last', async () => {
    const courseA = deferred()
    diagnosticApi.fetchPaper.mockImplementation((courseId) => (
      courseId === 5 ? courseA.promise : Promise.resolve(paperFor(6))
    ))

    const { router } = await mountDiag(5)
    await flushPromises()
    const firstSignal = diagnosticApi.fetchPaper.mock.calls[0][1].signal

    await router.push('/diagnostic?courseId=6')
    await flushPromises()

    expect(firstSignal.aborted).toBe(true)
    expect(window.__wjDiag.sessionToken.value).toBe('paper-6')
    expect(window.__wjDiag.questions.value[0].questionId).toBe(61)

    courseA.resolve(paperFor(5))
    await flushPromises()

    expect(window.__wjDiag.sessionToken.value).toBe('paper-6')
    expect(window.__wjDiag.questions.value[0].questionId).toBe(61)
    expect(window.__wjDiag.loading.value).toBe(false)
  })

  it('does not commit an old paper after the authenticated owner changes', async () => {
    const ownerA = deferred()
    diagnosticApi.fetchPaper
      .mockImplementationOnce(() => ownerA.promise)
      .mockResolvedValueOnce(paperFor(5, 'owner-b-paper'))

    const { pinia } = await mountDiag(5)
    await flushPromises()

    useSessionStore(pinia).setSession({
      token: 'auth-b',
      user: { id: 9, role: 2 }
    })
    await flushPromises()
    expect(window.__wjDiag.sessionToken.value).toBe('owner-b-paper')

    ownerA.resolve(paperFor(5, 'owner-a-paper'))
    await flushPromises()

    expect(window.__wjDiag.sessionToken.value).toBe('owner-b-paper')
  })

  it('cancels an old submit when a new course loads', async () => {
    const submitA = deferred()
    diagnosticApi.submitPaper.mockReturnValueOnce(submitA.promise)
    diagnosticApi.fetchPaper.mockImplementation((courseId) => Promise.resolve(paperFor(courseId)))

    const { router } = await mountDiag(5)
    await flushPromises()
    const diag = window.__wjDiag
    diag.pick(0)
    await nextTick()
    const submitPromise = diag.submitAnswers()
    await nextTick()
    const submitSignal = diagnosticApi.submitPaper.mock.calls[0][1].signal

    await router.push('/diagnostic?courseId=6')
    await flushPromises()
    expect(submitSignal.aborted).toBe(true)

    submitA.resolve({ total: 2, correctCount: 2, grades: [] })
    await submitPromise
    await flushPromises()

    expect(diag.result.value).toBeNull()
    expect(diag.sessionToken.value).toBe('paper-6')
    expect(diag.questions.value[0].questionId).toBe(61)
    expect(diag.submitting.value).toBe(false)
  })
})
