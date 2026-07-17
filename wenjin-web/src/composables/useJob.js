import { ref, unref, watch, onBeforeUnmount, getCurrentInstance } from 'vue'
import { getJob, cancelJob, isJobTerminal, isJobSucceeded } from '../api/jobs.js'

const POLL_MS = 1000

/**
 * 教师端 AI 作业轮询：串行查询；离开/切课停止；一次用户动作复用一个幂等键。
 *
 * @param {object} [options]
 * @param {import('vue').Ref<number|null>|import('vue').ComputedRef<number|null>|(() => number|null)} [options.courseId]
 * @param {(job: object) => void} [options.onSuccess]
 * @param {(err: Error, job?: object) => void} [options.onError]
 * @param {number} [options.pollIntervalMs]
 */
export function useJob(options = {}) {
  const {
    courseId: courseIdSource = null,
    onSuccess = null,
    onError = null,
    pollIntervalMs = POLL_MS
  } = options

  const job = ref(null)
  const loading = ref(false)
  const error = ref('')
  const progress = ref(0)

  let pollTimer = null
  let pollInFlightEpoch = null
  let disposed = false
  let epoch = 0
  let boundCourseId = null
  let activeAction = null

  function readCourseId() {
    if (courseIdSource == null) return null
    if (typeof courseIdSource === 'function') return courseIdSource()
    return unref(courseIdSource)
  }

  function createRequestKey() {
    return typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID().replace(/-/g, '')
      : `k${Date.now()}${Math.random().toString(16).slice(2)}`
  }

  function stopPolling() {
    if (pollTimer != null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function clearActiveAction(myEpoch = null) {
    if (myEpoch == null || activeAction?.epoch === myEpoch) {
      activeAction = null
    }
  }

  function clear() {
    epoch += 1
    stopPolling()
    clearActiveAction()
    job.value = null
    loading.value = false
    error.value = ''
    progress.value = 0
    boundCourseId = null
  }

  function courseStillCurrent(courseAtStart) {
    if (courseIdSource == null) return true
    const current = readCourseId()
    if (courseAtStart == null || current == null) {
      return courseAtStart == null && current == null
    }
    return Number(current) === Number(courseAtStart)
  }

  async function handleTerminal(latest, myEpoch, courseAtStart) {
    if (disposed || myEpoch !== epoch) return
    loading.value = false
    clearActiveAction(myEpoch)

    if (isJobSucceeded(latest)) {
      if (courseStillCurrent(courseAtStart) && myEpoch === epoch && onSuccess) {
        onSuccess(latest)
      }
      return
    }

    error.value = latest?.errorCode || '作业失败'
    if (onError) {
      const terminalError = new Error(error.value)
      terminalError.job = latest
      onError(terminalError, latest)
    }
  }

  function finishCancellation(myEpoch) {
    if (disposed || myEpoch !== epoch) return
    loading.value = false
    clearActiveAction(myEpoch)
  }

  async function pollOnce(myEpoch, courseAtStart, cancellationObservation) {
    if (disposed || myEpoch !== epoch) return false
    const id = job.value?.id
    if (id == null) return false

    try {
      const latest = await getJob(id)
      if (disposed || myEpoch !== epoch) return false

      job.value = latest
      progress.value = latest?.progress ?? 0
      if (!isJobTerminal(latest)) return true

      if (cancellationObservation) {
        finishCancellation(myEpoch)
      } else {
        await handleTerminal(latest, myEpoch, courseAtStart)
      }
      return false
    } catch (pollError) {
      if (disposed || myEpoch !== epoch) return false
      loading.value = false
      error.value = pollError?.message || '查询作业失败'
      if (onError) onError(pollError, job.value)
      return false
    }
  }

  function schedulePoll(myEpoch, courseAtStart, cancellationObservation = false) {
    stopPolling()
    if (disposed || myEpoch !== epoch || isJobTerminal(job.value)) return
    pollTimer = setTimeout(() => {
      pollTimer = null
      void pollLoop(myEpoch, courseAtStart, cancellationObservation)
    }, pollIntervalMs)
  }

  async function pollLoop(myEpoch, courseAtStart, cancellationObservation) {
    pollInFlightEpoch = myEpoch
    let shouldContinue
    try {
      shouldContinue = await pollOnce(myEpoch, courseAtStart, cancellationObservation)
    } finally {
      if (pollInFlightEpoch === myEpoch) pollInFlightEpoch = null
    }

    const action = activeAction?.epoch === myEpoch ? activeAction : null
    const resumeRequested = Boolean(action?.resumeRequested)
    if (action) action.resumeRequested = false
    if (
      (shouldContinue || resumeRequested) &&
      !disposed &&
      myEpoch === epoch &&
      !isJobTerminal(job.value)
    ) {
      schedulePoll(myEpoch, courseAtStart, cancellationObservation)
    }
  }

  function applyCancellationResult(updated, myEpoch, courseAtCancel) {
    if (disposed || myEpoch !== epoch) return
    job.value = updated
    progress.value = updated?.progress ?? progress.value
    if (isJobTerminal(updated)) {
      loading.value = false
    } else {
      loading.value = true
      schedulePoll(myEpoch, courseAtCancel, true)
    }
  }

  async function cancelKnownJob(id, myEpoch, courseAtCancel) {
    try {
      const updated = await cancelJob(id)
      applyCancellationResult(updated, myEpoch, courseAtCancel)
      return updated
    } catch (cancelError) {
      if (!disposed && myEpoch === epoch) {
        loading.value = false
        error.value = cancelError?.message || '取消失败'
      }
      throw cancelError
    }
  }

  async function dispatchAction(action, dispatchFn) {
    const { epoch: myEpoch, courseId, key } = action
    try {
      const created = await dispatchFn({ 'Idempotency-Key': key })
      action.jobId = created?.id ?? null

      if (action.cancelRequested) {
        if (action.jobId != null) {
          return cancelKnownJob(action.jobId, action.cancelEpoch, courseId)
        }
        if (!isJobTerminal(created)) {
          throw new Error('派发作业未返回 ID')
        }
        if (!disposed && action.cancelEpoch === epoch) {
          job.value = created
          progress.value = created?.progress ?? 0
          loading.value = false
        }
        return created
      }
      if (disposed || myEpoch !== epoch) return created

      job.value = created
      progress.value = created?.progress ?? 0
      if (isJobTerminal(created)) {
        await handleTerminal(created, myEpoch, courseId)
      } else {
        schedulePoll(myEpoch, courseId)
      }
      return created
    } catch (dispatchError) {
      const writeEpoch = action.cancelRequested ? action.cancelEpoch : myEpoch
      if (disposed || writeEpoch !== epoch) return null
      loading.value = false
      error.value = dispatchError?.message || '派发失败'
      clearActiveAction(writeEpoch)
      if (onError) onError(dispatchError)
      throw dispatchError
    }
  }

  /**
   * 派发并轮询。
   * @param {(headers: Record<string,string>) => Promise<object>} dispatchFn
   * @param {object} [opts]
   * @param {string} [opts.requestKey] 显式幂等键；缺省时按当前用户动作生成
   * @param {number|null} [opts.courseId] 绑定课程；缺省取 courseIdSource
   */
  async function start(dispatchFn, opts = {}) {
    if (typeof dispatchFn !== 'function') {
      throw new TypeError('dispatchFn required')
    }
    if (disposed) return null

    const requestedKey = opts.requestKey ? String(opts.requestKey) : null
    if (
      activeAction &&
      activeAction.epoch === epoch &&
      (requestedKey == null || requestedKey === activeAction.key)
    ) {
      loading.value = true
      error.value = ''
      if (activeAction.jobId != null && !isJobTerminal(job.value) && pollTimer == null) {
        if (pollInFlightEpoch === epoch) {
          activeAction.resumeRequested = true
        } else {
          schedulePoll(activeAction.epoch, activeAction.courseId)
        }
      }
      return activeAction.promise
    }

    const myEpoch = ++epoch
    stopPolling()
    const courseId = opts.courseId !== undefined ? opts.courseId : readCourseId()
    const action = {
      cancelEpoch: null,
      cancelRequested: false,
      courseId,
      epoch: myEpoch,
      jobId: null,
      key: requestedKey || createRequestKey(),
      promise: null,
      resumeRequested: false
    }

    boundCourseId = courseId
    loading.value = true
    error.value = ''
    progress.value = 0
    activeAction = action
    action.promise = dispatchAction(action, dispatchFn)
    return action.promise
  }

  async function cancel() {
    const action = activeAction
    if (action && action.jobId == null) {
      const myEpoch = ++epoch
      action.cancelRequested = true
      action.cancelEpoch = myEpoch
      stopPolling()
      clearActiveAction()
      loading.value = true
      error.value = ''
      return action.promise
    }

    const id = action?.jobId ?? (!isJobTerminal(job.value) ? job.value?.id : null)
    if (id == null) return null

    const myEpoch = ++epoch
    const courseAtCancel = action?.courseId ?? boundCourseId
    stopPolling()
    clearActiveAction()
    error.value = ''
    return cancelKnownJob(id, myEpoch, courseAtCancel)
  }

  if (courseIdSource != null) {
    watch(
      () => readCourseId(),
      (next, previous) => {
        if (next === previous) return
        epoch += 1
        stopPolling()
        clearActiveAction()
        loading.value = false
        boundCourseId = next
      }
    )
  }

  function dispose() {
    disposed = true
    epoch += 1
    stopPolling()
    clearActiveAction()
  }

  if (getCurrentInstance()) {
    onBeforeUnmount(dispose)
  }

  return {
    job,
    loading,
    error,
    progress,
    start,
    cancel,
    clear,
    dispose,
    stopPolling
  }
}
