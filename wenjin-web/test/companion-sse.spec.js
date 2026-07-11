import { describe, it, expect } from 'vitest'
import { dispatchChatStreamEvent, isAbortError } from '../src/api/companion.js'

describe('dispatchChatStreamEvent', () => {
  it('routes companion SSE payloads to callbacks', () => {
    const calls = []
    const callbacks = {
      onMeta: (event) => calls.push(['meta', event]),
      onToken: (token) => calls.push(['token', token]),
      onToolStart: (event) => calls.push(['tool_start', event]),
      onToolResult: (event) => calls.push(['tool_result', event]),
      onPracticeCard: (event) => calls.push(['practice_card', event]),
      onDone: () => calls.push(['done']),
      onError: (message) => calls.push(['error', message])
    }

    expect(dispatchChatStreamEvent('meta', { conversationId: 12 }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('token', { t: 'hello' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('tool_start', { name: 'query_mastery', label: '查询掌握度' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('tool_result', { name: 'query_mastery', ok: true }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('practice_card', { sessionId: 88 }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('error', { message: 'boom' }, callbacks).done).toBe(false)
    expect(dispatchChatStreamEvent('done', {}, callbacks).done).toBe(true)

    expect(calls).toEqual([
      ['meta', { conversationId: 12 }],
      ['token', 'hello'],
      ['tool_start', { name: 'query_mastery', label: '查询掌握度' }],
      ['tool_result', { name: 'query_mastery', ok: true }],
      ['practice_card', { sessionId: 88 }],
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
