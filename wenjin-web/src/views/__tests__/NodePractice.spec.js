import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick, ref } from 'vue'
import { useSessionStore } from '../../store/session.js'

const mocks = vi.hoisted(() => ({
  startPractice: vi.fn(),
  submitPractice: vi.fn()
}))

const courseState = vi.hoisted(() => ({ courseId: null }))

vi.mock('../../api/practice.js', () => ({
  startPractice: (...args) => mocks.startPractice(...args),
  submitPractice: (...args) => mocks.submitPractice(...args)
}))

vi.mock('../../composables/useStudentCourse.js', () => ({
  useStudentCourse: () => courseState
}))

vi.mock('../../components/QuestionAnswerCard.vue', () => ({
  default: {
    name: 'QuestionAnswerCard',
    template: '<div data-testid="question-card" />'
  }
}))

import NodePractice from '../NodePractice.vue'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function startData(label, sessionId) {
  return {
    sessionId,
    node: { nodeId: 10, nodeCode: label, name: label },
    questions: [{
      questionId: sessionId,
      chapter: 'chapter',
      stem: label,
      type: 1,
      options: [{ key: 'A', text: 'answer' }]
    }]
  }
}

async function mountPractice(initialCourseId = 1) {
  courseState.courseId = ref(initialCourseId)
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 'student-token', user: { id: 7, role: 2 } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/practice', component: NodePractice }]
  })
  await router.push({ path: '/practice', query: { nodeId: '10', pathItemId: '5' } })
  await router.isReady()
  const wrapper = mount(NodePractice, {
    global: {
      plugins: [pinia, router],
      stubs: { RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' } }
    }
  })
  await nextTick()
  return { wrapper, pinia, router }
}

