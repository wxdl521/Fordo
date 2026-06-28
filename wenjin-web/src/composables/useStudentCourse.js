import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'

export const LS_KEY = 'wj.student.currentCourseId'

/** 学生端当前课程：URL query 优先，其次 localStorage */
export function useStudentCourse() {
  const route = useRoute()

  const courseId = computed(() => {
    const q = Number(route.query.courseId)
    if (q > 0) return q
    const saved = Number(localStorage.getItem(LS_KEY))
    return saved > 0 ? saved : null
  })

  function rememberCourse(id) {
    const n = Number(id)
    if (n > 0) localStorage.setItem(LS_KEY, String(n))
  }

  watch(
    () => route.query.courseId,
    (v) => {
      const id = Number(v)
      if (id > 0) rememberCourse(id)
    },
    { immediate: true }
  )

  return { courseId, rememberCourse }
}