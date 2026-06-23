// ════════════════════════════════════════════════════════════
// 知识图谱 → 独立 SVG 字符串（兜底渲染，复刻染色地图暗色风格）。
// 与 ColorMap.vue 视觉一致，但内联字面色（导出 SVG 不依赖 CSS 变量）。
// ════════════════════════════════════════════════════════════
import { computeLayout, shortName } from './graphLayout.js'

const PAL = {
  bg: '#121317',
  star: '#E8E3D8',
  chap: '#9A948A',
  node: '#4A4D55',
  nodeKey: '#D85E45',
  label: '#9A948A',
  edge: 'rgba(232,227,216,0.18)',
  edgePre: '#9A948A'
}

const r2 = (v) => Math.round(v * 10) / 10
const esc = (s) =>
  String(s == null ? '' : s).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

/** 生成完整 SVG 字符串。data={nodes:[{id,name,chapter,difficulty,is_key}],edges:[{source,target,type}]} */
export function renderGraphSvg(data) {
  const L = computeLayout(data)

  // 章节标题：每章节取其节点质心，上移 60
  const agg = {}
  data.nodes.forEach((n) => {
    const ch = n.chapter || ''
    const p = L.pos[n.id]
    if (!ch || !p) return
    if (!agg[ch]) agg[ch] = { sx: 0, sy: 0, c: 0 }
    agg[ch].sx += p[0]; agg[ch].sy += p[1]; agg[ch].c++
  })
  const chapLabels = Object.entries(agg).map(([name, a]) => ({
    name, x: a.sx / a.c, y: a.sy / a.c - 60
  }))

  const out = []
  out.push('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1480 740" width="1480" height="740" font-family="-apple-system,system-ui,sans-serif">')
  out.push(`<rect x="0" y="0" width="1480" height="740" fill="${PAL.bg}"/>`)
  out.push(`<defs><marker id="arrow" markerWidth="7" markerHeight="7" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="${PAL.edgePre}"/></marker></defs>`)

  L.stars.forEach((s) =>
    out.push(`<circle cx="${r2(s.x)}" cy="${r2(s.y)}" r="${r2(s.r)}" fill="${PAL.star}" opacity="${(s.o * 0.5).toFixed(2)}"/>`))

  chapLabels.forEach((c) =>
    out.push(`<text x="${r2(c.x)}" y="${r2(c.y)}" text-anchor="middle" fill="${PAL.chap}" font-size="21" letter-spacing="7" font-family="'Noto Serif SC',serif" opacity="0.5">${esc(c.name)}</text>`))

  data.edges.forEach((e) => {
    const a = L.pos[e.source]; const b = L.pos[e.target]
    if (!a || !b) return
    if (e.type === '前置') {
      out.push(`<line x1="${r2(a[0])}" y1="${r2(a[1])}" x2="${r2(b[0])}" y2="${r2(b[1])}" stroke="${PAL.edgePre}" stroke-width="1.4" opacity="0.5" marker-end="url(#arrow)"/>`)
    } else if (e.type === '包含') {
      out.push(`<line x1="${r2(a[0])}" y1="${r2(a[1])}" x2="${r2(b[0])}" y2="${r2(b[1])}" stroke="${PAL.edge}" stroke-width="1.2" stroke-dasharray="5 5"/>`)
    } else {
      out.push(`<line x1="${r2(a[0])}" y1="${r2(a[1])}" x2="${r2(b[0])}" y2="${r2(b[1])}" stroke="${PAL.edge}" stroke-width="0.8" opacity="0.6"/>`)
    }
  })

  data.nodes.forEach((n) => {
    const p = L.pos[n.id]
    if (!p) return
    const r = L.radius[n.id] || 8
    const fill = n.is_key ? PAL.nodeKey : PAL.node
    out.push(`<circle class="wj-node" cx="${r2(p[0])}" cy="${r2(p[1])}" r="${r2(r)}" fill="${fill}"/>`)
    const lp = L.labelPos[n.id]
    if (lp) out.push(`<text x="${r2(lp.x)}" y="${r2(lp.y)}" text-anchor="${lp.a}" fill="${PAL.label}" font-size="11">${esc(shortName(n))}</text>`)
  })

  out.push('</svg>')
  return out.join('\n')
}
