import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { fetchTeacherCourses } from '../api/teacher.js'
import { useCourseStore } from '../store/course.js'

export { TEACHER_COURSE_KEY as LS_KEY } from '../store/course.js'

/**
 * 教师端多课程：各教师页共享 course store 中的 teacherCourseId / teacherCourses。
 * @param {{ autoLoad?: boolean }} [options]
 */
export function useTeacherCourse(options = {}) {
  const { autoLoad = true } = options
  const courseStore = useCourseStore()
  if (!courseStore.hydrated) courseStore.hydrate()

  const { teacherCourses: courses, teacherCourseId } = storeToRefs(courseStore)
  const currentCourse = computed(() => courseStore.teacherCourse)
  const courseId = computed(() => courseStore.teacherCourseId)

  /**
   * @param {unknown} [preferId]
   */
  async function loadCourses(preferId) {
    try {
      const list = await fetchTeacherCourses()
      courseStore.setTeacherCourses(Array.isArray(list) ? list : [])
    } catch {
      courseStore.setTeacherCourses([])
    }
    return courseStore.selectTeacherCourse(preferId)
  }

  /**
   * @param {unknown} id
   */
  function selectCourse(id) {
    const n = Number(id)
    if (!(n > 0)) return
    const found = courseStore.teacherCourses.find((c) => c.id === n)
    if (found) courseStore.setTeacherCourseId(found.id)
  }

  if (autoLoad) {
    onMounted(() => {
      loadCourses()
    })
  }

  return {
    courses,
    currentCourse,
    courseId,
    teacherCourseId,
    loadCourses,
    selectCourse
  }
}
