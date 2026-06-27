import assert from 'node:assert/strict'
import { computeAnchors, ANCHORS, computeLayout } from '../src/utils/graphLayout.js'
import { computeLayeredLayout } from '../src/utils/graphLayeredLayout.js'
import { renderGraphSvg } from '../src/utils/graphSvgRenderer.js'

// 1) SE 全命中 → 返回手调表坐标
{
  const se = Object.keys(ANCHORS)
  const a = computeAnchors(se)
  for (const ch of se) {
    assert.deepEqual(a[ch], ANCHORS[ch], `SE 章节 ${ch} 应用手调锚点`)
  }
}

// 2) 未知章节集(模拟 Java 4 章)→ 网格,x 横向铺开且在安全区内
{
  const java = ['Java概述', '语言程序基础', '面向对象', '常用类库及JDBC']
  const a = computeAnchors(java)
  const xs = java.map((c) => a[c][0])
  const spread = Math.max(...xs) - Math.min(...xs)
  assert.ok(spread > 400, `非 SE 章节应横向铺开,实测 spread=${spread}`)
  for (const c of java) {
    assert.ok(a[c][0] >= 140 && a[c][0] <= 1340, `${c} 的 x 在安全区`)
    assert.ok(a[c][1] >= 120 && a[c][1] <= 620, `${c} 的 y 在安全区`)
  }
}

// 3) 含空章节 → 不抛错,空串也得到锚点
{
  const withEmpty = ['第一章', '', '第三章']
  const a = computeAnchors(withEmpty)
  assert.ok(Array.isArray(a['']), '空章节也分配锚点')
}

// 4) 去重保序:重复章节不额外占格
{
  const dup = ['甲', '乙', '甲', '丙']
  const a = computeAnchors(dup)
  assert.equal(Object.keys(a).length, 3, '去重后 3 个章节')
}

// 5) computeLayout 端到端:非 SE 课节点最终 x 分布跨度显著(不竖挤)
{
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
  assert.ok(spread > 300, `非 SE 节点最终 x 应铺开,实测 spread=${spread}`)
}

// 6) renderGraphSvg 冒烟:确定性路径产出合法 SVG 字符串
{
  const svg = renderGraphSvg({
    nodes: [{ id: 'A1', name: 'a', chapter: 'X', difficulty: 3, is_key: false }],
    edges: []
  })
  assert.ok(typeof svg === 'string' && svg.startsWith('<svg'), 'renderGraphSvg 返回 SVG 字符串')
}

// 7) SE 章节 + 一个空 chapter 节点：SE 特例不被空串破坏，手调坐标仍生效
{
  const se = Object.keys(ANCHORS)
  const a = computeAnchors([...se, ''])
  for (const ch of se) {
    assert.deepEqual(a[ch], ANCHORS[ch], `SE+空章节下 ${ch} 仍用手调锚点`)
  }
}

// 8) 未知边类型(应用)不致 NaN：力导向对词典外类型兜底，全部坐标有限
{
  const data = {
    nodes: [
      { id: 'X1', name: 'x', chapter: '甲', difficulty: 3, is_key: false },
      { id: 'X2', name: 'y', chapter: '甲', difficulty: 3, is_key: false }
    ],
    edges: [{ source: 'X1', target: 'X2', type: '应用' }]
  }
  const L = computeLayout(data)
  for (const id of ['X1', 'X2']) {
    assert.ok(Number.isFinite(L.pos[id][0]) && Number.isFinite(L.pos[id][1]), `${id} 坐标应有限(非 NaN)`)
  }
}

// ————— computeLayeredLayout 新增 6 条断言 —————

const layeredSample = {
  nodes: [
    { id: 'N1',   name: '甲节点',   chapter: '甲', difficulty: 3, is_key: false },
    { id: 'N2',   name: '乙节点',   chapter: '乙', difficulty: 3, is_key: true  },
    { id: 'N3',   name: '丙节点',   chapter: '丙', difficulty: 3, is_key: false },
    { id: 'N2-1', name: '乙子概念', chapter: '乙', difficulty: 3, is_key: false }
  ],
  edges: [
    { source: 'N1', target: 'N2',   type: '前置' },  // 跨章 甲→乙
    { source: 'N2', target: 'N3',   type: '前置' },  // 跨章 乙→丙
    { source: 'N2', target: 'N2-1', type: '包含' },  // 子概念
    { source: 'N1', target: 'N3',   type: '相关' },  // 不进 dagre
    { source: 'N1', target: 'N2',   type: '应用' }   // 未知/正式第4类，不进 dagre
  ]
}

// 9) 层序正确：每条前置边满足 pos[source][0] < pos[target][0]（LR 下 source 在左）
{
  const L = computeLayeredLayout(layeredSample)
  const n1x = L.pos['N1'][0], n2x = L.pos['N2'][0], n3x = L.pos['N3'][0]
  assert.ok(n1x < n2x, `前置 N1→N2：N1.x(${n1x}) 应 < N2.x(${n2x})`)
  assert.ok(n2x < n3x, `前置 N2→N3：N2.x(${n2x}) 应 < N3.x(${n3x})`)
}

// 10) 包含子概念不掉队：N2-1.x 不等于全图最小 x
{
  const L = computeLayeredLayout(layeredSample)
  const allXs = Object.values(L.pos).map(p => p[0])
  const minX = Math.min(...allXs)
  assert.ok(L.pos['N2-1'][0] !== minX, `包含子概念 N2-1 不应在最左列(minX=${minX},N2-1.x=${L.pos['N2-1'][0]})`)
}

