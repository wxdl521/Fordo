import { ref, computed, onMounted } from 'vue'
import { fetchTeacherCourses } from '../api/teacher.js'

export const LS_KEY = 'wj.teacher.currentCourseId'

/** 教师端多课程：与图谱审核页共用 localStorage 中的当前课程 */
export function useTeacherCourse() {
  const courses = ref([])
  const currentCourse = ref(null)

  const courseId = computed(() => currentCourse.value?.id ?? null)

  async function loadCourses(preferId) {
    try {
      const list = await fetchTeacherCourses()
      courses.value = Array.isArray(list) ? list : []
    } catch {
      courses.value = []
    }
    if (courses.value.length === 0) {
      currentCourse.value = null
      return
    }
    const saved = preferId ?? Number(localStorage.getItem(LS_KEY))
    currentCourse.value = courses.value.find(c => c.id === saved) || courses.value[0]
    localStorage.setItem(LS_KEY, String(currentCourse.value.id))
  }

  onMounted(() => loadCourses())

  return { courses, currentCourse, courseId, loadCourses }
}