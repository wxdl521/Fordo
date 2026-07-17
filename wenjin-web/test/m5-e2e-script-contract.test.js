import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * Contract checks for the M5 teacher agent-trace Playwright script.
 * Full browser flow needs live backend + Vite; this file asserts structural
 * acceptance markers so CI still guards the script without servers.
 */
const __dirname = dirname(fileURLToPath(import.meta.url))
const script = readFileSync(resolve(__dirname, '../e2e/m5-agent-trace.mjs'), 'utf8')

describe('M5 e2e script contract', () => {
  it('documents how to run and prerequisites in the header', () => {
    expect(script).toMatch(/node \.\\e2e\\m5-agent-trace\.mjs|node \.\/e2e\/m5-agent-trace\.mjs/)
    expect(script).toContain('demo_teacher')
    expect(script).toContain('agent-trace-enabled')
    expect(script).toContain('V10')
    expect(script).toContain('m5-e2e-script-contract.test.js')
  })

  it('covers teacher UI route and list/empty acceptance', () => {
    const markers = [
      '/teacher/agent-runs',
      'Agent 轨迹',
      '暂无轨迹',
      'demo_teacher',
      'demo_student',
      'm5-agent-trace.png',
      'wj.teacher.currentCourseId'
    ]
    for (const marker of markers) {
      expect(script.includes(marker), `missing marker: ${marker}`).toBe(true)
    }
  })

  it('asserts teacher API success and student/unauth denial', () => {
    expect(script).toContain('/teacher/agent-runs?courseId=')
    expect(script).toContain('/teacher/agent-runs/${runId}/steps')
    expect(script).toMatch(/学生令牌访问 agent-runs → 401\/403/)
    expect(script).toMatch(/无令牌访问 agent-runs → 401\/403/)
    expect(script).toContain('isDenied')
    expect(script).toContain('isOk')
  })

  it('expands steps when runs exist', () => {
    expect(script).toContain('.run-row')
    expect(script).toContain('steps-table')
    expect(script).toMatch(/items\.length > 0/)
  })
})
