// ════════════════════════════════════════════════════════════
// 知识图谱 → 独立 SVG 字符串（审核预览，暖纸星图皮，复刻染色地图视觉）。
// 布局走 computeLayout（力导向）；颜色语义保留审核三态（待复核/已采纳/已确认）。
// 内联字面色（导出 SVG 不依赖 CSS 变量）。
// ════════════════════════════════════════════════════════════
import { computeLayout, shortName, computeAnchors } from './graphLayout.js'

const PAL = {
  bg: '#f7f3ec',          // 暖纸底
  chap: '#6f6759',        // 章节宋体水印 mut
  node: '#8a8073',        // 常识节点 warm stone
  nodeKey: '#b4422e',     // 考核重点 朱砂
  label: '#6f6759',       // 节点标签 mut
  ePending: '#b4422e',    // 待复核边 朱砂（虚线）
  eAdopted: '#3d7a5e',    // 已采纳边 绿（实线）
  eConfirm: '#6f6759'     // 已确认边 灰（淡实线）
}

const r2 = (v) => Math.round(v * 10) / 10
const esc = (s) =>
  String(s == null ? '' : s).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

// 边状态：待复核 > 已采纳 > 已确认（决定描边样式与叠放顺序）
function edgeState(e) {
  if (e.pending) return 'pending'
  if (e.confidence && e.confidence > 0) return 'adopted'
  return 'confirmed'
}
const STATE_STYLE = {
  pending:   { stroke: PAL.ePending, width: 2,   opacity: 0.85, dash: '6 5', marker: 'arrow-pending' },
  adopted:   { stroke: PAL.eAdopted, width: 1.6, opacity: 0.7,  dash: null,  marker: 'arrow-adopted' },
  confirmed: { stroke: PAL.eConfirm, width: 1,   opacity: 0.42, dash: null,  marker: 'arrow-confirm' }
}
// 叠放顺序：已确认最底 → 已采纳 → 待复核最上
const STATE_ORDER = { confirmed: 0, adopted: 1, pending: 2 }

