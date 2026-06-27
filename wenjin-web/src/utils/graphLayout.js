// ════════════════════════════════════════════════════════════
// 知识图谱力导向布局（纯 JS，框架无关）
// 与原型一致：确定性随机种子 → 章节锚点初始化 → 斥力/弹簧/章节引力迭代
// → 碰撞分离 → 标签贪心避让 → 背景星点。
// 染色地图 / 学情看板 / 图谱审核工作台共用此布局（viewBox 1480×740）。
// ════════════════════════════════════════════════════════════

export const ANCHORS = {
  软件工程概述: [180, 320],
  软件项目管理: [430, 110],
  需求确定: [450, 480],
  系统分析: [730, 300],
  系统设计: [950, 510],
  对象设计: [1010, 190],
  软件测试: [1220, 360],
  部署与维护: [1350, 150]
}

// 通用章节自动布锚：SE 全命中手调表则原样，否则自适应网格铺满安全区。
export function computeAnchors(chapters) {
  const uniq = []
  const seen = new Set()
  for (const c of chapters) {
    const ch = c == null ? '' : c
    if (!seen.has(ch)) { seen.add(ch); uniq.push(ch) }
  }
  const nonEmpty = uniq.filter((c) => c !== '')
  if (nonEmpty.length > 0 && nonEmpty.every((c) => ANCHORS[c])) {
    const out = {}
    nonEmpty.forEach((c) => { out[c] = ANCHORS[c] })
    return out   // '' 不映射 → computeLayout 回退 [700,360]，与旧版一致
  }
  const n = uniq.length
  const cols = Math.max(1, Math.ceil(Math.sqrt(n)))
  const rows = Math.max(1, Math.ceil(n / cols))
  const X0 = 140, X1 = 1340, Y0 = 120, Y1 = 620
  const out = {}
  uniq.forEach((c, i) => {
    const col = i % cols
    const row = Math.floor(i / cols)
    out[c] = [
      X0 + (col + 0.5) * ((X1 - X0) / cols),
      Y0 + (row + 0.5) * ((Y1 - Y0) / rows)
    ]
  })
  return out
}

export const SHORT = {
  KT01: '软工概念与生命周期', KT02: '传统过程模型', KT03: '现代过程模型',
  KT04: '需求概念与目标', KT05: '业务流程分析', KT06: '团队组织管理',
  KT07: '用例模型', KT10: '领域模型', KT13: '软件设计概念',
  KT14: '通用设计原则', KT15: '架构设计', KT16: '交互设计',
  KT17: '数据设计', KT18: 'OO设计原则', KT19: '设计类构建',
  KT20: '类设计转代码', KT22: '测试概念与过程', KT23: '黑盒测试方法',
  KT24: '部署与维护', KT25: '软件质量管理', KT27: 'UML建模工具',
  KT28: '数据库建模工具', KT29: '原型设计工具',
  'KT15-1': '分层架构', 'KT15-2': '架构风格与选型', 'KT18-1': 'SOLID原则', 'KT18-2': '设计模式'
}

export function shortName(n) {
  if (SHORT[n.id]) return SHORT[n.id]
  return n.name.length > 10 ? n.name.slice(0, 10) + '…' : n.name
}

export function radiusOf(n, hasKids) {
  const r = (hasKids[n.id] ? 13 : 8) + (n.is_key ? 1.5 : 0) + (n.difficulty - 3)
  return Math.max(6.5, Math.min(17, r))
}

