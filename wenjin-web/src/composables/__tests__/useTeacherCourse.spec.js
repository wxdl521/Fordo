import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  fetchTeacherCourses: vi.fn()
}))

vi.mock('../../api/teacher.js', () => ({
  fetchTeacherCourses: (...args) => mocks.fetchTeacherCourses(...args)
}))

import { useTeacherCourse } from '../useTeacherCourse.js'
import { useCourseStore } from '../../store/course.js'
import { useSessionStore } from '../../store/session.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useTeacherCourse lifecycle ownership', () => {
  let pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    vi.clearAllMocks()
    mocks.fetchTeacherCourses.mockReset()
    useSessionStore(pinia).setSession({
      token: 'token-a',
      user: { id: 1, role: 2 }
    })
  })

  afterEach(() => {
    localStorage.clear()
  })

  function mountHarness() {
    let teacherCourse
    const Harness = defineComponent({
      setup() {
        teacherCourse = useTeacherCourse()
        return () => h('div')
      }
    })
    const wrapper = mount(Harness, { global: { plugins: [pinia] } })
    return { wrapper, teacherCourse }
  }

  it('takes the latest instance load and aborts the older request', async () => {
    const older = deferred()
    const newer = deferred()
    mocks.fetchTeacherCourses
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise)
    const teacherCourse = useTeacherCourse({ autoLoad: false })

    const olderLoad = teacherCourse.loadCourses(1)
    const olderConfig = mocks.fetchTeacherCourses.mock.calls[0][0]
    const newerLoad = teacherCourse.loadCourses(2)

    expect.soft(olderConfig).toEqual(expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect.soft(olderConfig?.signal?.aborted).toBe(true)

    newer.resolve([{ id: 2, name: 'Current course', code: 'B' }])
    await newerLoad
    older.resolve([{ id: 1, name: 'Stale course', code: 'A' }])
    await olderLoad

    const store = useCourseStore(pinia)
    expect.soft(store.teacherCourseId).toBe(2)
    expect(store.teacherCourses).toEqual([{ id: 2, name: 'Current course', code: 'B' }])
  })

  it('aborts and suppresses the automatic load after component unmount', async () => {
    const pending = deferred()
    mocks.fetchTeacherCourses.mockReturnValue(pending.promise)
    const { wrapper } = mountHarness()
    const config = mocks.fetchTeacherCourses.mock.calls[0][0]

    wrapper.unmount()
    expect.soft(config).toEqual(expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect.soft(config?.signal?.aborted).toBe(true)

    pending.resolve([{ id: 1, name: 'Late course', code: 'A' }])
    await flushPromises()
    expect.soft(useCourseStore(pinia).teacherCourses).toEqual([])
    expect(useCourseStore(pinia).teacherCourseId).toBeNull()
  })

  it('does not repopulate teacher courses when logout clears the session', async () => {
    const pending = deferred()
    mocks.fetchTeacherCourses.mockReturnValue(pending.promise)
    const { wrapper } = mountHarness()
    const config = mocks.fetchTeacherCourses.mock.calls[0][0]

    useSessionStore(pinia).clearSession()
    expect(config.signal.aborted).toBe(true)
    pending.resolve([{ id: 1, name: 'Logged-out course', code: 'A' }])
    await flushPromises()

    expect.soft(useCourseStore(pinia).teacherCourses).toEqual([])
    expect(useCourseStore(pinia).teacherCourseId).toBeNull()
    wrapper.unmount()
  })

  it.each([
    ['token', { token: 'token-b', user: { id: 1, role: 2 } }],
    ['user', { token: 'token-a', user: { id: 2, role: 2 } }]
  ])('aborts and suppresses an old response when the session %s changes', async (_label, nextSession) => {
    const pending = deferred()
    mocks.fetchTeacherCourses.mockReturnValue(pending.promise)
    const { wrapper } = mountHarness()
    const session = useSessionStore(pinia)
    const courses = useCourseStore(pinia)
    const config = mocks.fetchTeacherCourses.mock.calls[0][0]

    session.setSession(nextSession)
    expect(config.signal.aborted).toBe(true)
    courses.setTeacherCourses([{ id: 2, name: 'New user course', code: 'B' }])
    courses.setTeacherCourseId(2)
    pending.resolve([{ id: 1, name: 'Old user course', code: 'A' }])
    await flushPromises()

    expect.soft(courses.teacherCourseId).toBe(2)
    expect(courses.teacherCourses).toEqual([{ id: 2, name: 'New user course', code: 'B' }])
    wrapper.unmount()
  })

  it.each([
    ['token', { token: null, user: { id: 1, role: 2 } }],
    ['user id', { token: 'token-a', user: null }]
  ])('does not fetch or mutate courses without a valid session %s', async (_label, ownerlessSession) => {
    const session = useSessionStore(pinia)
    const courses = useCourseStore(pinia)
    const teacherCourse = useTeacherCourse({ autoLoad: false })
    session.setSession(ownerlessSession)
    courses.setTeacherCourses([{ id: 9, name: 'Preserved course', code: 'P' }])
    courses.setTeacherCourseId(9)
    mocks.fetchTeacherCourses.mockResolvedValue([{ id: 1, name: 'Unauthorized course', code: 'A' }])

    const result = await teacherCourse.loadCourses()

    expect.soft(result).toBeNull()
    expect.soft(mocks.fetchTeacherCourses).not.toHaveBeenCalled()
    expect.soft(courses.teacherCourseId).toBe(9)
    expect(courses.teacherCourses).toEqual([{ id: 9, name: 'Preserved course', code: 'P' }])
  })
})
