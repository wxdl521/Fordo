import { describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { useAutoReview } from '../useAutoReview.js'

function setup({ review = '42', courseId = 1, typing = false, loading = null } = {}) {
  const state = {
    review: ref(review),
    courseId: ref(courseId),
    typing: ref(typing),
    loading: ref(loading)
  }
  const send = vi.fn()
  const clearQuery = vi.fn(() => { state.review.value = undefined })
  useAutoReview({
    reviewQuery: () => state.review.value,
    ready: () => !!state.courseId.value && !state.typing.value && state.loading.value === null,
    clearQuery,
    send
  })
  return { state, send, clearQuery }
}

describe('useAutoReview', () => {
  it('sends once immediately when idle and clears the query param', async () => {
    const { send, clearQuery } = setup()
    await nextTick()
    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith(42)
    expect(clearQuery).toHaveBeenCalledTimes(1)
  })

  it('defers while a reply is streaming instead of dropping the request', async () => {
    const { state, send } = setup({ typing: true })
    await nextTick()
    expect(send).not.toHaveBeenCalled()
    state.typing.value = false
    await nextTick()
    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith(42)
  })

  it('waits for courseId and conversation loading, then fires exactly once', async () => {
    const { state, send } = setup({ courseId: null, loading: 7 })
    await nextTick()
    state.courseId.value = 3
    await nextTick()
    expect(send).not.toHaveBeenCalled()
    state.loading.value = null
    await nextTick()
    state.typing.value = true
    await nextTick()
    state.typing.value = false
    await nextTick()
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('ignores invalid ids and never re-sends the same id', async () => {
    const { state, send } = setup({ review: 'abc' })
    await nextTick()
    expect(send).not.toHaveBeenCalled()
    state.review.value = '9'
    await nextTick()
    state.review.value = '9'
    await nextTick()
    state.review.value = ['9']
    await nextTick()
    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith(9)
  })
})