// 计算整图布局，返回 { byId, hasKids, parentOf, inEdges, pos, radius, labelPos, stars }
export function computeLayout(data) {
  const byId = {}
  data.nodes.forEach((n) => { byId[n.id] = n })
  const hasKids = {}
  const parentOf = {}
  const inEdges = {}
  data.edges.forEach((e) => {
    if (e.type === '包含') { hasKids[e.source] = true; parentOf[e.target] = e.source }
    if (e.type === '前置' || e.type === '包含') {
      if (!inEdges[e.target]) inEdges[e.target] = []
      inEdges[e.target].push(e)
    }
  })

  const chapters = []
  const seenCh = new Set()
  data.nodes.forEach((n) => {
    const ch = n.chapter || ''
    if (!seenCh.has(ch)) { seenCh.add(ch); chapters.push(ch) }
  })
  const anchors = computeAnchors(chapters)

  let seed = 9
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 }
  const pos = {}
  data.nodes.forEach((n) => {
    if (n.id.indexOf('-') === -1) {
      const a = anchors[n.chapter] || [700, 360]
      pos[n.id] = [a[0] + (rnd() - 0.5) * 240, a[1] + (rnd() - 0.5) * 200]
    }
  })
  data.nodes.forEach((n) => {
    if (n.id.indexOf('-') !== -1) {
      let p = parentOf[n.id] && pos[parentOf[n.id]]
      if (!p) { p = anchors[n.chapter] || [700, 360] }
      pos[n.id] = [p[0] + (rnd() - 0.5) * 150, p[1] + (rnd() - 0.5) * 150]
    }
  })

  const ids = data.nodes.map((n) => n.id)
  const rest = { 前置: 165, 包含: 88, 相关: 215 }
  const kk = { 前置: 0.03, 包含: 0.055, 相关: 0.012 }

  for (let it = 0; it < 280; it++) {
    const t = 1 - it / 280
    const f = {}
    ids.forEach((id) => { f[id] = [0, 0] })
    for (let i = 0; i < ids.length; i++) {
      for (let j = i + 1; j < ids.length; j++) {
        const a = pos[ids[i]]; const b = pos[ids[j]]
        const dx = a[0] - b[0]; const dy = a[1] - b[1]
        const d2 = dx * dx + dy * dy + 0.01
        const d = Math.sqrt(d2)
        if (d < 340) {
          const rep = 3200 / d2
          f[ids[i]][0] += (dx / d) * rep * 60; f[ids[i]][1] += (dy / d) * rep * 60
          f[ids[j]][0] -= (dx / d) * rep * 60; f[ids[j]][1] -= (dy / d) * rep * 60
        }
      }
    }
    data.edges.forEach((e) => {
      const a = pos[e.source]; const b = pos[e.target]
      if (!a || !b) return
      const dx = b[0] - a[0]; const dy = b[1] - a[1]
      const d = Math.sqrt(dx * dx + dy * dy) + 0.01
      const er = rest[e.type] ?? rest['相关']
      const ek = kk[e.type] ?? kk['相关']
      const pull = (d - er) * ek
      f[e.source][0] += (dx / d) * pull * 60; f[e.source][1] += (dy / d) * pull * 60
      f[e.target][0] -= (dx / d) * pull * 60; f[e.target][1] -= (dy / d) * pull * 60
    })
    data.nodes.forEach((n) => {
      const a = anchors[n.chapter]
      if (!a) return
      f[n.id][0] += (a[0] - pos[n.id][0]) * 0.014 * 60
      f[n.id][1] += (a[1] - pos[n.id][1]) * 0.014 * 60
    })
    ids.forEach((id) => {
      pos[id][0] += Math.max(-14, Math.min(14, f[id][0] * 0.016)) * t
      pos[id][1] += Math.max(-14, Math.min(14, f[id][1] * 0.016)) * t
      pos[id][0] = Math.max(60, Math.min(1420, pos[id][0]))
      pos[id][1] = Math.max(60, Math.min(680, pos[id][1]))
    })
  }

  const radius = {}
  data.nodes.forEach((n) => { radius[n.id] = radiusOf(n, hasKids) })

  for (let cp = 0; cp < 120; cp++) {
    let moved = false
    for (let ci = 0; ci < ids.length; ci++) {
      for (let cj = ci + 1; cj < ids.length; cj++) {
        const a = pos[ids[ci]]; const b = pos[ids[cj]]
        const dx = b[0] - a[0]; const dy = b[1] - a[1]
        const d = Math.sqrt(dx * dx + dy * dy) + 0.01
        const min = (radius[ids[ci]] + radius[ids[cj]]) * 2.2 + 30
        if (d < min) {
          const push = (min - d) / 2
          const ux = dx / d; const uy = dy / d
          a[0] -= ux * push; a[1] -= uy * push
          b[0] += ux * push; b[1] += uy * push
          moved = true
        }
      }
    }
    ids.forEach((id) => {
      pos[id][0] = Math.max(60, Math.min(1420, pos[id][0]))
      pos[id][1] = Math.max(70, Math.min(665, pos[id][1]))
    })
    if (!moved) break
  }

  // 标签贪心放置：候选位 下/上/右/左，避开所有节点与已放标签
  const boxes = []
  ids.forEach((id) => {
    const p = pos[id]; const r = radius[id] + 5
    boxes.push([p[0] - r, p[1] - r, p[0] + r, p[1] + r])
  })
  const hit = (b) => boxes.some((o) => b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1])
  const labelPos = {}
  const order = ids.slice().sort((a, b) => radius[b] - radius[a])
  order.forEach((id) => {
    const n = byId[id]; const p = pos[id]; const r = radius[id]
    const w = shortName(n).length * 12.5; const h = 15
    const cands = [
      { b: [p[0] - w / 2, p[1] + r + 5, p[0] + w / 2, p[1] + r + 5 + h], x: p[0], y: p[1] + r + 17, a: 'middle' },
      { b: [p[0] - w / 2, p[1] - r - 5 - h, p[0] + w / 2, p[1] - r - 5], x: p[0], y: p[1] - r - 9, a: 'middle' },
      { b: [p[0] + r + 7, p[1] - h / 2, p[0] + r + 7 + w, p[1] + h / 2], x: p[0] + r + 9, y: p[1] + 4, a: 'start' },
      { b: [p[0] - r - 7 - w, p[1] - h / 2, p[0] - r - 7, p[1] + h / 2], x: p[0] - r - 9, y: p[1] + 4, a: 'end' }
    ]
    const pick = cands.find((c) => !hit(c.b)) || cands[0]
    labelPos[id] = pick
    boxes.push(pick.b)
  })

  const stars = []
  for (let s = 0; s < 110; s++) {
    stars.push({ x: rnd() * 1480, y: rnd() * 740, r: 0.5 + rnd() * 1.1, o: 0.08 + rnd() * 0.22 })
  }

  return { byId, hasKids, parentOf, inEdges, pos, radius, labelPos, stars }
}
