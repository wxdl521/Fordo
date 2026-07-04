/**
 * 自适应诊断三色进度环的弧段计算（纯函数，供 SVG 渲染 + node 单测）。
 *
 * 三色语义（对齐 §2 裁决 6）：实测 tested / 推断 inferred（不区分 pass/fail）/ 未知 unknown。
 * 每段弧长按各自计数占三者之和的比例分配；返回结果只做几何，颜色由视图层映射。
 *
 * @param {{ tested?: number, inferred?: number, unknown?: number }} progress
 * @param {number} circumference 环周长 = 2πr
 * @returns {Array<{ key: 'tested'|'inferred'|'unknown', length: number, offset: number }>}
 *   length = 该段弧长；offset = 该段起点距环起点（顶部）的累计弧长（顺时针）。
 *   计数为 0 的段被跳过；三者之和 ≤ 0 时返回空数组（视图只画底环）。
 */
export function ringSegments(progress, circumference) {
  const tested = Math.max(0, Number(progress?.tested) || 0)
  const inferred = Math.max(0, Number(progress?.inferred) || 0)
  const unknown = Math.max(0, Number(progress?.unknown) || 0)
  const total = tested + inferred + unknown
  if (total <= 0 || !(circumference > 0)) return []

  const parts = [
    { key: 'tested', count: tested },
    { key: 'inferred', count: inferred },
    { key: 'unknown', count: unknown }
  ]

  const segs = []
  let offset = 0
  for (const p of parts) {
    if (p.count <= 0) continue
    const length = (p.count / total) * circumference
    segs.push({ key: p.key, length, offset })
    offset += length
  }
  return segs
}
