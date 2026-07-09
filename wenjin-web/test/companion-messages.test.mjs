import test from 'node:test'
import assert from 'node:assert/strict'

import {
  appendAssistantToken,
  applyToolResult,
  buildPracticeAnswerList,
  createPracticeMessage,
  createToolMessage,
  normalizeConversationMessage,
  restoreTypedMessagesFromSnapshot
} from '../src/utils/companionMessages.js'
import {
  buildCompanionChatPayload,
  readCompanionNodeCode
} from '../src/utils/companionContext.js'

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

test('readCompanionNodeCode normalizes route query values', () => {
  assert.equal(readCompanionNodeCode('KT12'), 'KT12')
  assert.equal(readCompanionNodeCode(['KT12', 'KT99']), 'KT12')
  assert.equal(readCompanionNodeCode('   '), null)
  assert.equal(readCompanionNodeCode(undefined), null)
})

test('buildCompanionChatPayload sends nodeCode instead of contextNodeCode', () => {
  assert.deepEqual(
    buildCompanionChatPayload({
      studentId: 2,
      courseId: 5,
      conversationId: 99,
      nodeCode: 'KT12',
      message: 'help me practice'
    }),
    {
      studentId: 2,
      courseId: 5,
      conversationId: 99,
      nodeCode: 'KT12',
      message: 'help me practice'
    }
  )

  assert.deepEqual(
    buildCompanionChatPayload({
      studentId: 2,
      courseId: 5,
      nodeCode: null,
      message: 'help me practice'
    }),
    {
      studentId: 2,
      courseId: 5,
      message: 'help me practice'
    }
  )
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

test('assistant token appears after tools', () => {
  let msgs = [{ role: 'user', content: '出两道题' }]
  msgs = [...msgs, { role: 'tool', name: 'start_practice', label: '正在生成节点练习…', ok: true }]
  msgs = appendAssistantToken(msgs, '练习已生成')
  assert.equal(msgs[0].role, 'user')
  assert.equal(msgs[1].role, 'tool')
  assert.equal(msgs[2].role, 'assistant')
  assert.equal(msgs[2].content, '练习已生成')
})

test('appendAssistantToken appends to current-turn assistant after tools and practice', () => {
  let msgs = [
    { role: 'user', content: 'prev' },
    { role: 'assistant', content: 'old reply' },
    { role: 'user', content: '出两道题' },
    { role: 'tool', name: 'start_practice', label: '正在生成节点练习…', ok: true },
    { role: 'practice', card: { sessionId: 1, questions: [] } }
  ]
  msgs = appendAssistantToken(msgs, '练习')
  msgs = appendAssistantToken(msgs, '已生成')
  assert.equal(msgs.map((m) => m.role).join(','), 'user,assistant,user,tool,practice,assistant')
  assert.equal(msgs[1].content, 'old reply')
  assert.equal(msgs[5].content, '练习已生成')
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
