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
    'readAuditSlice',
    'waitAuditLog',
    'subarray(offset)',
    'budgetTools[3]?.ok === false',
    'companion_tool'
  ]

  for (const marker of requiredMarkers) {
    assert.ok(script.includes(marker), `missing e2e acceptance marker: ${marker}`)
  }
})
