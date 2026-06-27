import dagre from '@dagrejs/dagre'
import { radiusOf, shortName } from './graphLayout.js'

const CANVAS_W = 1480, CANVAS_H = 740
const FIT_MARGIN = 48        // 适配画布留白
const TITLE_CHAR_W = 28      // 标题字宽估算（font-size 21 + letter-spacing 7，CJK 偏大兜底）
const CH_LABEL_GAP = 30      // 章节标题距其节点簇顶的间距
const CH_ROW_H = 24          // 标题同高重叠时上移错开的行高

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

  // —— 3. 适配到 1480×740 画布（等比缩放 + 垂直真居中）——
  const gw = g.graph().width || 1, gh = g.graph().height || 1
  const availW = CANVAS_W - 2 * FIT_MARGIN
  const availH = CANVAS_H - 2 * FIT_MARGIN          // 不再为顶部标题带保留高度
  const s = Math.min(availW / gw, availH / gh, 1.4)   // 别放太大，1.4 上限
  const tx = (CANVAS_W - gw * s) / 2
  const ty = (CANVAS_H - gh * s) / 2                  // 垂直真居中，不再沉底
  const fit = ([x, y]) => [x * s + tx, y * s + ty]

  const pos = {}; Object.entries(rawPos).forEach(([id, p]) => { pos[id] = fit(p) })
  const edgePaths = {}
  Object.entries(rawEdgePaths).forEach(([k, pts]) => { edgePaths[k] = pts.map(fit) })

  // —— 4. 章节标题贴各自节点簇顶：y = 簇 minY 上方 CH_LABEL_GAP；
  // 若两标题 x 区间重叠且纵向太近 → 靠右那个上移一行错开（上移只增大与节点间距，不压节点）——
  const chAgg = {}
  data.nodes.forEach(n => {
    const ch = n.chapter || ''; const p = pos[n.id]
    if (!ch || !p) return
    if (!chAgg[ch]) chAgg[ch] = { minX: Infinity, maxX: -Infinity, minY: Infinity }
    chAgg[ch].minX = Math.min(chAgg[ch].minX, p[0])
    chAgg[ch].maxX = Math.max(chAgg[ch].maxX, p[0])
    chAgg[ch].minY = Math.min(chAgg[ch].minY, p[1])
  })
  const rawLabels = Object.entries(chAgg)
    .map(([name, a]) => ({
      name, x: (a.minX + a.maxX) / 2,
      baseY: Math.max(a.minY - CH_LABEL_GAP, 24),
      hw: Math.max(1, name.length) * TITLE_CHAR_W / 2
    }))
    .sort((p, q) => p.x - q.x)
  const placed = []
  const chapterLabels = rawLabels.map(L => {
    let y = L.baseY
    let bumped = true, guard = 0
    while (bumped && guard++ < 8) {
      bumped = false
      for (const p of placed) {
        if (Math.abs(L.x - p.x) < (L.hw + p.hw + 8) && Math.abs(y - p.y) < CH_ROW_H) { y -= CH_ROW_H; bumped = true; break }
      }
    }
    y = Math.max(y, 18)
    placed.push({ x: L.x, y, hw: L.hw })
    return { name: L.name, x: L.x, y }
  })

  // —— 5. 标签位：贪心放置（候选 下/上/右/左），避开所有节点圆与已放标签 ——
  // 与 computeLayout 同款：密集 rank 列里"一律正下方"会与下方节点/相邻标签重叠。
  const ids = data.nodes.map(n => n.id).filter(id => pos[id])
  const boxes = []
  ids.forEach(id => {
    const p = pos[id]; const r = radius[id] + 5
    boxes.push([p[0] - r, p[1] - r, p[0] + r, p[1] + r])
  })
  const hit = (b) => boxes.some(o => b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1])
  const labelPos = {}
  const order = ids.slice().sort((a, b) => radius[b] - radius[a])
  order.forEach(id => {
    const n = byId[id]; const p = pos[id]; const r = radius[id]
    const w = shortName(n).length * 12.5; const h = 15
    const cands = [
      { b: [p[0] - w / 2, p[1] + r + 5, p[0] + w / 2, p[1] + r + 5 + h], x: p[0], y: p[1] + r + 17, a: 'middle' },
      { b: [p[0] - w / 2, p[1] - r - 5 - h, p[0] + w / 2, p[1] - r - 5], x: p[0], y: p[1] - r - 9, a: 'middle' },
      { b: [p[0] + r + 7, p[1] - h / 2, p[0] + r + 7 + w, p[1] + h / 2], x: p[0] + r + 9, y: p[1] + 4, a: 'start' },
      { b: [p[0] - r - 7 - w, p[1] - h / 2, p[0] - r - 7, p[1] + h / 2], x: p[0] - r - 9, y: p[1] + 4, a: 'end' }
    ]
    const pick = cands.find(c => !hit(c.b)) || cands[0]
    labelPos[id] = pick
    boxes.push(pick.b)
  })

  // —— 6. 星点（保留品牌暗色质感，少量即可；确定性种子）——
  let seed = 9; const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 }
  const stars = []
  for (let i = 0; i < 70; i++) stars.push({ x: rnd() * CANVAS_W, y: rnd() * CANVAS_H, r: 0.5 + rnd() * 1.0, o: 0.06 + rnd() * 0.16 })

  return { byId, hasKids, parentOf, radius, pos, labelPos, edgePaths, chapterLabels, stars }
}
