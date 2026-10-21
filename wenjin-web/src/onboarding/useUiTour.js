import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../store/session.js'
import { useCourseStore } from '../store/course.js'
import { stepsForRole, storageKey } from './tourSteps.js'

const open = ref(false)
const stepIndex = ref(0)
const isTeacherTour = ref(false)
const finishMode = ref('close')
let autoWatchInstalled = false

function readDone(key) {
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

function writeDone(key) {
  try {
    localStorage.setItem(key, '1')
  } catch {
    /* ignore */
  }
}

/**
 * 全局聚光灯导览。状态模块单例；自动弹出 watch 只装一次。
 */
export function useUiTour() {
  const route = useRoute()
  const router = useRouter()
  const session = useSessionStore()
  const course = useCourseStore()

  const steps = computed(() => stepsForRole(isTeacherTour.value))
  const currentStep = computed(() => steps.value[stepIndex.value] || steps.value[0] || null)
  const total = computed(() => steps.value.length)
  const isLast = computed(() => stepIndex.value >= total.value - 1)
  const doneKey = computed(() => storageKey(session.userId, isTeacherTour.value))

  function markDone() {
    writeDone(doneKey.value)
  }

  function close() {
    open.value = false
  }

  function start({ teacher = false, then = 'close', index = 0 } = {}) {
    if (!session.isAuthenticated) return
    isTeacherTour.value = !!teacher
    finishMode.value = then || 'close'
    stepIndex.value = Math.min(Math.max(0, index), Math.max(0, steps.value.length - 1))
    open.value = true
  }

  function finish() {
    const then = finishMode.value
    markDone()
    close()
    if (then === 'diagnostic') {
      const q = {}
      if (course.studentCourseId) q.courseId = course.studentCourseId
      router.push({ path: '/diagnostic', query: Object.keys(q).length ? q : undefined })
    }
  }

  function skipAll() {
    if (!isTeacherTour.value) return
    finish()
  }

  function next() {
    if (isLast.value) {
      finish()
      return
    }
    stepIndex.value += 1
  }

  function prev() {
    if (stepIndex.value > 0) stepIndex.value -= 1
  }

  if (!autoWatchInstalled) {
    autoWatchInstalled = true
    watch(
      () => [session.isAuthenticated, session.userId, route.path],
      ([authed, uid, path]) => {
        if (!authed || uid == null) {
          open.value = false
          return
        }
        if (open.value) return

        const onMap = path === '/map'
        const onTeacher = path === '/teacher/graph'
        if (!onMap && !onTeacher) return

        const teacher = onTeacher
        if (readDone(storageKey(uid, teacher))) return

        if (onMap) {
          start({ teacher: false, then: 'diagnostic' })
          return
        }
        start({ teacher: true, then: 'close' })
      },
      { immediate: true }
    )
  }

  return {
    open,
    stepIndex,
    steps,
    currentStep,
    total,
    isLast,
    isTeacherTour,
    start,
    close,
    skipAll,
    finish,
    next,
    prev,
    finishMode,
  }
}
