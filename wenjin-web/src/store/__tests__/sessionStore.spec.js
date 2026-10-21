import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore, parseStoredUser, TOKEN_KEY, USER_KEY } from '../session.js'
import { useCourseStore, STUDENT_COURSE_KEY, TEACHER_COURSE_KEY } from '../course.js'

vi.mock('../../api/user.js', () => ({
  me: vi.fn()
}))

import { me } from '../../api/user.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('sessionStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('parseStoredUser: bad localStorage JSON → null', () => {
    expect(parseStoredUser(null)).toBeNull()
    expect(parseStoredUser('')).toBeNull()
    expect(parseStoredUser('not-json')).toBeNull()
    expect(parseStoredUser('[]')).toBeNull()
    expect(parseStoredUser('"str"')).toBeNull()
    expect(parseStoredUser('{"id":2,"role":2}')).toEqual({ id: 2, role: 2 })
  })

  it('hydrate: bad wj_user with token → user null (no throw)', () => {
    localStorage.setItem(TOKEN_KEY, 'tok')
    localStorage.setItem(USER_KEY, '{broken')
    const session = useSessionStore()
    session.hydrate()
    expect(session.token).toBe('tok')
    expect(session.user).toBeNull()
    expect(session.isAuthenticated).toBe(false)
  })

  it('hydrate: no token clears user cache', () => {
    localStorage.setItem(USER_KEY, JSON.stringify({ id: 9 }))
    const session = useSessionStore()
    session.hydrate()
    expect(session.token).toBeNull()
    expect(session.user).toBeNull()
    expect(localStorage.getItem(USER_KEY)).toBeNull()
  })

  it('setSession / clearSession: login logout observed', () => {
    const session = useSessionStore()
    const course = useCourseStore()
    course.setStudentCourseId(5)
    course.setTeacherCourseId(3)

    session.setSession({
      token: 'abc',
      user: { id: 2, role: 2, realName: '测' }
    })
    expect(session.isAuthenticated).toBe(true)
    expect(session.userId).toBe(2)
    expect(localStorage.getItem(TOKEN_KEY)).toBe('abc')
    expect(JSON.parse(localStorage.getItem(USER_KEY)).id).toBe(2)

    session.clearSession()
    expect(session.token).toBeNull()
    expect(session.user).toBeNull()
    expect(session.isAuthenticated).toBe(false)
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(USER_KEY)).toBeNull()
    // logout 清空课程上下文
    expect(course.studentCourseId).toBeNull()
    expect(course.teacherCourseId).toBeNull()
    expect(localStorage.getItem(STUDENT_COURSE_KEY)).toBeNull()
    expect(localStorage.getItem(TEACHER_COURSE_KEY)).toBeNull()
  })

  it('isAdmin / isTeacher getters follow the role code (3=admin, 1|3=teacher side)', () => {
    const session = useSessionStore()
    expect(session.isAdmin).toBe(false)

    session.setSession({ token: 't', user: { id: 1, role: 3 } })
    expect(session.isAdmin).toBe(true)
    expect(session.isTeacher).toBe(true)

    session.setSession({ token: 't', user: { id: 2, role: 1 } })
    expect(session.isAdmin).toBe(false)
    expect(session.isTeacher).toBe(true)

    session.setSession({ token: 't', user: { id: 3, role: 2 } })
    expect(session.isAdmin).toBe(false)
    expect(session.isTeacher).toBe(false)

    session.clearSession()
    expect(session.isAdmin).toBe(false)
  })

  it('refreshMe success updates user; failure clears session (401 path)', async () => {
    const session = useSessionStore()
    session.setSession({ token: 't', user: { id: 1, role: 2 } })
    me.mockResolvedValueOnce({ id: 1, role: 2, realName: '刷新后' })
    const u = await session.refreshMe()
    expect(u.realName).toBe('刷新后')
    expect(session.user.realName).toBe('刷新后')

    me.mockRejectedValueOnce(new Error('unauthorized'))
    const gone = await session.refreshMe()
    expect(gone).toBeNull()
    expect(session.token).toBeNull()
    expect(session.user).toBeNull()
  })

  it('refreshMe ignores a stale success after a replacement login', async () => {
    const session = useSessionStore()
    const pending = deferred()
    session.setSession({ token: 'token-a', user: { id: 1, role: 2 } })
    me.mockReturnValueOnce(pending.promise)

    const refresh = session.refreshMe()
    await vi.waitFor(() => expect(me).toHaveBeenCalledOnce())
    session.setSession({ token: 'token-b', user: { id: 2, role: 1 } })
    pending.resolve({ id: 1, role: 2, realName: 'old user' })

    await expect(refresh).resolves.toBeNull()
    expect(session.token).toBe('token-b')
    expect(session.user).toEqual({ id: 2, role: 1 })
    expect(localStorage.getItem(TOKEN_KEY)).toBe('token-b')
    expect(JSON.parse(localStorage.getItem(USER_KEY))).toEqual({ id: 2, role: 1 })
  })

  it('refreshMe ignores a stale failure after a replacement login', async () => {
    const session = useSessionStore()
    const pending = deferred()
    session.setSession({ token: 'token-a', user: { id: 1, role: 2 } })
    me.mockReturnValueOnce(pending.promise)

    const refresh = session.refreshMe()
    await vi.waitFor(() => expect(me).toHaveBeenCalledOnce())
    session.setSession({ token: 'token-b', user: { id: 2, role: 1 } })
    pending.reject(new Error('stale unauthorized'))

    await expect(refresh).resolves.toBeNull()
    expect(session.token).toBe('token-b')
    expect(session.user).toEqual({ id: 2, role: 1 })
    expect(localStorage.getItem(TOKEN_KEY)).toBe('token-b')
    expect(JSON.parse(localStorage.getItem(USER_KEY))).toEqual({ id: 2, role: 1 })
  })

  it('refreshMe with no token → null and clear', async () => {
    const session = useSessionStore()
    session.hydrate()
    const u = await session.refreshMe()
    expect(u).toBeNull()
    expect(me).not.toHaveBeenCalled()
  })
})
