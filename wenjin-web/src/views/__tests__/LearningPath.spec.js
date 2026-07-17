import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, ref } from 'vue'
import { useSessionStore } from '../../store/session.js'

const mocks = vi.hoisted(() => ({
  completeItem: vi.fn(),
  fetchCurrentPath: vi.fn()
}))

const courseState = vi.hoisted(() => ({ courseId: null }))

vi.mock('../../api/path.js', () => ({
  completeItem: (...args) => mocks.completeItem(...args),
  fetchCurrentPath: (...args) => mocks.fetchCurrentPath(...args)
}))

vi.mock('../../composables/useStudentCourse.js', () => ({
  useStudentCourse: () => courseState
}))

import LearningPath from '../LearningPath.vue'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function pathData(label, itemId = 11) {
  return {
    label,
    pathId: itemId * 10,
    targetNode: { name: label },
    conclusionText: '',
    progress: { done: 0, total: 1 },
    steps: [{
      itemId,
      nodeId: itemId,
      stepOrder: 1,
      name: label,
      role: 'root',
      status: 0,
      availableQuestionCount: 0
    }]
  }
}

async function mountPath(initialCourseId = 1) {
  courseState.courseId = ref(initialCourseId)
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 'student-token', user: { id: 7, role: 2 } })
  const wrapper = mount(LearningPath, {
    global: {
      plugins: [pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } }
    }
  })
  await nextTick()
  return { wrapper, pinia }
}

describe('LearningPath request ownership', () => {
  beforeEach(() => {
    mocks.completeItem.mockReset()
    mocks.fetchCurrentPath.mockReset()
  })

  afterEach(() => {
    delete window.__wjPath
  })

  it('keeps course B when course A resolves late', async () => {
    const courseA = deferred()
    const courseB = deferred()
    mocks.fetchCurrentPath.mockImplementation((id) => (id === 1 ? courseA.promise : courseB.promise))

    const { wrapper } = await mountPath()
    const signalA = mocks.fetchCurrentPath.mock.calls[0][1].signal
    courseState.courseId.value = 2
    await nextTick()

    expect.soft(signalA.aborted).toBe(true)
    expect.soft(mocks.fetchCurrentPath.mock.calls[1][1].signal).toEqual(expect.any(AbortSignal))

    courseB.resolve(pathData('course-b', 22))
    await flushPromises()
    courseA.resolve(pathData('course-a'))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.data.label).toBe('course-b')
    expect.soft(state.loading).toBe(false)
    expect(state.error).toBe('')
    wrapper.unmount()
  })

  it('does not let a stale load failure clear course B loading', async () => {
    const courseA = deferred()
    const courseB = deferred()
    mocks.fetchCurrentPath.mockImplementation((id) => (id === 1 ? courseA.promise : courseB.promise))

    const { wrapper } = await mountPath()
    courseState.courseId.value = 2
    await nextTick()
    courseA.reject(new Error('course A failed'))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.loading).toBe(true)
    expect.soft(state.error).toBe('')

    courseB.resolve(pathData('course-b', 22))
    await flushPromises()
    expect.soft(state.data.label).toBe('course-b')
    expect.soft(state.loading).toBe(false)
    expect(state.error).toBe('')
    wrapper.unmount()
  })

  it('refuses an old-course item before the course watcher flushes', async () => {
    const loadB = deferred()
    mocks.fetchCurrentPath
      .mockResolvedValueOnce(pathData('course-a'))
      .mockReturnValueOnce(loadB.promise)

    const { wrapper } = await mountPath()
    await flushPromises()
    const state = wrapper.vm.$.setupState

    courseState.courseId.value = 2
    const completion = state.markDone(11)
    expect(mocks.completeItem).not.toHaveBeenCalled()
    await completion
    await nextTick()

    loadB.resolve(pathData('course-b', 22))
    await flushPromises()
    expect(state.data.label).toBe('course-b')
    wrapper.unmount()
  })

  it('refuses an item outside the committed path context', async () => {
    mocks.fetchCurrentPath.mockResolvedValueOnce(pathData('course-a'))
    const { wrapper } = await mountPath()
    await flushPromises()

    await wrapper.vm.$.setupState.markDone(999)

    expect(mocks.completeItem).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('cancels an old-course completion and never reloads the new course from it', async () => {
    const completionA = deferred()
    const loadB = deferred()
    mocks.fetchCurrentPath.mockResolvedValueOnce(pathData('course-a'))
    mocks.fetchCurrentPath.mockImplementationOnce(() => loadB.promise)
    mocks.completeItem.mockReturnValue(completionA.promise)

    const { wrapper } = await mountPath()
    await flushPromises()
    const state = wrapper.vm.$.setupState
    const completionPromise = state.markDone(11)
    await nextTick()
    const completionSignal = mocks.completeItem.mock.calls[0][1].signal

    courseState.courseId.value = 2
    await nextTick()
    expect.soft(completionSignal.aborted).toBe(true)
    expect.soft(state.completingId).toBeNull()

    loadB.resolve(pathData('course-b', 22))
    completionA.resolve()
    await completionPromise
    await flushPromises()

    expect.soft(mocks.fetchCurrentPath).toHaveBeenCalledTimes(2)
    expect.soft(state.data.label).toBe('course-b')
    expect.soft(state.completingId).toBeNull()
    expect(state.error).toBe('')
    wrapper.unmount()
  })

  it('aborts and ignores the active load after the session owner changes', async () => {
    const oldOwner = deferred()
    const newOwner = deferred()
    mocks.fetchCurrentPath
      .mockReturnValueOnce(oldOwner.promise)
      .mockReturnValueOnce(newOwner.promise)
    const { wrapper, pinia } = await mountPath()
    const signal = mocks.fetchCurrentPath.mock.calls[0][1].signal

    useSessionStore(pinia).setSession({ token: 'other-token', user: { id: 8, role: 2 } })
    await nextTick()
    expect(signal.aborted).toBe(true)

    oldOwner.resolve(pathData('old-owner'))
    await flushPromises()
    expect(wrapper.vm.$.setupState.data).toBeNull()

    newOwner.resolve(pathData('new-owner', 22))
    await flushPromises()
    expect(wrapper.vm.$.setupState.data.label).toBe('new-owner')
    wrapper.unmount()
  })

  it('aborts on unmount and refuses a captured DEV-hook reload', async () => {
    const pending = deferred()
    mocks.fetchCurrentPath.mockReturnValue(pending.promise)
    const { wrapper } = await mountPath()
    const signal = mocks.fetchCurrentPath.mock.calls[0][1].signal
    const hook = window.__wjPath

    wrapper.unmount()
    expect.soft(signal.aborted).toBe(true)
    expect.soft(window.__wjPath).toBeUndefined()
    await hook.load()

    expect(mocks.fetchCurrentPath).toHaveBeenCalledTimes(1)
    pending.resolve(pathData('late'))
    await flushPromises()
  })
})
