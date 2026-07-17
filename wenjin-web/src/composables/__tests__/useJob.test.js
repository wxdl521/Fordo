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

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function flushMicrotasks() {
  await Promise.resolve()
  await Promise.resolve()
}

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

    const pendingDispatch = deferred()
    const dispatch = vi.fn(() => pendingDispatch.promise)

    const p1 = start(dispatch, { requestKey: 'same-key', courseId: 1 })
    const p2 = start(dispatch, { requestKey: 'same-key', courseId: 1 })

    expect(dispatch).toHaveBeenCalledTimes(1)
    expect(dispatch.mock.calls[0][0]).toEqual({ 'Idempotency-Key': 'same-key' })

    pendingDispatch.resolve({ id: 3, status: 0, progress: 0, requestKey: 'same-key' })
    await Promise.all([p1, p2])
    dispose()
  })

  it('coalesces an implicit-key double-click while dispatch is pending', async () => {
    const { start, dispose } = useJob({ pollIntervalMs: 1000 })
    const pendingDispatch = deferred()
    const dispatch = vi.fn(() => pendingDispatch.promise)

    const p1 = start(dispatch)
    const p2 = start(dispatch)

    expect(dispatch).toHaveBeenCalledTimes(1)
    const requestKey = dispatch.mock.calls[0][0]['Idempotency-Key']
    expect(requestKey).toEqual(expect.any(String))
    expect(requestKey).not.toBe('')

    pendingDispatch.resolve({ id: 4, status: 0, progress: 0, requestKey })
    await Promise.all([p1, p2])
    dispose()
  })

  it('generates a fresh implicit key for the next action after terminal completion', async () => {
    const { start, dispose } = useJob()
    let id = 0
    const dispatch = vi.fn(async (headers) => ({
      id: ++id,
      status: 2,
      progress: 100,
      requestKey: headers['Idempotency-Key']
    }))

    await start(dispatch)
    await start(dispatch)

    expect(dispatch).toHaveBeenCalledTimes(2)
    const firstKey = dispatch.mock.calls[0][0]['Idempotency-Key']
    const secondKey = dispatch.mock.calls[1][0]['Idempotency-Key']
    expect(firstKey).not.toBe(secondKey)
    dispose()
  })

  it('cancels a dispatch-pending action when its new job id arrives, not the retained old job', async () => {
    const { start, cancel, job, loading, dispose } = useJob()

    await start(async () => ({ id: 20, status: 2, progress: 100 }))
    expect(job.value.id).toBe(20)

    const pendingDispatch = deferred()
    const starting = start(() => pendingDispatch.promise)
    cancelJob.mockResolvedValue({ id: 21, status: 4, progress: 0, errorCode: 'CANCELLED' })

    const cancelling = cancel()
    expect(cancelJob).not.toHaveBeenCalled()

    pendingDispatch.resolve({ id: 21, status: 1, progress: 0 })
    await expect(cancelling).resolves.toMatchObject({ id: 21, status: 4 })
    await expect(starting).resolves.toMatchObject({ id: 21, status: 4 })

    expect(cancelJob).toHaveBeenCalledTimes(1)
    expect(cancelJob).toHaveBeenCalledWith(21)
    expect(job.value).toMatchObject({ id: 21, status: 4 })
    expect(loading.value).toBe(false)
    dispose()
  })

  it('ends pending cancellation cleanly when dispatch fails before returning a job id', async () => {
    const { start, cancel, loading, error, dispose } = useJob()
    const pendingDispatch = deferred()

    const starting = start(() => pendingDispatch.promise)
    const cancelling = cancel()
    pendingDispatch.reject(new Error('dispatch unavailable'))

    await expect(starting).rejects.toThrow('dispatch unavailable')
    await expect(cancelling).rejects.toThrow('dispatch unavailable')
    expect(cancelJob).not.toHaveBeenCalled()
    expect(loading.value).toBe(false)
    expect(error.value).toBe('dispatch unavailable')
    dispose()
  })

  it('fails pending cancellation cleanly when dispatch returns a non-terminal job without an id', async () => {
    const { start, cancel, loading, error, dispose } = useJob()
    const pendingDispatch = deferred()

    const starting = start(() => pendingDispatch.promise)
    const cancelling = cancel()
    pendingDispatch.resolve({ status: 1, progress: 0 })

    await expect(starting).rejects.toThrow('未返回 ID')
    await expect(cancelling).rejects.toThrow('未返回 ID')
    expect(cancelJob).not.toHaveBeenCalled()
    expect(loading.value).toBe(false)
    expect(error.value).toContain('未返回 ID')
    dispose()
  })

  it('treats an explicit different requestKey as a new action', async () => {
    const { start, dispose } = useJob()
    const pendingDispatch = deferred()
    const dispatch = vi.fn(() => pendingDispatch.promise)

    const first = start(dispatch, { requestKey: 'first' })
    const second = start(dispatch, { requestKey: 'second' })

    expect(dispatch).toHaveBeenCalledTimes(2)
    expect(dispatch.mock.calls.map(([headers]) => headers['Idempotency-Key'])).toEqual([
      'first',
      'second'
    ])

    pendingDispatch.resolve({ id: 6, status: 2, progress: 100 })
    await Promise.all([first, second])
    dispose()
  })

  it('does not overlap polls while the previous getJob request is pending', async () => {
    const { start, dispose } = useJob({ pollIntervalMs: 1000 })
    const pendingPoll = deferred()
    getJob.mockReturnValueOnce(pendingPoll.promise)

    await start(async () => ({ id: 11, status: 0, progress: 0 }))
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(5000)

    expect(getJob).toHaveBeenCalledTimes(1)

    pendingPoll.resolve({ id: 11, status: 2, progress: 100 })
    await flushMicrotasks()
    dispose()
  })

  it('resumes polling the same action after a transient poll error without redispatching', async () => {
    const onError = vi.fn()
    const { start, job, loading, error, dispose } = useJob({
      onError,
      pollIntervalMs: 1000
    })
    const dispatch = vi.fn(async () => ({ id: 17, status: 1, progress: 10 }))
    getJob
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce({ id: 17, status: 2, progress: 100 })

    await start(dispatch)
    await vi.advanceTimersByTimeAsync(1000)

    expect(dispatch).toHaveBeenCalledTimes(1)
    expect(loading.value).toBe(false)
    expect(error.value).toBe('temporary network failure')
    expect(onError).toHaveBeenCalledTimes(1)

    await start(dispatch)
    expect(dispatch).toHaveBeenCalledTimes(1)
    expect(loading.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)
    expect(getJob).toHaveBeenCalledTimes(2)
    expect(job.value).toMatchObject({ id: 17, status: 2, progress: 100 })
    expect(loading.value).toBe(false)
    dispose()
  })

  it('does not let an old poll revive a job after cancel reaches terminal state', async () => {
    const onSuccess = vi.fn()
    const onError = vi.fn()
    const { start, cancel, job, progress, loading, error, dispose } = useJob({
      onSuccess,
      onError,
      pollIntervalMs: 1000
    })
    const pendingPoll = deferred()
    getJob.mockReturnValueOnce(pendingPoll.promise)
    cancelJob.mockResolvedValue({ id: 12, status: 4, progress: 70, errorCode: 'CANCELLED' })

    await start(async () => ({ id: 12, status: 1, progress: 20 }))
    await vi.advanceTimersByTimeAsync(1000)
    await cancel()

    expect(job.value.status).toBe(4)
    expect(loading.value).toBe(false)

    pendingPoll.resolve({ id: 12, status: 1, progress: 80 })
    await flushMicrotasks()

    expect(job.value.status).toBe(4)
    expect(progress.value).toBe(70)
    expect(loading.value).toBe(false)
    expect(error.value).toBe('')
    expect(onSuccess).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    dispose()
  })

  it('continues serial observation when cancel returns a non-terminal job', async () => {
    const { start, cancel, job, loading, dispose } = useJob({ pollIntervalMs: 1000 })
    cancelJob.mockResolvedValue({ id: 13, status: 1, progress: 30 })
    getJob.mockResolvedValue({ id: 13, status: 4, progress: 30, errorCode: 'CANCELLED' })

    await start(async () => ({ id: 13, status: 1, progress: 10 }))
    await cancel()
    expect(job.value.status).toBe(1)
    expect(loading.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)

    expect(getJob).toHaveBeenCalledTimes(1)
    expect(job.value.status).toBe(4)
    expect(loading.value).toBe(false)
    dispose()
  })

  it('keeps clear state when an already-issued poll resolves later', async () => {
    const onSuccess = vi.fn()
    const onError = vi.fn()
    const { start, clear, job, progress, loading, error, dispose } = useJob({
      onSuccess,
      onError,
      pollIntervalMs: 1000
    })
    const pendingPoll = deferred()
    getJob.mockReturnValueOnce(pendingPoll.promise)

    await start(async () => ({ id: 14, status: 1, progress: 10 }))
    await vi.advanceTimersByTimeAsync(1000)
    clear()
    pendingPoll.resolve({ id: 14, status: 2, progress: 100 })
    await flushMicrotasks()

    expect(job.value).toBeNull()
    expect(progress.value).toBe(0)
    expect(loading.value).toBe(false)
    expect(error.value).toBe('')
    expect(onSuccess).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    dispose()
  })

  it('does not write a stale cancel error after clear invalidates it', async () => {
    const onError = vi.fn()
    const { start, cancel, clear, error, dispose } = useJob({ onError })
    const pendingCancel = deferred()
    cancelJob.mockReturnValueOnce(pendingCancel.promise)

    await start(async () => ({ id: 15, status: 1, progress: 0 }))
    const cancelling = cancel()
    clear()
    pendingCancel.reject(new Error('late cancel failure'))
    await expect(cancelling).rejects.toThrow('late cancel failure')

    expect(error.value).toBe('')
    expect(onError).not.toHaveBeenCalled()
    dispose()
  })

  it('keeps course-switch state when an already-issued poll resolves later', async () => {
    const courseId = ref(1)
    const onSuccess = vi.fn()
    const { start, job, progress, loading, dispose } = useJob({
      courseId,
      onSuccess,
      pollIntervalMs: 1000
    })
    const pendingPoll = deferred()
    getJob.mockReturnValueOnce(pendingPoll.promise)

    await start(async () => ({ id: 16, status: 1, progress: 10 }), { courseId: 1 })
    await vi.advanceTimersByTimeAsync(1000)
    courseId.value = 2
    await nextTick()
    const switchedSnapshot = job.value

    pendingPoll.resolve({ id: 16, status: 2, progress: 100 })
    await flushMicrotasks()

    expect(job.value).toBe(switchedSnapshot)
    expect(progress.value).toBe(10)
    expect(loading.value).toBe(false)
    expect(onSuccess).not.toHaveBeenCalled()
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

  it('keeps a newer terminal action when an older in-flight poll resolves later', async () => {
    const onSuccess = vi.fn()
    const { start, job, dispose } = useJob({ onSuccess, pollIntervalMs: 1000 })
    const oldPoll = deferred()
    getJob.mockReturnValueOnce(oldPoll.promise)

    await start(async () => ({ id: 31, status: 1, progress: 10 }), { requestKey: 'old' })
    await vi.advanceTimersByTimeAsync(1000)

    await start(
      async () => ({ id: 32, status: 2, progress: 100, result: { course: 'new' } }),
      { requestKey: 'new' }
    )
    oldPoll.resolve({ id: 31, status: 1, progress: 90 })
    await flushMicrotasks()

    expect(job.value).toMatchObject({ id: 32, status: 2, progress: 100 })
    expect(onSuccess).toHaveBeenCalledTimes(1)
    expect(onSuccess.mock.calls[0][0].id).toBe(32)
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

  it('dispose ignores a poll response that was already in flight', async () => {
    const onSuccess = vi.fn()
    const { start, dispose, job, progress } = useJob({ onSuccess, pollIntervalMs: 1000 })
    const pendingPoll = deferred()
    getJob.mockReturnValueOnce(pendingPoll.promise)

    await start(async () => ({ id: 33, status: 1, progress: 10 }))
    await vi.advanceTimersByTimeAsync(1000)
    dispose()
    pendingPoll.resolve({ id: 33, status: 2, progress: 100 })
    await flushMicrotasks()

    expect(job.value).toMatchObject({ id: 33, status: 1, progress: 10 })
    expect(progress.value).toBe(10)
    expect(onSuccess).not.toHaveBeenCalled()
  })
})
