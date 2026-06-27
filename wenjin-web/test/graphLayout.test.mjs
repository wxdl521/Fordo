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

console.log('graphLayout.test.mjs: 全部通过')
