import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * Contract checks for the M4 Playwright acceptance script.
 * These are structural (source) assertions because the full browser flow
 * requires a live backend; behavior of helpers is covered elsewhere.
 */
const __dirname = dirname(fileURLToPath(import.meta.url))
const script = readFileSync(resolve(__dirname, '../e2e/m4-companion-tools.mjs'), 'utf8')

describe('M4 e2e script contract', () => {
  it('covers every final acceptance lane', () => {
    const requiredMarkers = [
      '诱导模型连续调用超过预算',
      'TOOL_BUDGET_EXCEEDED',
      '图谱外节点注入',
      'M4_OUT_OF_GRAPH_NODE',
      'WJ_EXPECT_TOOLS_DISABLED',
      'WJ_TOOLS_DISABLED_ASSERT',
      'WJ_TOOL_BUDGET_MAX',
      'readAuditSlice',
      'waitAuditLog',
      'subarray(offset)',
      'budgetBlockedIndex',
      'budgetTools[budgetBlockedIndex]?.ok === false',
      'companion_tool'
    ]

    for (const marker of requiredMarkers) {
      expect(script.includes(marker), `missing e2e acceptance marker: ${marker}`).toBe(true)
    }
  })

  it('waits for practice stream idle before sending budget prompt', () => {
    const answerIndex = script.indexOf('const answer = await answerLatestPracticeCard()')
    const bodyTextIndex = script.indexOf("const bodyText = await page.locator('body').innerText()", answerIndex)
    const idleIndex = script.indexOf('practice-submit-idle', answerIndex)
    const budgetIndex = script.indexOf('const budgetRun = await sendAndWaitDone(', answerIndex)

    expect(answerIndex).toBeGreaterThan(-1)
    expect(bodyTextIndex).toBeGreaterThan(answerIndex)
    expect(idleIndex).toBeGreaterThan(bodyTextIndex)
    expect(budgetIndex).toBeGreaterThan(idleIndex)
  })

  it('asserts budget using configured tool budget', () => {
    expect(script).toMatch(/const toolBudgetLimit = readPositiveInt\(process\.env\.WJ_TOOL_BUDGET_MAX, 3\)/)
    expect(script).toMatch(/const budgetBlockedIndex = toolBudgetLimit/)
    expect(script).toMatch(/budgetTools\.slice\(0, toolBudgetLimit\)\.every/)
  })
})
