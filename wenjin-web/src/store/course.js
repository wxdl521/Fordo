import { defineStore } from 'pinia'

/** 学生当前课程（由 store 统一读写，页面不再各自读 LS） */
export const STUDENT_COURSE_KEY = 'wj.student.currentCourseId'
/** 教师当前课程（教师各页共享同一 teacherCourseId） */
export const TEACHER_COURSE_KEY = 'wj.teacher.currentCourseId'

/**
 * @param {unknown} v
 * @returns {number | null}
 */
export function normalizeCourseId(v) {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : null
}

/**
 * 课程上下文：学生课 / 教师课列表与当前课。
 * 无课程时为 null（禁止 || 1 / || 2 演示回落）。
 */
export const useCourseStore = defineStore('course', {
  state: () => ({
    studentCourseId: /** @type {number | null} */ (null),
    teacherCourses: /** @type {Array<{ id: number, code?: string, name?: string, published?: boolean }>} */ ([]),
    teacherCourseId: /** @type {number | null} */ (null),
    hydrated: false
  }),

  getters: {
    /** 当前教师课程对象；无匹配时 null */
    teacherCourse(state) {
      if (state.teacherCourseId == null) return null
      return state.teacherCourses.find((c) => c.id === state.teacherCourseId) || null
    }
  },

  actions: {
    /** 从 localStorage 恢复（仅 hydrate 一次）。坏值 → null。 */
    hydrate() {
      if (this.hydrated) return
      this.studentCourseId = normalizeCourseId(localStorage.getItem(STUDENT_COURSE_KEY))
      this.teacherCourseId = normalizeCourseId(localStorage.getItem(TEACHER_COURSE_KEY))
      this.hydrated = true
    },

    /**
     * @param {unknown} id
     */
    setStudentCourseId(id) {
      const n = normalizeCourseId(id)
      this.studentCourseId = n
      if (n != null) localStorage.setItem(STUDENT_COURSE_KEY, String(n))
      else localStorage.removeItem(STUDENT_COURSE_KEY)
    },

    /**
     * @param {Array} list
     */
    setTeacherCourses(list) {
      this.teacherCourses = Array.isArray(list) ? list : []
    },

    /**
     * @param {unknown} id
     */
    setTeacherCourseId(id) {
      const n = normalizeCourseId(id)
      this.teacherCourseId = n
      if (n != null) localStorage.setItem(TEACHER_COURSE_KEY, String(n))
      else localStorage.removeItem(TEACHER_COURSE_KEY)
    },

    /**
     * 在教师课程列表中选中一门：preferId > 已存 id > 第一门；空列表 → null。
     * @param {unknown} [preferId]
     */
    selectTeacherCourse(preferId) {
      if (this.teacherCourses.length === 0) {
        this.setTeacherCourseId(null)
        return null
      }
      const prefer = normalizeCourseId(preferId)
      const saved = prefer ?? this.teacherCourseId
      const found = this.teacherCourses.find((c) => c.id === saved) || this.teacherCourses[0]
      this.setTeacherCourseId(found.id)
      return found
    },

    /** 登出时清空课程上下文（含 LS）。 */
    clearCourseContext() {
      this.studentCourseId = null
      this.teacherCourses = []
      this.teacherCourseId = null
      localStorage.removeItem(STUDENT_COURSE_KEY)
      localStorage.removeItem(TEACHER_COURSE_KEY)
    }
  }
})
