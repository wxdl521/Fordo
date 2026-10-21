/**
 * 未诊断时地图全是灰。导览期间给一份确定性的示例染色，
 * 不写回服务器；诊断后用真实 mastery 覆盖。
 */

const LEVELS = [
  { mastery: 'mastered', masteryScore: 88 },
  { mastery: 'mastered', masteryScore: 80 },
  { mastery: 'weak', masteryScore: 52 },
  { mastery: 'unlearned', masteryScore: null },
  { mastery: 'unlearned', masteryScore: null },
]

function hashId(id) {
  const s = String(id || '')
  let h = 0
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) | 0
  return Math.abs(h)
}

export function needsTemplateMastery(nodes) {
  if (!Array.isArray(nodes) || nodes.length === 0) return false
  return nodes.every((n) => !n.mastery || n.mastery === 'unlearned')
}

export function applyTemplateMastery(nodes) {
  if (!Array.isArray(nodes)) return nodes
  return nodes.map((n) => {
    const lv = LEVELS[hashId(n.id) % LEVELS.length]
    return {
      ...n,
      mastery: lv.mastery,
      masteryScore: lv.masteryScore,
      decaying: false,
    }
  })
}

export function withTemplateGraph(data) {
  if (!data || !Array.isArray(data.nodes)) return data
  if (!needsTemplateMastery(data.nodes)) return data
  return {
    ...data,
    nodes: applyTemplateMastery(data.nodes),
    template: true,
  }
}
