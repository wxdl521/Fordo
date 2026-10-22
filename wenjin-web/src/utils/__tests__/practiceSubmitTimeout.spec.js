import { describe, expect, it } from 'vitest'
import {
  CODE_JUDGE_BUDGET_MS,
  SUBMIT_BASE_TIMEOUT_MS,
  SUBMIT_MAX_TIMEOUT_MS,
  codeAnswerCount,
  practiceSubmitTimeoutMs
} from '../codeResult.js'

describe('practice submit timeout (CODE answers are judged synchronously)', () => {
  it('keeps the default 15s when no CODE answer is submitted', () => {
    expect(SUBMIT_BASE_TIMEOUT_MS).toBe(15000)
    expect(practiceSubmitTimeoutMs(0)).toBe(15000)
    expect(practiceSubmitTimeoutMs(undefined)).toBe(15000)
    expect(practiceSubmitTimeoutMs(-3)).toBe(15000)
  })

  it('adds one judge budget per CODE answer on top of the base margin', () => {
    expect(CODE_JUDGE_BUDGET_MS).toBe(60000)
    expect(practiceSubmitTimeoutMs(1)).toBe(15000 + 60000)
    expect(practiceSubmitTimeoutMs(2)).toBe(15000 + 2 * 60000)
  })

  it('is capped', () => {
    expect(SUBMIT_MAX_TIMEOUT_MS).toBe(180000)
    expect(practiceSubmitTimeoutMs(3)).toBe(180000)
    expect(practiceSubmitTimeoutMs(50)).toBe(180000)
  })

  it('counts only answers whose question is a CODE question', () => {
    const questions = [
      { questionId: 1, type: 1 },
      { questionId: 2, type: 5 },
      { questionId: 3, type: 'CODE' },
      { questionId: 4, type: 4 }
    ]
    const answers = [
      { questionId: 1, studentAnswer: 'A' },
      { questionId: 2, studentAnswer: 'class Main {}' },
      { questionId: 4, studentAnswer: 'text' }
    ]
    expect(codeAnswerCount(questions, answers)).toBe(1)
    expect(codeAnswerCount(questions, [...answers, { questionId: 3, studentAnswer: 'x' }])).toBe(2)
    expect(codeAnswerCount(null, answers)).toBe(0)
    expect(codeAnswerCount(questions, null)).toBe(0)
  })
})
