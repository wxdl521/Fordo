import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const script = readFileSync(resolve(__dirname, '../e2e/m4-companion-tools.mjs'), 'utf8')

test('M4 e2e script covers every final acceptance lane', () => {
  const requiredMarkers = [
    '诱导模型连续调用超过预算',
    'tool_budget_exceeded',
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
    assert.ok(script.includes(marker), `missing e2e acceptance marker: ${marker}`)
  }
})

test('M4 e2e waits for practice stream idle before sending budget prompt', () => {
  const answerIndex = script.indexOf('const answer = await answerLatestPracticeCard()')
  const bodyTextIndex = script.indexOf("const bodyText = await page.locator('body').innerText()", answerIndex)
  const idleIndex = script.indexOf('practice-submit-idle', answerIndex)
  const budgetIndex = script.indexOf('const budgetRun = await sendAndWaitDone(', answerIndex)

  assert.ok(answerIndex > -1, 'missing practice answer step')
  assert.ok(bodyTextIndex > answerIndex, 'missing practice result text check')
  assert.ok(idleIndex > bodyTextIndex, 'practice idle wait must run after result text check')
  assert.ok(budgetIndex > idleIndex, 'budget prompt must run after practice idle wait')
})

test('M4 e2e budget assertion follows configured tool budget', () => {
  assert.match(script, /const toolBudgetLimit = readPositiveInt\(process\.env\.WJ_TOOL_BUDGET_MAX, 3\)/)
  assert.match(script, /const budgetBlockedIndex = toolBudgetLimit/)
  assert.match(script, /budgetTools\.slice\(0, toolBudgetLimit\)\.every/)
})
