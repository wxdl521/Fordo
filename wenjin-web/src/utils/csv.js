const NUMERIC = /^[+-]?\d+(\.\d+)?$/
const FORMULA_LEAD = /^[=+\-@\t\r]/

/**
 * 单元格转义：先防 CSV 公式注入（以 = + - @ Tab CR 开头且非纯数字时前缀单引号），
 * 再按 RFC 4180：含逗号、双引号、换行时加引号并把 " 翻倍。
 */
export function escapeCsvCell(value) {
  let text = value == null ? '' : String(value)
  if (FORMULA_LEAD.test(text) && !NUMERIC.test(text)) text = `'${text}`
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/**
 * @param {unknown[]} columns
 * @param {unknown[][]} rows
 * @returns {string}
 */
export function toCsv(columns, rows) {
  const lines = [columns || [], ...(rows || [])].map((row) => row.map(escapeCsvCell).join(','))
  return lines.join('\r\n')
}
