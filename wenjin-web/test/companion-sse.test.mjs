import test from 'node:test'
import assert from 'node:assert/strict'

import { dispatchChatStreamEvent } from '../src/api/companion.js'

test('dispatchChatStreamEvent routes companion SSE payloads to callbacks', () => {
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

  assert.equal(dispatchChatStreamEvent('meta', { conversationId: 12 }, callbacks).done, false)
  assert.equal(dispatchChatStreamEvent('token', { t: 'hello' }, callbacks).done, false)
  assert.equal(dispatchChatStreamEvent('tool_start', { name: 'query_mastery', label: '查询掌握度' }, callbacks).done, false)
  assert.equal(dispatchChatStreamEvent('tool_result', { name: 'query_mastery', ok: true }, callbacks).done, false)
  assert.equal(dispatchChatStreamEvent('practice_card', { sessionId: 88 }, callbacks).done, false)
  assert.equal(dispatchChatStreamEvent('error', { message: 'boom' }, callbacks).done, false)
  assert.equal(dispatchChatStreamEvent('done', {}, callbacks).done, true)

  assert.deepEqual(calls, [
    ['meta', { conversationId: 12 }],
    ['token', 'hello'],
    ['tool_start', { name: 'query_mastery', label: '查询掌握度' }],
    ['tool_result', { name: 'query_mastery', ok: true }],
    ['practice_card', { sessionId: 88 }],
    ['error', 'boom'],
    ['done']
  ])
})

test('dispatchChatStreamEvent ignores unknown events and missing callbacks', () => {
  assert.deepEqual(dispatchChatStreamEvent('unknown', { ok: true }, {}), { handled: false, done: false })
  assert.equal(dispatchChatStreamEvent('done', {}, {}).done, true)
})