describe('NodePractice session ownership', () => {
  beforeEach(() => {
    mocks.startPractice.mockReset()
    mocks.submitPractice.mockReset()
  })

  afterEach(() => {
    delete window.__wjPractice
  })

  it('keeps the newest course session when the old start resolves late', async () => {
    const startA = deferred()
    const startB = deferred()
    mocks.startPractice.mockImplementation((payload) => (payload.courseId === 1 ? startA.promise : startB.promise))

    const { wrapper } = await mountPractice()
    const signalA = mocks.startPractice.mock.calls[0][1].signal
    courseState.courseId.value = 2
    await nextTick()

    expect.soft(signalA.aborted).toBe(true)
    expect.soft(mocks.startPractice.mock.calls[1][1].signal).toEqual(expect.any(AbortSignal))
    startB.resolve(startData('course-b', 202))
    await flushPromises()
    startA.resolve(startData('course-a', 101))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.sessionId).toBe(202)
    expect.soft(state.nodeInfo.nodeCode).toBe('course-b')
    expect.soft(state.phase).toBe('answering')
    expect(state.errorMsg).toBe('')
    wrapper.unmount()
  })

  it('does not let an old start error replace the new loading state', async () => {
    const startA = deferred()
    const startB = deferred()
    mocks.startPractice.mockImplementation((payload) => (payload.courseId === 1 ? startA.promise : startB.promise))

    const { wrapper } = await mountPractice()
    courseState.courseId.value = 2
    await nextTick()
    startA.reject(new Error('course A failed'))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.phase).toBe('loading')
    expect.soft(state.errorMsg).toBe('')
    startB.resolve(startData('course-b', 202))
    await flushPromises()
    expect(state.phase).toBe('answering')
    wrapper.unmount()
  })

  it('refuses to submit a session after its owner changes', async () => {
    const newOwnerStart = deferred()
    mocks.startPractice
      .mockResolvedValueOnce(startData('course-a', 101))
      .mockReturnValueOnce(newOwnerStart.promise)
    const { wrapper, pinia } = await mountPractice()
    await flushPromises()
    const state = wrapper.vm.$.setupState
    const oldSubmit = state.doSubmit

    useSessionStore(pinia).setSession({ token: 'new-token', user: { id: 8, role: 2 } })
    await nextTick()
    await oldSubmit([{ questionId: 101, studentAnswer: 'A' }])
    await flushPromises()

    expect(mocks.submitPractice).not.toHaveBeenCalled()
    newOwnerStart.resolve(startData('new-owner', 202))
    await flushPromises()
    wrapper.unmount()
  })

  it('invalidates a loaded session as soon as a replacement generation starts', async () => {
    const replacement = deferred()
    mocks.startPractice
      .mockResolvedValueOnce(startData('generation-a', 101))
      .mockReturnValueOnce(replacement.promise)
    const { wrapper } = await mountPractice()
    await flushPromises()
    const state = wrapper.vm.$.setupState

    const replacementPromise = state.startSession()
    await nextTick()
    await state.doSubmit([{ questionId: 101, studentAnswer: 'A' }])
    expect.soft(mocks.submitPractice).not.toHaveBeenCalled()
    expect.soft(state.sessionId).toBeNull()
    expect.soft(state.phase).toBe('loading')

    replacement.resolve(startData('generation-b', 202))
    await replacementPromise
    await flushPromises()
    expect.soft(state.sessionId).toBe(202)
    expect(state.phase).toBe('answering')
    wrapper.unmount()
  })

  it('ignores a pending submission after switching courses', async () => {
    const submitA = deferred()
    const startB = deferred()
    mocks.startPractice.mockImplementation((payload) => {
      return payload.courseId === 1 ? Promise.resolve(startData('course-a', 101)) : startB.promise
    })
    mocks.submitPractice.mockReturnValue(submitA.promise)

    const { wrapper } = await mountPractice()
    await flushPromises()
    const state = wrapper.vm.$.setupState
    const submitPromise = state.doSubmit([{ questionId: 101, studentAnswer: 'A' }])
    await nextTick()
    const submitSignal = mocks.submitPractice.mock.calls[0][2].signal

    courseState.courseId.value = 2
    await nextTick()
    expect(submitSignal.aborted).toBe(true)
    startB.resolve(startData('course-b', 202))
    submitA.resolve({ itemCompleted: true, masteryBefore: 0, masteryAfter: 100 })
    await submitPromise
    await flushPromises()

    expect.soft(state.sessionId).toBe(202)
    expect.soft(state.phase).toBe('answering')
    expect.soft(state.submitResult).toBeNull()
    expect(state.itemCompleted).toBe(false)
    wrapper.unmount()
  })

  it('aborts on unmount and refuses a captured DEV-hook restart', async () => {
    const pending = deferred()
    mocks.startPractice.mockReturnValue(pending.promise)
    const { wrapper } = await mountPractice()
    const signal = mocks.startPractice.mock.calls[0][1].signal
    const hook = window.__wjPractice

    wrapper.unmount()
    expect.soft(signal.aborted).toBe(true)
    expect.soft(window.__wjPractice).toBeUndefined()
    await hook.startSession()

    expect(mocks.startPractice).toHaveBeenCalledTimes(1)
    pending.resolve(startData('late', 303))
    await flushPromises()
  })
})

describe('NodePractice review-mistakes entry', () => {
  beforeEach(() => {
    mocks.startPractice.mockReset()
    mocks.submitPractice.mockReset()
  })
  afterEach(() => { delete window.__wjPractice })

  async function finish(graded) {
    mocks.startPractice.mockResolvedValue(startData('N7', 303))
    mocks.submitPractice.mockResolvedValue({ masteryBefore: 0.4, masteryAfter: 0.5, graded })
    const { wrapper } = await mountPractice(5)
    await flushPromises()
    await wrapper.vm.$.setupState.doSubmit([{ questionId: 303, studentAnswer: 'A' }])
    await flushPromises()
    return wrapper
  }

  it('shows 复盘错题 with the right query when there are wrong answers', async () => {
    const wrapper = await finish([{ questionId: 303, correct: false }])
    const link = wrapper.find('[data-testid="np-review-mistakes"]')
    expect(link.exists()).toBe(true)
    expect(JSON.parse(link.attributes('data-to'))).toEqual({
      path: '/companion',
      query: { courseId: 5, nodeCode: 'N7', review: 303 }
    })
    wrapper.unmount()
  })

  it('hides the button when everything is correct', async () => {
    const wrapper = await finish([{ questionId: 303, correct: true }])
    expect(wrapper.find('[data-testid="np-review-mistakes"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