// 11) 相关/未知不影响定位：去掉相关+应用边，坐标完全相同
{
  const sampleNoExtra = {
    nodes: layeredSample.nodes,
    edges: layeredSample.edges.filter(e => e.type !== '相关' && e.type !== '应用')
  }
  const L1 = computeLayeredLayout(layeredSample)
  const L2 = computeLayeredLayout(sampleNoExtra)
  for (const id of ['N1', 'N2', 'N3', 'N2-1']) {
    assert.deepEqual(L1.pos[id], L2.pos[id], `${id} 坐标不受相关/应用边影响`)
  }
}

// 12) 无 NaN：所有 pos 值与 edgePaths 路由点坐标均有限
{
  const L = computeLayeredLayout(layeredSample)
  for (const [id, p] of Object.entries(L.pos)) {
    assert.ok(Number.isFinite(p[0]) && Number.isFinite(p[1]), `pos[${id}] 坐标应有限`)
  }
  for (const [k, pts] of Object.entries(L.edgePaths)) {
    for (const [x, y] of pts) {
      assert.ok(Number.isFinite(x) && Number.isFinite(y), `edgePaths[${k}] 路由点坐标应有限`)
    }
  }
}

// 13) 标题置顶：每个 chapterLabel.y 小于该章所有节点的最小 pos[id][1]
{
  const L = computeLayeredLayout(layeredSample)
  for (const label of L.chapterLabels) {
    const nodeYs = layeredSample.nodes
      .filter(n => (n.chapter || '') === label.name)
      .map(n => L.pos[n.id][1])
    const minNodeY = Math.min(...nodeYs)
    assert.ok(label.y < minNodeY, `章节标题"${label.name}".y(${label.y}) 应 < 该章最小节点 y(${minNodeY})`)
  }
}

// 14) 回归保护：computeLayout 仍返回原 8 个 key
{
  const L = computeLayout(layeredSample)
  const expected = ['byId', 'hasKids', 'parentOf', 'inEdges', 'pos', 'radius', 'labelPos', 'stars']
  for (const key of expected) {
    assert.ok(key in L, `computeLayout 应仍含 key: ${key}`)
  }
  assert.deepEqual(Object.keys(L).sort(), expected.slice().sort(), 'computeLayout 返回的 key 集合不变')
}

// 15) renderGraphSvg 含前置+包含边：折线路由（<path>）、箭头、虚线
{
  const svg = renderGraphSvg(layeredSample)
  assert.ok(typeof svg === 'string' && svg.startsWith('<svg'), '用例15: 返回以 <svg 开头的字符串')
  assert.ok(svg.includes('<path'), '用例15: 含 <path（dagre 折线路由）')
  assert.ok(svg.includes('marker-end="url(#arrow)"'), '用例15: 含前置箭头 marker-end')
  assert.ok(svg.includes('stroke-dasharray'), '用例15: 含包含虚线 stroke-dasharray')
}

// 16) 章节标题去叠：横向区间重叠的章节标题被分配到不同行（不同 y），避免叠字
{
  const collide = {
    nodes: [
      { id: 'P1', name: '甲一', chapter: '甲章节名称', difficulty: 3, is_key: false },
      { id: 'Q1', name: '乙一', chapter: '乙章节名称', difficulty: 3, is_key: false },
      { id: 'P2', name: '甲二', chapter: '甲章节名称', difficulty: 3, is_key: false },
      { id: 'Q2', name: '乙二', chapter: '乙章节名称', difficulty: 3, is_key: false }
    ],
    edges: []   // 无边 → dagre 同列堆叠 → 两章 x 区间重合，标题中点会撞
  }
  const L = computeLayeredLayout(collide)
  assert.equal(L.chapterLabels.length, 2, '用例16: 两个章节两个标题')
  const [a, b] = L.chapterLabels
  const xClose = Math.abs(a.x - b.x) < (a.name.length + b.name.length) * 7
  assert.ok(xClose, `用例16: 构造的两章标题 x 应接近(a.x=${a.x},b.x=${b.x})`)
  assert.ok(a.y !== b.y, `用例16: 横向重叠的章节标题应分配到不同行(a.y=${a.y},b.y=${b.y})`)
}

// 17) 节点标签避让：labelPos 走贪心避让（含盒 b），标签不压住其它节点圆
{
  const L = computeLayeredLayout(layeredSample)
  const ids = layeredSample.nodes.map(n => n.id)
  const nodeBox = {}
  ids.forEach(id => {
    const p = L.pos[id]; const r = L.radius[id]
    nodeBox[id] = [p[0] - r, p[1] - r, p[0] + r, p[1] + r]
  })
  for (const id of ids) {
    const lp = L.labelPos[id]
    assert.ok(Array.isArray(lp.b) && lp.b.every(Number.isFinite), `用例17: ${id} 标签应带有限避让盒 b`)
    for (const other of ids) {
      if (other === id) continue
      const o = nodeBox[other]; const b = lp.b
      const overlap = b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1]
      assert.ok(!overlap, `用例17: ${id} 的标签不应压住节点 ${other}`)
    }
  }
}

console.log('graphLayout.test.mjs: 全部通过')
