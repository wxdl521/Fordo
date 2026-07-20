import { describe, it, expect, afterEach, vi } from 'vitest'
import { chatStream, dispatchChatStreamEvent, isAbortError } from '../src/api/companion.js'

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('dispatchChatStreamEvent', () => {
  it('routes companion SSE payloads to callbacks', () => {
    const calls = []
    const callbacks = {
      onMeta: (event) => calls.push(['meta', event]),
      onToken: (token) => calls.push(['token', token]),
      onToolStart: (event) => calls.push(['tool_start', event]),
      onToolResult: (event) => calls.push(['tool_result', event]),
      onPracticeCard: (event) => calls.push(['practice_card', event]),
      onProbeCard: (event) => calls.push(['probe_card', event]),
      onDone: () => calls.push(['done']),
      onError: (message) => calls.push(['error', message])
    }

    expect(dispatchChatStreamEvent('meta', { conversationId: 12 }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('token', { t: 'hello' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('tool_start', { name: 'query_mastery', label: '查询掌握度' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('tool_result', { name: 'query_mastery', ok: true }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('practice_card', { sessionId: 88 }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('probe_card', { sessionId: 'p-1' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('error', { message: 'boom' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('done', {}, callbacks).done).toBe(true)

    expect(calls).toEqual([
      ['meta', { conversationId: 12 }],
      ['token', 'hello'],
      ['tool_start', { name: 'query_mastery', label: '查询掌握度' }],
      ['tool_result', { name: 'query_mastery', ok: true }],
      ['practice_card', { sessionId: 88 }],
      ['probe_card', { sessionId: 'p-1' }],
      ['error', 'boom'],
      ['done']
    ])
  })

  it('ignores unknown events and missing callbacks', () => {
    expect(dispatchChatStreamEvent('unknown', { ok: true }, {})).toEqual({ handled: false, done: false })
    expect(dispatchChatStreamEvent('done', {}, {}).done).toBe(true)
  })
})

describe('isAbortError', () => {
  it('recognizes abort / cancel shapes', () => {
    expect(isAbortError({ name: 'AbortError' })).toBe(true)
    expect(isAbortError({ name: 'CanceledError' })).toBe(true)
    expect(isAbortError({ code: 'ERR_CANCELED' })).toBe(true)
    expect(isAbortError({ message: 'canceled' })).toBe(true)
    expect(isAbortError({ message: 'network down' })).toBe(false)
    expect(isAbortError(null)).toBe(false)
  })
})

describe('chatStream authentication failures', () => {
  it('clears the session when the captured token receives a 401', async () => {
    localStorage.setItem('wj_token', 'token-a')
    localStorage.setItem('wj_user', JSON.stringify({ id: 1 }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }))
    const onError = vi.fn()

    await expect(
      chatStream({ courseId: 7, message: 'hello' }, { onError })
    ).rejects.toThrow('HTTP 401')

    expect(onError).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('wj_token')).toBeNull()
    expect(localStorage.getItem('wj_user')).toBeNull()
  })

  it('keeps the captured Authorization token but preserves a newer session on delayed 401', async () => {
    localStorage.setItem('wj_token', 'token-a')
    localStorage.setItem('wj_user', JSON.stringify({ id: 1 }))
    let resolveFetch
    const fetchMock = vi.fn(() => new Promise((resolve) => {
      resolveFetch = resolve
    }))
    vi.stubGlobal('fetch', fetchMock)
    const onError = vi.fn()

    const stream = chatStream(
      { courseId: 7, message: 'hello' },
      { onError }
    )

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token-a')

    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))
    resolveFetch({ ok: false, status: 401 })

    await expect(stream).rejects.toThrow('HTTP 401')
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith('HTTP 401')
    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(JSON.parse(localStorage.getItem('wj_user'))).toEqual({ id: 2 })
  })
})
