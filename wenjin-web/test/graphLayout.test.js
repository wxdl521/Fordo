import { describe, it, expect } from 'vitest'
import { computeAnchors, ANCHORS, computeLayout } from '../src/utils/graphLayout.js'
import { renderGraphSvg } from '../src/utils/graphSvgRenderer.js'

describe('graphLayout anchors and layout', () => {
  it('uses hand-tuned SE chapter anchors', () => {
    const se = Object.keys(ANCHORS)
    const a = computeAnchors(se)
    for (const ch of se) {
      expect(a[ch]).toEqual(ANCHORS[ch])
    }
  })

  it('spreads unknown chapter sets horizontally inside safe bounds', () => {
    const java = ['Java概述', '语言程序基础', '面向对象', '常用类库及JDBC']
    const a = computeAnchors(java)
    const xs = java.map((c) => a[c][0])
    const spread = Math.max(...xs) - Math.min(...xs)
    expect(spread).toBeGreaterThan(400)
    for (const c of java) {
      expect(a[c][0]).toBeGreaterThanOrEqual(140)
      expect(a[c][0]).toBeLessThanOrEqual(1340)
      expect(a[c][1]).toBeGreaterThanOrEqual(120)
      expect(a[c][1]).toBeLessThanOrEqual(620)
    }
  })

  it('assigns anchors for empty chapter labels without throwing', () => {
    const withEmpty = ['第一章', '', '第三章']
    const a = computeAnchors(withEmpty)
    expect(Array.isArray(a[''])).toBe(true)
  })

  it('dedupes chapters while preserving order', () => {
    const dup = ['甲', '乙', '甲', '丙']
    const a = computeAnchors(dup)
    expect(Object.keys(a)).toHaveLength(3)
  })

  it('spreads non-SE nodes across x after layout', () => {
    const data = {
      nodes: [
        { id: 'A1', name: 'a', chapter: 'Java概述', difficulty: 3, is_key: false },
        { id: 'B1', name: 'b', chapter: '语言程序基础', difficulty: 3, is_key: false },
        { id: 'C1', name: 'c', chapter: '面向对象', difficulty: 3, is_key: false },
        { id: 'D1', name: 'd', chapter: '常用类库及JDBC', difficulty: 3, is_key: false }
      ],
      edges: []
    }
    const L = computeLayout(data)
    const xs = data.nodes.map((n) => L.pos[n.id][0])
    const spread = Math.max(...xs) - Math.min(...xs)
    expect(spread).toBeGreaterThan(300)
  })

  it('keeps SE anchors when an empty chapter is mixed in', () => {
    const se = Object.keys(ANCHORS)
    const a = computeAnchors([...se, ''])
    for (const ch of se) {
      expect(a[ch]).toEqual(ANCHORS[ch])
    }
  })

  it('produces finite coords for unknown edge types', () => {
    const data = {
      nodes: [
        { id: 'X1', name: 'x', chapter: '甲', difficulty: 3, is_key: false },
        { id: 'X2', name: 'y', chapter: '甲', difficulty: 3, is_key: false }
      ],
      edges: [{ source: 'X1', target: 'X2', type: '应用' }]
    }
    const L = computeLayout(data)
    for (const id of ['X1', 'X2']) {
      expect(Number.isFinite(L.pos[id][0])).toBe(true)
      expect(Number.isFinite(L.pos[id][1])).toBe(true)
    }
  })

  it('keeps the public computeLayout key surface stable', () => {
    const sample = {
      nodes: [{ id: 'N1', name: '甲节点', chapter: '甲', difficulty: 3, is_key: false }],
      edges: []
    }
    const L = computeLayout(sample)
    const expected = ['byId', 'hasKids', 'parentOf', 'inEdges', 'pos', 'radius', 'labelPos', 'stars']
    expect(expected.every((k) => k in L)).toBe(true)
    expect(Object.keys(L).sort()).toEqual(expected.slice().sort())
  })
})

