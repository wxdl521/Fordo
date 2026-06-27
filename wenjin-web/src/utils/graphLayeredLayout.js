import dagre from '@dagrejs/dagre'
import { radiusOf, shortName } from './graphLayout.js'

const CANVAS_W = 1480, CANVAS_H = 740
const FIT_MARGIN = 48        // 适配画布留白
const TITLE_BAND = 46        // 顶部章节标题带高度（布局整体下移这么多）

export function computeLayeredLayout(data) {
  // —— 复用既有派生信息（与 computeLayout 同口径，但不调用它）——
  const byId = {}; data.nodes.forEach(n => { byId[n.id] = n })
  const hasKids = {}; const parentOf = {}
  data.edges.forEach(e => {
    if (e.type === '包含') { hasKids[e.source] = true; parentOf[e.target] = e.source }
  })
  const radius = {}; data.nodes.forEach(n => { radius[n.id] = radiusOf(n, hasKids) })

  // —— 1. 建 dagre 图 ——
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 95, marginx: 20, marginy: 20, ranker: 'tight-tree' })
  g.setDefaultEdgeLabel(() => ({}))

  data.nodes.forEach(n => {
    const r = radius[n.id]
    const labelW = shortName(n).length * 12        // 估算标签宽，给节点盒留位
    g.setNode(n.id, { width: Math.max(2 * r, labelW), height: 2 * r + 20 })
  })

  // 定层边：前置 ∪ 包含（相关/未知不参与）
  const LAYOUT_TYPES = new Set(['前置', '包含'])
  data.edges.forEach(e => {
    if (LAYOUT_TYPES.has(e.type) && byId[e.source] && byId[e.target]) {
      g.setEdge(e.source, e.target)
    }
  })

  dagre.layout(g)

  // —— 2. 读坐标（dagre 节点 x/y 即中心）——
  const rawPos = {}
  data.nodes.forEach(n => {
    const gn = g.node(n.id)
    if (gn) rawPos[n.id] = [gn.x, gn.y]
  })

  // 边路由点（仅布局边有）
  const rawEdgePaths = {}
  data.edges.forEach(e => {
    if (!LAYOUT_TYPES.has(e.type)) return
    const ge = g.edge({ v: e.source, w: e.target })
    if (ge && ge.points) rawEdgePaths[`${e.source}->${e.target}`] = ge.points.map(p => [p.x, p.y])
  })

  // —— 3. 适配到 1480×740 画布（等比缩放 + 居中 + 顶部留标题带）——
  const gw = g.graph().width || 1, gh = g.graph().height || 1
  const availW = CANVAS_W - 2 * FIT_MARGIN
  const availH = CANVAS_H - 2 * FIT_MARGIN - TITLE_BAND
  const s = Math.min(availW / gw, availH / gh, 1.4)   // 别放太大，1.4 上限
  const tx = (CANVAS_W - gw * s) / 2
  const ty = FIT_MARGIN + TITLE_BAND + (availH - gh * s) / 2
  const fit = ([x, y]) => [x * s + tx, y * s + ty]

  const pos = {}; Object.entries(rawPos).forEach(([id, p]) => { pos[id] = fit(p) })
  const edgePaths = {}
  Object.entries(rawEdgePaths).forEach(([k, pts]) => { edgePaths[k] = pts.map(fit) })

  // —— 4. 章节标题置顶：x = 该章节点 x 区间中点，y 固定在标题带 ——
  const chAgg = {}
  data.nodes.forEach(n => {
    const ch = n.chapter || ''; const p = pos[n.id]
    if (!ch || !p) return
    if (!chAgg[ch]) chAgg[ch] = { minX: Infinity, maxX: -Infinity }
    chAgg[ch].minX = Math.min(chAgg[ch].minX, p[0])
    chAgg[ch].maxX = Math.max(chAgg[ch].maxX, p[0])
  })
  const chapterLabels = Object.entries(chAgg).map(([name, a]) => ({
    name, x: (a.minX + a.maxX) / 2, y: FIT_MARGIN + TITLE_BAND * 0.55
  }))

  // —— 5. 标签位：分层图节点稀疏，直接放节点正下方即可（不用贪心避让）——
  const labelPos = {}
  data.nodes.forEach(n => {
    const p = pos[n.id]; if (!p) return
    labelPos[n.id] = { x: p[0], y: p[1] + radius[n.id] + 15, a: 'middle' }
  })

  // —— 6. 星点（保留品牌暗色质感，少量即可；确定性种子）——
  let seed = 9; const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 }
  const stars = []
  for (let i = 0; i < 70; i++) stars.push({ x: rnd() * CANVAS_W, y: rnd() * CANVAS_H, r: 0.5 + rnd() * 1.0, o: 0.06 + rnd() * 0.16 })

  return { byId, hasKids, parentOf, radius, pos, labelPos, edgePaths, chapterLabels, stars }
}
