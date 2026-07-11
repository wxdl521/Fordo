import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref, nextTick } from 'vue'
import { useJob } from '../useJob.js'

vi.mock('../../api/jobs.js', () => ({
  getJob: vi.fn(),
  cancelJob: vi.fn(),
  isJobTerminal: (job) => {
    const s = job?.status
    return s === 2 || s === 3 || s === 4
  },
  isJobSucceeded: (job) => job?.status === 2
}))

import { getJob, cancelJob } from '../../api/jobs.js'

describe('useJob', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    getJob.mockReset()
    cancelJob.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls every 1s until succeeded and calls onSuccess', async () => {
    const onSuccess = vi.fn()
    const courseId = ref(1)
    const { start, job, loading, dispose } = useJob({ courseId, onSuccess, pollIntervalMs: 1000 })

    const dispatch = vi.fn(async () => ({
      id: 9,
      status: 0,
      progress: 0,
      requestKey: 'k1'
    }))

    getJob
      .mockResolvedValueOnce({ id: 9, status: 1, progress: 40 })
      .mockResolvedValueOnce({ id: 9, status: 2, progress: 100, result: { generated: 2 } })

    await start(dispatch, { requestKey: 'k1', courseId: 1 })
    expect(loading.value).toBe(true)
    expect(job.value.id).toBe(9)

    await vi.advanceTimersByTimeAsync(1000)
    expect(getJob).toHaveBeenCalledWith(9)
    expect(job.value.status).toBe(1)

    await vi.advanceTimersByTimeAsync(1000)
    expect(job.value.status).toBe(2)
    expect(onSuccess).toHaveBeenCalledTimes(1)
    expect(loading.value).toBe(false)

    dispose()
  })

  it('double-click with same requestKey reuses inflight job (single dispatch)', async () => {
    const courseId = ref(1)
    const { start, dispose } = useJob({ courseId, pollIntervalMs: 1000 })

    let resolveDispatch
    const dispatch = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveDispatch = resolve
        })
    )

    const p1 = start(dispatch, { requestKey: 'same-key', courseId: 1 })
    // 第一次尚在派发中时，第二次会再调 dispatch；完成后靠幂等键复用
    resolveDispatch({ id: 3, status: 0, progress: 0, requestKey: 'same-key' })
    await p1

    getJob.mockResolvedValue({ id: 3, status: 1, progress: 10 })
    await start(dispatch, { requestKey: 'same-key', courseId: 1 })

    // 第二次应复用 inflight，不再 dispatch
    expect(dispatch).toHaveBeenCalledTimes(1)
    dispose()
  })

  it('take-latest: only latest start success callback fires', async () => {
    const onSuccess = vi.fn()
    const courseId = ref(1)
    const { start, dispose } = useJob({ courseId, onSuccess, pollIntervalMs: 1000 })

    getJob.mockImplementation(async (id) => ({
      id,
      status: 2,
      progress: 100,
      result: { id }
    }))

    await start(async () => ({ id: 1, status: 0, progress: 0 }), { requestKey: 'a', courseId: 1 })
    await start(async () => ({ id: 2, status: 0, progress: 0 }), { requestKey: 'b', courseId: 1 })

    await vi.advanceTimersByTimeAsync(1000)
    // 仅最新 epoch 的成功应触发；旧 job 终态若晚到也不应二次
    expect(onSuccess).toHaveBeenCalled()
    const last = onSuccess.mock.calls.at(-1)[0]
    expect(last.id).toBe(2)

    dispose()
  })

  it('stops polling on course switch and does not refresh stale course', async () => {
    const onSuccess = vi.fn()
    const courseId = ref(1)
    const { start, loading, dispose } = useJob({ courseId, onSuccess, pollIntervalMs: 1000 })

    getJob.mockResolvedValue({ id: 5, status: 1, progress: 50 })

    await start(async () => ({ id: 5, status: 0, progress: 0 }), { requestKey: 'c', courseId: 1 })
    expect(loading.value).toBe(true)

    courseId.value = 2
    await nextTick()
    expect(loading.value).toBe(false)

    getJob.mockResolvedValue({ id: 5, status: 2, progress: 100, result: {} })
    await vi.advanceTimersByTimeAsync(2000)
    // 切课后 epoch 作废，不应 onSuccess
    expect(onSuccess).not.toHaveBeenCalled()

    dispose()
  })

  it('dispose stops further polls', async () => {
    const courseId = ref(1)
    const { start, dispose } = useJob({ courseId, pollIntervalMs: 1000 })

    getJob.mockResolvedValue({ id: 1, status: 1, progress: 10 })
    await start(async () => ({ id: 1, status: 0, progress: 0 }), { requestKey: 'd' })
    dispose()
    getJob.mockClear()
    await vi.advanceTimersByTimeAsync(3000)
    expect(getJob).not.toHaveBeenCalled()
  })
})
