import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useCourseStore,
  normalizeCourseId,
  STUDENT_COURSE_KEY,
  TEACHER_COURSE_KEY
} from '../course.js'
import { useSessionStore } from '../session.js'

vi.mock('../../api/teacher.js', () => ({
  fetchTeacherCourses: vi.fn()
}))

import { fetchTeacherCourses } from '../../api/teacher.js'
import { useTeacherCourse } from '../../composables/useTeacherCourse.js'

describe('courseStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('no course → null not 1', () => {
    const store = useCourseStore()
    store.hydrate()
    expect(store.studentCourseId).toBeNull()
    expect(store.teacherCourseId).toBeNull()
    expect(normalizeCourseId(undefined)).toBeNull()
    expect(normalizeCourseId('')).toBeNull()
    expect(normalizeCourseId(0)).toBeNull()
    expect(normalizeCourseId('abc')).toBeNull()
    expect(normalizeCourseId(5)).toBe(5)
  })

  it('bad localStorage course ids → null', () => {
    localStorage.setItem(STUDENT_COURSE_KEY, 'nope')
    localStorage.setItem(TEACHER_COURSE_KEY, '-1')
    const store = useCourseStore()
    store.hydrate()
    expect(store.studentCourseId).toBeNull()
    expect(store.teacherCourseId).toBeNull()
  })

  it('studentCourseId set/get persists', () => {
    const store = useCourseStore()
    store.setStudentCourseId(7)
    expect(store.studentCourseId).toBe(7)
    expect(localStorage.getItem(STUDENT_COURSE_KEY)).toBe('7')
    store.setStudentCourseId(null)
    expect(store.studentCourseId).toBeNull()
    expect(localStorage.getItem(STUDENT_COURSE_KEY)).toBeNull()
  })

  it('teacher pages share teacherCourseId via store', async () => {
    fetchTeacherCourses.mockResolvedValue([
      { id: 10, name: '课A', code: 'A' },
      { id: 20, name: '课B', code: 'B' }
    ])

    const a = useTeacherCourse({ autoLoad: false })
    const b = useTeacherCourse({ autoLoad: false })
    await a.loadCourses(10)
    expect(a.courseId.value).toBe(10)
    expect(b.courseId.value).toBe(10)
    expect(a.currentCourse.value?.name).toBe('课A')
    expect(b.currentCourse.value?.name).toBe('课A')

    b.selectCourse(20)
    expect(a.courseId.value).toBe(20)
    expect(b.courseId.value).toBe(20)
    expect(useCourseStore().teacherCourseId).toBe(20)
  })

  it('logout clears course context', () => {
    const course = useCourseStore()
    const session = useSessionStore()
    course.setStudentCourseId(1)
    course.setTeacherCourses([{ id: 2, name: 'T' }])
    course.setTeacherCourseId(2)
    session.setSession({ token: 't', user: { id: 1, role: 2 } })
    session.clearSession()
    expect(course.studentCourseId).toBeNull()
    expect(course.teacherCourseId).toBeNull()
    expect(course.teacherCourses).toEqual([])
  })
})
