import { describe, expect, it } from 'vitest'
import { buildActivityOption, buildAiOption, compactNumber, readChartPalette } from '../adminTrendOptions.js'

const PALETTE = { ink: '#111', mut: '#666', line: '#ddd', card: '#fff', acc: '#a00', ok: '#0a0', warn: '#fa0' }

describe('adminTrendOptions', () => {
  it('buildActivityOption plots logins / activeUsers / failures against the dates', () => {
    const option = buildActivityOption(
      [
        { date: '2026-10-14', logins: 3, activeUsers: 2, failures: 1 },
        { date: '2026-10-15', logins: 0, activeUsers: 0, failures: 0 }
      ],
      PALETTE
    )
    expect(option.xAxis.data).toEqual(['2026-10-14', '2026-10-15'])
    expect(option.series.map((s) => s.name)).toEqual(['登录次数', '活跃用户', '登录失败'])
    expect(option.series[0].data).toEqual([3, 0])
    expect(option.series[1].data).toEqual([2, 0])
    expect(option.series[2].data).toEqual([1, 0])
    expect(option.series[2].lineStyle.color).toBe(PALETTE.acc)
    expect(option.aria.enabled).toBe(true)
    expect(option.xAxis.axisLabel.formatter('2026-10-14')).toBe('10-14')
  })

  it('buildAiOption puts runs on the left axis and tokens on the right axis', () => {
    const option = buildAiOption([{ date: '2026-10-14', runs: 4, tokens: 1500 }], PALETTE)
    expect(option.yAxis).toHaveLength(2)
    expect(option.series[0]).toMatchObject({ name: '调用次数', type: 'bar', yAxisIndex: 0, data: [4] })
    expect(option.series[1]).toMatchObject({ name: 'Tokens', type: 'line', yAxisIndex: 1, data: [1500] })
  })

  it('tolerates missing / non-numeric data', () => {
    expect(buildActivityOption(undefined, PALETTE).series[0].data).toEqual([])
    expect(buildAiOption([{ date: '2026-10-14', runs: 'x', tokens: null }], PALETTE).series[0].data).toEqual([0])
  })

  it('compactNumber shortens large axis ticks', () => {
    expect(compactNumber(7)).toBe('7')
    expect(compactNumber(1200)).toBe('1.2k')
    expect(compactNumber(20000)).toBe('20k')
    expect(compactNumber(1500000)).toBe('1.5M')
  })

  it('readChartPalette falls back to defaults and picks up CSS variables', () => {
    expect(readChartPalette().acc).toBeTruthy()
    const el = document.createElement('div')
    el.style.setProperty('--acc', '#123456')
    document.body.appendChild(el)
    expect(readChartPalette(el).acc).toBe('#123456')
    el.remove()
  })
})
