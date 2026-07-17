import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, ref } from 'vue'
import { useSessionStore } from '../../store/session.js'

const mocks = vi.hoisted(() => ({
  fetchGrowth: vi.fn()
}))

const courseState = vi.hoisted(() => ({ courseId: null }))

vi.mock('../../api/growth.js', () => ({
  fetchGrowth: (...args) => mocks.fetchGrowth(...args)
}))

vi.mock('../../composables/useStudentCourse.js', () => ({
  useStudentCourse: () => courseState
}))

import Growth from '../Growth.vue'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function growthData(label) {
  return {
    label,
    curve: [],
    compare: { summary: {}, baseline: [], latest: [] }
  }
}

async function mountGrowth(initialCourseId = 1) {
  courseState.courseId = ref(initialCourseId)
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 'student-token', user: { id: 7, role: 2 } })
  const wrapper = mount(Growth, {
    global: {
      plugins: [pinia],
      stubs: { RouterLink: { template: '<a><slot /></a>' } }
    }
  })
  await nextTick()
  return { wrapper, pinia }
}

describe('Growth request ownership', () => {
  beforeEach(() => {
    mocks.fetchGrowth.mockReset()
  })

  afterEach(() => {
    delete window.__wjGrowth
  })

  it('keeps course B when course A resolves after it', async () => {
    const courseA = deferred()
    const courseB = deferred()
    mocks.fetchGrowth.mockImplementation((id) => (id === 1 ? courseA.promise : courseB.promise))

    const { wrapper } = await mountGrowth()
    const signalA = mocks.fetchGrowth.mock.calls[0][1].signal
    courseState.courseId.value = 2
    await nextTick()

    expect.soft(signalA).toEqual(expect.any(AbortSignal))
    expect.soft(signalA.aborted).toBe(true)
    expect.soft(mocks.fetchGrowth.mock.calls[1][1].signal).toEqual(expect.any(AbortSignal))

    courseB.resolve(growthData('course-b'))
    await flushPromises()
    courseA.resolve(growthData('course-a'))
    await flushPromises()

    const state = wrapper.vm.$.setupState
    expect.soft(state.data.label).toBe('course-b')
    expect.soft(state.loading).toBe(false)
    expect(state.error).toBeNull()
    wrapper.unmount()
  })

  it('does not let a stale failure clear course B loading or set its error', async () => {
    const courseA = deferred()
    const courseB = deferred()
    mocks.fetchGrowth.mockImplementation((id) => (id === 1 ? courseA.promise : courseB.promise))

    const { wrapper } = await mountGrowth()
    courseState.courseId.value = 2
    await nextTick()

    courseA.reject(new Error('course A failed'))
    await flushPromises()
    const state = wrapper.vm.$.setupState
    expect.soft(state.loading).toBe(true)
    expect.soft(state.error).toBeNull()

    courseB.resolve(growthData('course-b'))
    await flushPromises()
    expect.soft(state.data.label).toBe('course-b')
    expect.soft(state.loading).toBe(false)
    expect(state.error).toBeNull()
    wrapper.unmount()
  })

  it('aborts and ignores the active load after the session owner changes', async () => {
    const oldOwner = deferred()
    const newOwner = deferred()
    mocks.fetchGrowth
      .mockReturnValueOnce(oldOwner.promise)
      .mockReturnValueOnce(newOwner.promise)
    const { wrapper, pinia } = await mountGrowth()
    const signal = mocks.fetchGrowth.mock.calls[0][1].signal

    useSessionStore(pinia).setSession({ token: 'other-token', user: { id: 8, role: 2 } })
    await nextTick()

    expect(signal.aborted).toBe(true)
    oldOwner.resolve(growthData('old-owner'))
    await flushPromises()
    expect(wrapper.vm.$.setupState.data).toBeNull()

    newOwner.resolve(growthData('new-owner'))
    await flushPromises()
    expect(wrapper.vm.$.setupState.data.label).toBe('new-owner')
    wrapper.unmount()
  })

  it('aborts on unmount and refuses a captured DEV-hook reload', async () => {
    const pending = deferred()
    mocks.fetchGrowth.mockReturnValue(pending.promise)
    const { wrapper } = await mountGrowth()
    const signal = mocks.fetchGrowth.mock.calls[0][1].signal
    const hook = window.__wjGrowth

    wrapper.unmount()
    expect.soft(signal.aborted).toBe(true)
    expect.soft(window.__wjGrowth).toBeUndefined()
    await hook.load()

    expect(mocks.fetchGrowth).toHaveBeenCalledTimes(1)
    pending.resolve(growthData('late'))
    await flushPromises()
  })
})
