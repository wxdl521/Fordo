import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useCourseStore, normalizeCourseId } from '../store/course.js'

export { STUDENT_COURSE_KEY as LS_KEY } from '../store/course.js'

/**
 * 学生端当前课程：URL query 优先，其次 course store（无课 → null，禁止回落 1）。
 */
export function useStudentCourse() {
  const route = useRoute()
  const courseStore = useCourseStore()
  if (!courseStore.hydrated) courseStore.hydrate()

  const courseId = computed(() => {
    const fromQuery = normalizeCourseId(route.query.courseId)
    if (fromQuery != null) return fromQuery
    return courseStore.studentCourseId
  })

  function rememberCourse(id) {
    courseStore.setStudentCourseId(id)
  }

  watch(
    () => route.query.courseId,
    (v) => {
      const id = normalizeCourseId(v)
      if (id != null) rememberCourse(id)
    },
    { immediate: true }
  )

  return { courseId, rememberCourse }
}
