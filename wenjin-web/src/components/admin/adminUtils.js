/** 管理后台各页共用的小工具。 */

export function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

function pad(n) {
  return String(n).padStart(2, '0')
}

/** ISO 时间 → 本地 `YYYY-MM-DD HH:mm`；空值给「—」，无法解析则原样返回。 */
export function formatDateTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 回车提交；输入法组字中的回车（选词）不算。 */
export function onEnter(event, fn) {
  if (event.isComposing || event.keyCode === 229) return
  fn()
}

/** 千分位整数；非数字回退为 0。 */
export function formatCount(value) {
  const n = Number(value)
  return Number.isFinite(n) ? Math.round(n).toLocaleString('en-US') : '0'
}

/** 超长文本截断（超过 max 个字符则末尾加省略号）；完整内容配合 title 悬浮展示。 */
export function truncateText(value, max = 48) {
  const text = value == null ? '' : String(value)
  return text.length > max ? `${text.slice(0, max)}…` : text
}
