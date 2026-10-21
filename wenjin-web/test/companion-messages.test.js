import { describe, it, expect } from 'vitest'
import {
  appendAssistantToken,
  applyToolResult,
  buildPracticeAnswerList,
  createPracticeMessage,
  createToolMessage,
  normalizeConversationMessage,
  parseCitationSegments,
  restoreTypedMessagesFromSnapshot
} from '../src/utils/companionMessages.js'
import {
  buildCompanionChatPayload,
  readCompanionNodeCode
} from '../src/utils/companionContext.js'

describe('companionMessages', () => {
  it('completes the earliest pending matching tool first', () => {
    const messages = [
      createToolMessage({ name: 'query_mastery', label: 'Query Mastery' }),
      createToolMessage({ name: 'query_mastery', label: 'Query Mastery' }),
      createToolMessage({ name: 'get_node_detail', label: 'Get Node Detail' })
    ]
    const updated = applyToolResult(messages, { name: 'query_mastery', ok: true })
    expect(updated[0].ok).toBe(true)
    expect(updated[1].ok).toBeNull()
    expect(updated[2].ok).toBeNull()
  })

  it('uses the Chinese tool fallback label', () => {
    expect(createToolMessage({}).label).toBe('工具')
  })

  it('normalizes route query node codes', () => {
    expect(readCompanionNodeCode('KT12')).toBe('KT12')
    expect(readCompanionNodeCode(['KT12', 'KT99'])).toBe('KT12')
    expect(readCompanionNodeCode('   ')).toBeNull()
    expect(readCompanionNodeCode(undefined)).toBeNull()
  })

  it('omits studentId and sends nodeCode in chat payload', () => {
    expect(
      buildCompanionChatPayload({
        courseId: 5,
        conversationId: 99,
        nodeCode: 'KT12',
        message: 'help me practice'
      })
    ).toEqual({
      courseId: 5,
      conversationId: 99,
      nodeCode: 'KT12',
      message: 'help me practice'
    })

    expect(
      buildCompanionChatPayload({
        courseId: 5,
        nodeCode: null,
        message: 'help me practice'
      })
    ).toEqual({
      courseId: 5,
      message: 'help me practice'
    })
  })

  it('builds practice answers compatible with NodePractice semantics', () => {
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

    expect(buildPracticeAnswerList(message)).toEqual([
      { questionId: 11, studentAnswer: 'B' },
      { questionId: 12, studentAnswer: 'A,C' },
      { questionId: 13, studentAnswer: 'loop invariant' }
    ])
  })

  it('appends assistant tokens after tools', () => {
    let msgs = [{ role: 'user', content: '出两道题' }]
    msgs = [...msgs, { role: 'tool', name: 'start_practice', label: '正在生成节点练习…', ok: true }]
    msgs = appendAssistantToken(msgs, '练习已生成')
    expect(msgs[0].role).toBe('user')
    expect(msgs[1].role).toBe('tool')
    expect(msgs[2].role).toBe('assistant')
    expect(msgs[2].content).toBe('练习已生成')
  })

  it('appends to current-turn assistant after tools and practice', () => {
    let msgs = [
      { role: 'user', content: 'prev' },
      { role: 'assistant', content: 'old reply' },
      { role: 'user', content: '出两道题' },
      { role: 'tool', name: 'start_practice', label: '正在生成节点练习…', ok: true },
      { role: 'practice', card: { sessionId: 1, questions: [] } }
    ]
    msgs = appendAssistantToken(msgs, '练习')
    msgs = appendAssistantToken(msgs, '已生成')
    expect(msgs.map((m) => m.role).join(',')).toBe('user,assistant,user,tool,practice,assistant')
    expect(msgs[1].content).toBe('old reply')
    expect(msgs[5].content).toBe('练习已生成')
  })

  it('puts post-tool text in a new bubble after the tool capsule', () => {
    let msgs = [{ role: 'user', content: 'q' }]
    msgs = appendAssistantToken(msgs, '先')
    msgs = appendAssistantToken(msgs, '查一下')
    msgs = [...msgs, createToolMessage({ name: 'query_mastery', label: 'Q' })]
    msgs = applyToolResult(msgs, { name: 'query_mastery', ok: true })
    msgs = appendAssistantToken(msgs, '结果')
    msgs = appendAssistantToken(msgs, '如下')
    expect(msgs.map((m) => m.role).join(',')).toBe('user,assistant,tool,assistant')
    expect(msgs[1].content).toBe('先查一下')
    expect(msgs[3].content).toBe('结果如下')
  })

  it('puts text after a practice card in a new bubble', () => {
    let msgs = [{ role: 'user', content: 'q' }]
    msgs = appendAssistantToken(msgs, 'a')
    msgs.push({ role: 'practice', card: { sessionId: 1, questions: [] } })
    msgs = appendAssistantToken(msgs, 'b')
    expect(msgs.map((m) => m.role).join(',')).toBe('user,assistant,practice,assistant')
    expect(msgs[3].content).toBe('b')
  })

  it('streams many tokens into one bubble without mutating input', () => {
    const start = [{ role: 'user', content: 'q' }]
    let msgs = start
    for (let i = 0; i < 50; i++) msgs = appendAssistantToken(msgs, 'x')
    expect(msgs).toHaveLength(2)
    expect(msgs[1].content).toBe('x'.repeat(50))
    expect(start).toHaveLength(1)
  })

  it('pairs two parallel same-name tools in start order', () => {
    let msgs = [
      createToolMessage({ name: 'query_mastery' }),
      createToolMessage({ name: 'query_mastery' })
    ]
    msgs = applyToolResult(msgs, { name: 'query_mastery', ok: true })
    expect(msgs.map((m) => m.ok)).toEqual([true, null])
    msgs = applyToolResult(msgs, { name: 'query_mastery', ok: false })
    expect(msgs.map((m) => m.ok)).toEqual([true, false])
  })

  it('keeps citation parsing working per bubble', () => {
    expect(parseCitationSegments('见资料 #7。').map((s) => s.type)).toEqual(['text', 'citation', 'text'])
  })

  it('restores typed snapshot only when transcript still matches', () => {
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
    expect(restored.messages).toHaveLength(3)
    expect(restored.usedSnapshot).toBe(true)
    expect(restored.messages[2].role).toBe('practice')

    const stale = restoreTypedMessagesFromSnapshot(
      [
        ...serverMessages.slice(0, 1),
        normalizeConversationMessage({ role: 'ai', content: 'Different assistant reply' })
      ],
      snapshot
    )

    expect(stale.usedSnapshot).toBe(false)
    expect(stale.messages).toEqual([
      { role: 'user', content: 'Need help with arrays' },
      { role: 'assistant', content: 'Different assistant reply' }
    ])
  })
})
