// 教师周报 payload 展示辅助（纯函数，便于单测）。

/** 0–1 → "42%"；非数值返回 "—"。 */
export function pct(v) {
  const n = Number(v)
  return v == null || !Number.isFinite(n) ? '—' : `${Math.round(n * 100)}%`
}

/** 差值 → "↑3" / "↓2" / "持平"；非数值返回 "—"。 */
export function trend(delta) {
  const n = Number(delta)
  if (delta == null || !Number.isFinite(n)) return '—'
  if (n > 0) return `↑${n}`
  if (n < 0) return `↓${-n}`
  return '持平'
}

export function trendClass(delta) {
  const n = Number(delta)
  if (!Number.isFinite(n) || n === 0) return 'flat'
  return n > 0 ? 'up' : 'down'
}

function num(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * engagement → 表格行：作答 / 活跃学生 / 伴侣对话（本周、上周、变化）。
 * 兼容旧 payload（没有 activeStudents* / *Delta 字段时按可得字段计算或留空）。
 */
export function engagementRows(e) {
  if (!e || typeof e !== 'object') return []
  const row = (label, cur, prev, delta) => {
    const c = num(cur)
    const p = num(prev)
    const d = num(delta) ?? (c != null && p != null ? c - p : null)
    return { label, cur: c, prev: p, delta: d }
  }
  return [
    row('作答次数', e.practiceThisWeek, e.practiceLastWeek, e.practiceDelta),
    row('活跃学生', e.activeStudentsThisWeek, e.activeStudentsLastWeek, e.activeStudentsDelta),
    row('伴侣对话', e.dialogueThisWeek, e.dialogueLastWeek, e.dialogueDelta)
  ].filter((r) => r.cur != null || r.prev != null)
}

/** 周报 payload 是否「有效但全空」（有 summary 的新格式且无任何数据）。 */
export function isEmptyWeekly(payload) {
  if (!payload || typeof payload.summary !== 'string') return false
  const noWeak = !Array.isArray(payload.weakNodes) || payload.weakNodes.length === 0
  const noAnomaly = !Array.isArray(payload.anomalies) || payload.anomalies.length === 0
  const noEngagement = engagementRows(payload.engagement).every((r) => !r.cur && !r.prev)
  return noWeak && noAnomaly && noEngagement
}

export function anomalyTypeLabel(type) {
  return ({ EFFECTIVE_DROP: '掌握度下降', NO_PRACTICE: '长期未练习' })[type] ?? type ?? '异常'
}
