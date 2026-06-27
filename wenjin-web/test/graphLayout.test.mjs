import assert from 'node:assert/strict'
import { computeAnchors, ANCHORS, computeLayout } from '../src/utils/graphLayout.js'
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

// ————— renderGraphSvg 力导向星图渲染（退役 dagre）—————

const sample = {
  nodes: [
    { id: 'N1',   name: '甲节点',   chapter: '甲', difficulty: 3, is_key: false },
    { id: 'N2',   name: '乙节点',   chapter: '乙', difficulty: 3, is_key: true  },
    { id: 'N3',   name: '丙节点',   chapter: '丙', difficulty: 3, is_key: false },
    { id: 'N2-1', name: '乙子概念', chapter: '乙', difficulty: 3, is_key: false }
  ],
  edges: [
    { source: 'N1', target: 'N2',   type: '前置', pending: true },      // 待复核
    { source: 'N2', target: 'N3',   type: '前置', confidence: 85 },     // 已采纳
    { source: 'N2', target: 'N2-1', type: '包含' },                     // 已确认（包含,无箭头）
    { source: 'N1', target: 'N3',   type: '相关' },                     // 已确认（相关）
    { source: 'N3', target: 'N1',   type: '应用' }                      // 已确认（未知类型,验无 NaN）
  ]
}

// 9) 力导向直线边：边以 <line> 渲染,无 dagre 折线 <path fill="none">
{
  const svg = renderGraphSvg(sample)
  assert.ok(svg.includes('<line '), '用例9: 边应以 <line> 直线渲染')
  assert.ok(!svg.includes('fill="none"'), '用例9: 不应残留 dagre 折线路由 fill="none"')
}

// 10) 暖纸底色：背景 rect 填充暖纸色 #f7f3ec
{
  const svg = renderGraphSvg(sample)
  assert.ok(svg.includes('fill="#f7f3ec"'), '用例10: 暖纸底色 #f7f3ec')
}

// 11) 节点辉光halo：含两层半透明同色圆（opacity 0.10 / 0.18）
{
  const svg = renderGraphSvg(sample)
  assert.ok(svg.includes('opacity="0.10"'), '用例11: 外层辉光 halo')
  assert.ok(svg.includes('opacity="0.18"'), '用例11: 内层辉光 halo')
}

// 12) 边按状态上色 + 三色箭头 marker
{
  const svg = renderGraphSvg(sample)
  assert.ok(svg.includes('stroke="#b4422e"'), '用例12: 朱砂（待复核）色存在')
  assert.ok(svg.includes('stroke="#3d7a5e"'), '用例12: 绿（已采纳）色存在')
  assert.ok(svg.includes('stroke="#6f6759"'), '用例12: 灰（已确认）色存在')
  assert.ok(svg.includes('id="arrow-pending"'), '用例12: 待复核箭头 marker')
  assert.ok(svg.includes('id="arrow-adopted"'), '用例12: 已采纳箭头 marker')
  assert.ok(svg.includes('id="arrow-confirm"'), '用例12: 已确认箭头 marker')
}

// 13) 待复核边虚线 + 前置边带状态色箭头 + 非前置边无箭头
{
  const svg = renderGraphSvg(sample)
  const lines = svg.split('\n').filter((l) => l.startsWith('<line'))
  const pendingLine = lines.find((l) => l.includes('stroke="#b4422e"'))
  assert.ok(pendingLine, '用例13: 待复核边存在')
  assert.ok(pendingLine.includes('stroke-dasharray'), '用例13: 待复核边为虚线')
  assert.ok(pendingLine.includes('marker-end="url(#arrow-pending)"'), '用例13: 待复核前置边带朱砂箭头')
  const adoptedLine = lines.find((l) => l.includes('stroke="#3d7a5e"'))
  assert.ok(adoptedLine && adoptedLine.includes('marker-end="url(#arrow-adopted)"'), '用例13: 已采纳前置边带绿箭头')
  const grayLines = lines.filter((l) => l.includes('stroke="#6f6759"'))
  assert.ok(grayLines.some((l) => !l.includes('marker-end')), '用例13: 包含/相关等非前置边无箭头')
}

// 14) 回归保护：computeLayout 仍返回原 8 个 key（graphLayout.js 未受渲染层改动影响）
{
  const L = computeLayout(sample)
  const expected = ['byId', 'hasKids', 'parentOf', 'inEdges', 'pos', 'radius', 'labelPos', 'stars']
  assert.ok(expected.every((k) => k in L), '用例14: computeLayout 仍含 8 key')
  assert.deepEqual(Object.keys(L).sort(), expected.slice().sort(), '用例14: computeLayout key 集合不变')
}

// 15) 章节水印：数据中每个非空章节产出一条 serif font-size=21 水印
{
  const svg = renderGraphSvg(sample)
  const titles = (svg.match(/font-size="21"/g) || []).length
  assert.equal(titles, 3, `用例15: 3 个章节应有 3 条水印,实测 ${titles}`)
}

// 16) 图例：SVG 含 "待复核"/"已采纳"/"已确认" 文案
{
  const svg = renderGraphSvg(sample)
  assert.ok(svg.includes('待复核'), '用例16: 图例含 待复核')
  assert.ok(svg.includes('已采纳'), '用例16: 图例含 已采纳')
  assert.ok(svg.includes('已确认'), '用例16: 图例含 已确认')
}

// 17) 无 NaN：渲染产物不含 NaN 字面量
{
  const svg = renderGraphSvg(sample)
  assert.ok(!svg.includes('NaN'), '用例17: SVG 不含 NaN')
}

// 18) 空图与单节点不抛错且产出合法 SVG
{
  const empty = renderGraphSvg({ nodes: [], edges: [] })
  assert.ok(empty.startsWith('<svg') && empty.includes('</svg>'), '用例18: 空图产出合法 SVG')
  const single = renderGraphSvg({ nodes: [{ id: 'A', name: 'a', chapter: 'X', difficulty: 3, is_key: false }], edges: [] })
  assert.ok(single.startsWith('<svg'), '用例18: 单节点产出合法 SVG')
}

console.log('graphLayout.test.mjs: 全部通过')
