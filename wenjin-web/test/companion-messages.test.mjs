import test from 'node:test'
import assert from 'node:assert/strict'

import {
  applyToolResult,
  buildPracticeAnswerList,
  createPracticeMessage,
  createToolMessage,
  normalizeConversationMessage,
  restoreTypedMessagesFromSnapshot
} from '../src/utils/companionMessages.js'

test('applyToolResult completes the earliest pending matching tool first', () => {
  const messages = [
    createToolMessage({ name: 'query_mastery', label: 'Query Mastery' }),
    createToolMessage({ name: 'query_mastery', label: 'Query Mastery' }),
    createToolMessage({ name: 'get_node_detail', label: 'Get Node Detail' })
  ]

  const updated = applyToolResult(messages, { name: 'query_mastery', ok: true })

  assert.equal(updated[0].ok, true)
  assert.equal(updated[1].ok, null)
  assert.equal(updated[2].ok, null)
})

test('createToolMessage uses the Chinese tool fallback label', () => {
  assert.equal(createToolMessage({}).label, '工具')
})

test('buildPracticeAnswerList stays compatible with NodePractice answer semantics', () => {
  const message = createPracticeMessage({
    sessionId: 88,
    node: { nodeId: 7, nodeCode: 'KT12', name: 'Arrays' },
    questions: [
      {
        questionId: 11,
        chapter: 'Ch1',
        stem: 'Single',
        options: [{ key: 'A', text: 'a' }, { key: 'B', text: 'b' }],
        type: 1
      },
      {
        questionId: 12,
        chapter: 'Ch1',
        stem: 'Multi',
        options: [{ key: 'A', text: 'a' }, { key: 'B', text: 'b' }, { key: 'C', text: 'c' }],
        type: 2
      },
      {
        questionId: 13,
        chapter: 'Ch1',
        stem: 'Short',
        options: [],
        type: 4
      }
    ]
  })

  message.answers.single[0] = 1
  message.answers.multi[1] = [2, 0]
  message.answers.text[2] = 'loop invariant'

  assert.deepEqual(buildPracticeAnswerList(message), [
    { questionId: 11, studentAnswer: 'B' },
    { questionId: 12, studentAnswer: 'A,C' },
    { questionId: 13, studentAnswer: 'loop invariant' }
  ])
})

test('restoreTypedMessagesFromSnapshot uses saved typed messages only when transcript still matches', () => {
  const serverMessages = [
    normalizeConversationMessage({ role: 'user', content: 'Need help with arrays' }),
    normalizeConversationMessage({ role: 'ai', content: 'Try one short practice set.' })
  ]
  const practiceMessage = createPracticeMessage({
    sessionId: 88,
    node: { nodeId: 7, nodeCode: 'KT12', name: 'Arrays' },
    questions: []
  })
  practiceMessage.phase = 'result'
  practiceMessage.submitResult = { masteryBefore: 40, masteryAfter: 55 }

  const snapshot = {
    transcript: [
      { role: 'user', content: 'Need help with arrays' },
      { role: 'assistant', content: 'Try one short practice set.' }
    ],
    messages: [...serverMessages, practiceMessage]
  }

  const restored = restoreTypedMessagesFromSnapshot(serverMessages, snapshot)
  assert.equal(restored.messages.length, 3)
  assert.equal(restored.usedSnapshot, true)
  assert.equal(restored.messages[2].role, 'practice')

  const stale = restoreTypedMessagesFromSnapshot(
    [
      ...serverMessages.slice(0, 1),
      normalizeConversationMessage({ role: 'ai', content: 'Different assistant reply' })
    ],
    snapshot
  )

  assert.equal(stale.usedSnapshot, false)
  assert.deepEqual(stale.messages, [
    { role: 'user', content: 'Need help with arrays' },
    { role: 'assistant', content: 'Different assistant reply' }
  ])
})
