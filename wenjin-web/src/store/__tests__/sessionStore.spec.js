import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore, parseStoredUser, TOKEN_KEY, USER_KEY } from '../session.js'
import { useCourseStore, STUDENT_COURSE_KEY, TEACHER_COURSE_KEY } from '../course.js'

vi.mock('../../api/user.js', () => ({
  me: vi.fn()
}))

import { me } from '../../api/user.js'

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

  it('refreshMe with no token → null and clear', async () => {
    const session = useSessionStore()
    session.hydrate()
    const u = await session.refreshMe()
    expect(u).toBeNull()
    expect(me).not.toHaveBeenCalled()
  })
})