/** 生成完整 SVG 字符串。data={nodes:[{id,name,chapter,difficulty,is_key}],edges:[{source,target,type,pending?,confidence?}]} */
export function renderGraphSvg(data) {
  const L = computeLayout(data)
  const nodes = data.nodes || []
  const edges = data.edges || []

  // 章节水印：数据中实际出现的非空章节，按 computeAnchors 锚点上方放置（SE 全命中手调表，否则网格）
  const chapters = []
  const seenCh = new Set()
  nodes.forEach((n) => { const ch = n.chapter || ''; if (ch && !seenCh.has(ch)) { seenCh.add(ch); chapters.push(ch) } })
  const anchors = computeAnchors(chapters)
  const chapterLabels = chapters.map((ch) => {
    const a = anchors[ch] || [700, 360]
    return { name: ch, x: a[0], y: Math.max(42, a[1] - 95) }
  })

  const out = []
  out.push('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1480 740" width="1480" height="740" font-family="-apple-system,system-ui,sans-serif">')
  out.push(`<rect x="0" y="0" width="1480" height="740" fill="${PAL.bg}"/>`)

  // 三色箭头 marker（marker fill 固定，不能继承 stroke，故每态一个）
  out.push('<defs>')
  out.push(`<marker id="arrow-pending" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L7,4 L0,8 Z" fill="${PAL.ePending}"/></marker>`)
  out.push(`<marker id="arrow-adopted" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L7,4 L0,8 Z" fill="${PAL.eAdopted}"/></marker>`)
  out.push(`<marker id="arrow-confirm" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L7,4 L0,8 Z" fill="${PAL.eConfirm}"/></marker>`)
  out.push('</defs>')

  // 章节宋体水印（最底层）
  chapterLabels.forEach((c) =>
    out.push(`<text x="${r2(c.x)}" y="${r2(c.y)}" text-anchor="middle" fill="${PAL.chap}" font-size="21" letter-spacing="7" font-family="'Noto Serif SC',serif" font-weight="500" opacity="0.22">${esc(c.name)}</text>`))

  // 边：按状态排序后绘制（已确认最底 → 待复核最上），均为直线；仅「前置」边带箭头
  edges
    .map((e) => ({ e, st: edgeState(e) }))
    .filter(({ e }) => L.pos[e.source] && L.pos[e.target])
    .sort((a, b) => STATE_ORDER[a.st] - STATE_ORDER[b.st])
    .forEach(({ e, st }) => {
      const a = L.pos[e.source], b = L.pos[e.target]
      const s = STATE_STYLE[st]
      const dash = s.dash ? ` stroke-dasharray="${s.dash}"` : ''
      const arrow = e.type === '前置' ? ` marker-end="url(#${s.marker})"` : ''
      out.push(`<line x1="${r2(a[0])}" y1="${r2(a[1])}" x2="${r2(b[0])}" y2="${r2(b[1])}" stroke="${s.stroke}" stroke-width="${s.width}" opacity="${s.opacity}"${dash}${arrow} stroke-linecap="round"/>`)
    })

  // 节点：辉光halo（两圈半透明同色圆）+ 主圆 + 标签
  nodes.forEach((n) => {
    const p = L.pos[n.id]
    if (!p) return
    const r = L.radius[n.id] || 8
    const c = n.is_key ? PAL.nodeKey : PAL.node
    out.push(`<circle cx="${r2(p[0])}" cy="${r2(p[1])}" r="${r2(r * 1.95)}" fill="${c}" opacity="0.10"/>`)
    out.push(`<circle cx="${r2(p[0])}" cy="${r2(p[1])}" r="${r2(r * 1.35)}" fill="${c}" opacity="0.18"/>`)
    out.push(`<circle class="wj-node" cx="${r2(p[0])}" cy="${r2(p[1])}" r="${r2(r)}" fill="${c}"/>`)
    const lp = L.labelPos[n.id]
    if (lp) {
      const fs = n.id.indexOf('-') === -1 ? 12 : 11
      out.push(`<text x="${r2(lp.x)}" y="${r2(lp.y)}" text-anchor="${lp.a}" fill="${PAL.label}" font-size="${fs}" opacity="0.9">${esc(shortName(n))}</text>`)
    }
  })

  // 左下图例
  const lx = 26, ly0 = 656, step = 20
  const legend = [
    { stroke: PAL.ePending, width: 2,   dash: '6 5', label: '待复核' },
    { stroke: PAL.eAdopted, width: 1.6, dash: null,  label: '已采纳' },
    { stroke: PAL.eConfirm, width: 1,   dash: null,  opacity: 0.5, label: '已确认' },
    { dot: PAL.nodeKey, label: '考核重点' }
  ]
  legend.forEach((row, i) => {
    const y = ly0 + i * step
    if (row.dot) {
      out.push(`<circle cx="${lx + 8}" cy="${y}" r="4" fill="${row.dot}"/>`)
      out.push(`<text x="${lx + 20}" y="${y + 4}" fill="${PAL.label}" font-size="12">${esc(row.label)}</text>`)
    } else {
      const dashAttr = row.dash ? ` stroke-dasharray="${row.dash}"` : ''
      const opAttr = row.opacity != null ? ` opacity="${row.opacity}"` : ''
      out.push(`<line x1="${lx}" y1="${y}" x2="${lx + 26}" y2="${y}" stroke="${row.stroke}" stroke-width="${row.width}"${dashAttr}${opAttr} stroke-linecap="round"/>`)
      out.push(`<text x="${lx + 34}" y="${y + 4}" fill="${PAL.label}" font-size="12">${esc(row.label)}</text>`)
    }
  })

  out.push('</svg>')
  return out.join('\n')
}
