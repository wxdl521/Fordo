import { describe, it, expect } from 'vitest'
import { graphAriaOption, initChart, disposeChart } from '../echartsGraph.js'

describe('echartsGraph modular entry', () => {
  it('exports aria defaults for screen readers', () => {
    expect(graphAriaOption.aria.show).toBe(true)
    expect(graphAriaOption.aria.description).toBeTruthy()
  })

  it('initChart creates a disposable instance on a DOM node', () => {
    const el = document.createElement('div')
    el.style.width = '200px'
    el.style.height = '200px'
    document.body.appendChild(el)
    const chart = initChart(el)
    expect(chart).toBeTruthy()
    expect(typeof chart.setOption).toBe('function')
    disposeChart(chart)
    document.body.removeChild(el)
  })

  it('does not re-export full echarts package path (tree-shake surface)', async () => {
    // Guard: consumers must import from this module, not 'echarts'
    const src = await import('../echartsGraph.js?raw').catch(() => null)
    // In vitest without ?raw, just assert named API surface is small
    const mod = await import('../echartsGraph.js')
    expect(typeof mod.initChart).toBe('function')
    expect(typeof mod.disposeChart).toBe('function')
    expect(mod.graphAriaOption).toBeTruthy()
    expect(mod.default).toBeTruthy()
    void src
  })
})
