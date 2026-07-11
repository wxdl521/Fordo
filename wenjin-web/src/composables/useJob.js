import { ref, unref, watch, onBeforeUnmount, getCurrentInstance } from 'vue'
import { getJob, cancelJob, isJobTerminal, isJobSucceeded } from '../api/jobs.js'

const POLL_MS = 1000

/**
 * 教师端 AI 作业轮询：1s 一次；离开/切课停止；双击复用 requestKey；take-latest。
 *
 * @param {object} [options]
 * @param {import('vue').Ref<number|null>|import('vue').ComputedRef<number|null>|(() => number|null)} [options.courseId]
 *   当前课程；切换时停止轮询并作废进行中的成功回调
 * @param {(job: object) => void} [options.onSuccess] 成功且仍属当前课程时回调
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
  let disposed = false
  /** take-latest：仅最新一次 start 可写回结果 */
  let epoch = 0
  /** 进行中幂等键 → jobId，双击复用 */
  const inflightByKey = new Map()
  let boundCourseId = null

  function readCourseId() {
    if (courseIdSource == null) return null
    if (typeof courseIdSource === 'function') return courseIdSource()
    return unref(courseIdSource)
  }

  function stopPolling() {
    if (pollTimer != null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function resetUi() {
    loading.value = false
    // 保留 job/error/progress 供 UI 展示终态，除非显式 clear
  }

  function clear() {
    stopPolling()
    job.value = null
    loading.value = false
    error.value = ''
    progress.value = 0
    inflightByKey.clear()
    boundCourseId = null
  }

  /**
   * 派发并轮询。
   * @param {(headers: Record<string,string>) => Promise<object>} dispatchFn
   *   调用后端派发接口，须返回 AiJobVO；headers 已含 Idempotency-Key
   * @param {object} [opts]
   * @param {string} [opts.requestKey] 幂等键；缺省自动生成
   * @param {number|null} [opts.courseId] 绑定课程；缺省取 courseIdSource
   */
  async function start(dispatchFn, opts = {}) {
    if (typeof dispatchFn !== 'function') {
      throw new TypeError('dispatchFn required')
    }
    if (disposed) return null

    const myEpoch = ++epoch
    const courseId = opts.courseId !== undefined ? opts.courseId : readCourseId()
    const requestKey =
      (opts.requestKey && String(opts.requestKey)) ||
      (typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID().replace(/-/g, '')
        : `k${Date.now()}${Math.random().toString(16).slice(2)}`)

    // 双击：同一 requestKey 且仍在进行 → 复用原 job 继续轮询
    const existingId = inflightByKey.get(requestKey)
    if (existingId != null && job.value?.id === existingId && !isJobTerminal(job.value)) {
      loading.value = true
      ensurePolling(myEpoch)
      return job.value
    }

    boundCourseId = courseId
    loading.value = true
    error.value = ''
    progress.value = 0

    try {
      const headers = { 'Idempotency-Key': requestKey }
      const created = await dispatchFn(headers)
      if (disposed || myEpoch !== epoch) return created

      job.value = created
      progress.value = created?.progress ?? 0
      inflightByKey.set(requestKey, created.id)

      if (isJobTerminal(created)) {
        await handleTerminal(created, myEpoch, courseId)
        return created
      }
      ensurePolling(myEpoch)
      return created
    } catch (e) {
      if (disposed || myEpoch !== epoch) return null
      loading.value = false
      error.value = e?.message || '派发失败'
      if (onError) onError(e)
      throw e
    }
  }

  function ensurePolling(myEpoch) {
    stopPolling()
    pollTimer = setInterval(() => {
      void pollOnce(myEpoch)
    }, pollIntervalMs)
  }

  async function pollOnce(myEpoch) {
    if (disposed || myEpoch !== epoch) {
      stopPolling()
      return
    }
    const id = job.value?.id
    if (id == null) {
      stopPolling()
      return
    }
    try {
      const latest = await getJob(id)
      if (disposed || myEpoch !== epoch) return
      job.value = latest
      progress.value = latest?.progress ?? 0
      if (isJobTerminal(latest)) {
        stopPolling()
        await handleTerminal(latest, myEpoch, boundCourseId)
      }
    } catch (e) {
      if (disposed || myEpoch !== epoch) return
      stopPolling()
      loading.value = false
      error.value = e?.message || '查询作业失败'
      if (onError) onError(e, job.value)
    }
  }

  async function handleTerminal(latest, myEpoch, courseAtStart) {
    loading.value = false
    // 清理 inflight
    for (const [k, id] of [...inflightByKey.entries()]) {
      if (id === latest?.id) inflightByKey.delete(k)
    }
    if (isJobSucceeded(latest)) {
      const current = readCourseId()
      // 仅当仍属当前课程时刷新
      if (courseAtStart == null || current == null || Number(current) === Number(courseAtStart)) {
        if (myEpoch === epoch && onSuccess) {
          onSuccess(latest)
        }
      }
    } else {
      error.value = latest?.errorCode || '作业失败'
      if (onError) {
        const err = new Error(error.value)
        err.job = latest
        onError(err, latest)
      }
    }
  }

  async function cancel() {
    const id = job.value?.id
    if (id == null) return null
    const myEpoch = epoch
    try {
      const updated = await cancelJob(id)
      if (disposed || myEpoch !== epoch) return updated
      job.value = updated
      if (isJobTerminal(updated)) {
        stopPolling()
        loading.value = false
      }
      return updated
    } catch (e) {
      error.value = e?.message || '取消失败'
      throw e
    }
  }

  // 切课：停止轮询并作废 epoch（成功回调不再触发）
  if (courseIdSource != null && typeof courseIdSource !== 'function') {
    watch(
      () => unref(courseIdSource),
      (next, prev) => {
        if (next !== prev) {
          epoch += 1
          stopPolling()
          loading.value = false
          inflightByKey.clear()
          boundCourseId = next
        }
      }
    )
  }

  function dispose() {
    disposed = true
    epoch += 1
    stopPolling()
    inflightByKey.clear()
  }

  // 组件 setup 内自动清理；单测 / 非组件调用可手动 dispose()
  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      dispose()
    })
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
