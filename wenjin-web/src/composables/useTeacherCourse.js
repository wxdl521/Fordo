import { computed, getCurrentInstance, onBeforeUnmount, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { fetchTeacherCourses } from '../api/teacher.js'
import { useCourseStore } from '../store/course.js'
import { useSessionStore } from '../store/session.js'
import { createLatestRequest } from '../utils/latestRequest.js'

export { TEACHER_COURSE_KEY as LS_KEY } from '../store/course.js'

/**
 * 教师端多课程：各教师页共享 course store 中的 teacherCourseId / teacherCourses。
 * @param {{ autoLoad?: boolean }} [options]
 */
export function useTeacherCourse(options = {}) {
  const { autoLoad = true } = options
  const courseStore = useCourseStore()
  const sessionStore = useSessionStore()
  if (!courseStore.hydrated) courseStore.hydrate()
  const coursesRequest = createLatestRequest()
  let disposed = false

  const { teacherCourses: courses, teacherCourseId } = storeToRefs(courseStore)
  const currentCourse = computed(() => courseStore.teacherCourse)
  const courseId = computed(() => courseStore.teacherCourseId)

  function isAbortError(err) {
    return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
  }

  function isCurrentOwner(request, owner) {
    return !disposed
      && request.isCurrent()
      && sessionStore.token === owner.token
      && sessionStore.userId === owner.userId
  }

  /**
   * @param {unknown} [preferId]
   */
  async function loadCourses(preferId) {
    if (disposed) return null
    const request = coursesRequest.next()
    const owner = { token: sessionStore.token, userId: sessionStore.userId }
    try {
      const list = await fetchTeacherCourses({ signal: request.signal })
      if (!isCurrentOwner(request, owner)) return null
      courseStore.setTeacherCourses(Array.isArray(list) ? list : [])
    } catch (err) {
      if (!isCurrentOwner(request, owner) || isAbortError(err)) return null
      courseStore.setTeacherCourses([])
    }
    if (!isCurrentOwner(request, owner)) return null
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
      void loadCourses()
    })
  }

  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      disposed = true
      coursesRequest.cancel()
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