describe('renderGraphSvg behavior', () => {
  const sample = {
    nodes: [
      { id: 'N1', name: '甲节点', chapter: '甲', difficulty: 3, is_key: false },
      { id: 'N2', name: '乙节点', chapter: '乙', difficulty: 3, is_key: true },
      { id: 'N3', name: '丙节点', chapter: '丙', difficulty: 3, is_key: false },
      { id: 'N2-1', name: '乙子概念', chapter: '乙', difficulty: 3, is_key: false }
    ],
    edges: [
      { source: 'N1', target: 'N2', type: '前置', pending: true },
      { source: 'N2', target: 'N3', type: '前置', confidence: 85 },
      { source: 'N2', target: 'N2-1', type: '包含' },
      { source: 'N1', target: 'N3', type: '相关' },
      { source: 'N3', target: 'N1', type: '应用' }
    ]
  }

  it('returns a valid SVG string for a single node graph', () => {
    const svg = renderGraphSvg({
      nodes: [{ id: 'A1', name: 'a', chapter: 'X', difficulty: 3, is_key: false }],
      edges: []
    })
    expect(typeof svg).toBe('string')
    expect(svg.startsWith('<svg')).toBe(true)
  })

  it('renders force-layout edges as lines without dagre path remnants', () => {
    const svg = renderGraphSvg(sample)
    expect(svg.includes('<line ')).toBe(true)
    expect(svg.includes('fill="none"')).toBe(false)
  })

  it('uses warm paper background fill', () => {
    const svg = renderGraphSvg(sample)
    expect(svg.includes('fill="#f7f3ec"')).toBe(true)
  })

  it('draws dual halo rings on nodes', () => {
    const svg = renderGraphSvg(sample)
    expect(svg.includes('opacity="0.10"')).toBe(true)
    expect(svg.includes('opacity="0.18"')).toBe(true)
  })

  it('colors edges and arrow markers by review state', () => {
    const svg = renderGraphSvg(sample)
    expect(svg.includes('stroke="#b4422e"')).toBe(true)
    expect(svg.includes('stroke="#3d7a5e"')).toBe(true)
    expect(svg.includes('stroke="#6f6759"')).toBe(true)
    expect(svg.includes('id="arrow-pending"')).toBe(true)
    expect(svg.includes('id="arrow-adopted"')).toBe(true)
    expect(svg.includes('id="arrow-confirm"')).toBe(true)
  })

  it('dashes pending prereq edges and omits arrows on non-prereq edges', () => {
    const svg = renderGraphSvg(sample)
    const lines = svg.split('\n').filter((l) => l.startsWith('<line'))
    const pendingLine = lines.find((l) => l.includes('stroke="#b4422e"'))
    expect(pendingLine).toBeTruthy()
    expect(pendingLine.includes('stroke-dasharray')).toBe(true)
    expect(pendingLine.includes('marker-end="url(#arrow-pending)"')).toBe(true)
    const adoptedLine = lines.find((l) => l.includes('stroke="#3d7a5e"'))
    expect(adoptedLine && adoptedLine.includes('marker-end="url(#arrow-adopted)"')).toBe(true)
    const grayLines = lines.filter((l) => l.includes('stroke="#6f6759"'))
    expect(grayLines.some((l) => !l.includes('marker-end'))).toBe(true)
  })

  it('emits one chapter watermark per non-empty chapter', () => {
    const svg = renderGraphSvg(sample)
    const titles = (svg.match(/font-size="21"/g) || []).length
    expect(titles).toBe(3)
  })

  it('includes legend labels for review states', () => {
    const svg = renderGraphSvg(sample)
    expect(svg.includes('待复核')).toBe(true)
    expect(svg.includes('已采纳')).toBe(true)
    expect(svg.includes('已确认')).toBe(true)
  })

  it('never emits NaN coordinates in the SVG', () => {
    const svg = renderGraphSvg(sample)
    expect(svg.includes('NaN')).toBe(false)
  })

  it('handles empty and single-node graphs', () => {
    const empty = renderGraphSvg({ nodes: [], edges: [] })
    expect(empty.startsWith('<svg') && empty.includes('</svg>')).toBe(true)
    const single = renderGraphSvg({
      nodes: [{ id: 'A', name: 'a', chapter: 'X', difficulty: 3, is_key: false }],
      edges: []
    })
    expect(single.startsWith('<svg')).toBe(true)
  })
})
