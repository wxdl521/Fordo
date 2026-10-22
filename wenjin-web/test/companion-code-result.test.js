import { describe, expect, it, vi } from 'vitest'
import { dispatchChatStreamEvent } from '../src/api/companion.js'
import {
  appendAssistantToken, createCodeResultMessage, createPracticeMessage, buildPracticeAnswerList,
  createTypedMessagesSnapshot, restoreTypedMessagesFromSnapshot
} from '../src/utils/companionMessages.js'

describe('code_result support', () => {
  it('dispatches code_result to onCodeResult', () => {
    const cb = { onCodeResult: vi.fn() }
    const r = dispatchChatStreamEvent('code_result', { mode: 'RUN' }, cb)
    expect(cb.onCodeResult).toHaveBeenCalledWith({ mode: 'RUN' })
    expect(r).toEqual({ handled: true, done: false })
  })

  it('normalizes the event and survives a typed snapshot round trip', () => {
    const msg = createCodeResultMessage({ mode: 'JUDGE', verdict: 'WRONG_ANSWER', passedCases: 1, totalCases: 3, cases: [{ index: 1 }] })
    expect(msg.role).toBe('code_result')
    expect(msg.result.stderr).toBe('')
    const messages = [{ role: 'user', content: 'hi' }, { role: 'tool', name: 'run_code_check', ok: true }, msg, { role: 'assistant', content: 'ok' }]
    const snap = createTypedMessagesSnapshot(messages)
    const server = [{ role: 'user', content: 'hi' }, { role: 'assistant', content: 'ok' }]
    const restored = restoreTypedMessagesFromSnapshot(server, snap)
    expect(restored.usedSnapshot).toBe(true)
    expect(restored.messages[2].result.verdict).toBe('WRONG_ANSWER')
  })

  it('a token after a code_result card starts a new assistant bubble', () => {
    const msgs = appendAssistantToken([createCodeResultMessage({ mode: 'RUN' })], 'x')
    expect(msgs.at(-1)).toEqual({ role: 'assistant', content: 'x' })
  })

  it('practice card carries starterCode/sampleCases and answers default to starter code', () => {
    const m = createPracticeMessage({
      sessionId: 1,
      questions: [{ questionId: 3, chapter: 'c', stem: 's', type: 5, starterCode: 'SC', sampleCases: [{ seq: 1, stdin: 'i', expectedStdout: 'o' }] }]
    })
    expect(m.card.questions[0].sampleCases).toHaveLength(1)
    expect(buildPracticeAnswerList(m)).toEqual([{ questionId: 3, studentAnswer: 'SC' }])
    m.answers.text = { 0: 'mine' }
    expect(buildPracticeAnswerList(m)).toEqual([{ questionId: 3, studentAnswer: 'mine' }])
  })
})
