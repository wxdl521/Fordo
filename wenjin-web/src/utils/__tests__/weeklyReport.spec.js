import { describe, expect, it } from 'vitest'
import { engagementRows, isEmptyWeekly, pct, trend, trendClass } from '../weeklyReport.js'

describe('weeklyReport helpers', () => {
  it('formats percent and trend', () => {
    expect(pct(0.304)).toBe('30%')
    expect(pct(null)).toBe('—')
    expect(trend(3)).toBe('↑3')
    expect(trend(-2)).toBe('↓2')
    expect(trend(0)).toBe('持平')
    expect(trend(undefined)).toBe('—')
    expect(trendClass(1)).toBe('up')
    expect(trendClass(-1)).toBe('down')
  })

  it('derives deltas when the payload predates *Delta fields', () => {
    const rows = engagementRows({ practiceThisWeek: 5, practiceLastWeek: 2 })
    expect(rows).toEqual([{ label: '作答次数', cur: 5, prev: 2, delta: 3 }])
    expect(engagementRows(null)).toEqual([])
  })

  it('detects an all-empty weekly payload only for the new format', () => {
    expect(isEmptyWeekly({ summary: 's', weakNodes: [], anomalies: [], engagement: {} })).toBe(true)
    expect(isEmptyWeekly({ weakNodes: [], anomalies: [], engagement: {} })).toBe(false)
    expect(isEmptyWeekly({ summary: 's', weakNodes: [{}], anomalies: [] })).toBe(false)
  })
})
