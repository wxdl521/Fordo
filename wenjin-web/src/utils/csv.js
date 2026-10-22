/** RFC 4180 单元格转义：含逗号、双引号、换行时加引号并把 " 翻倍。 */
export function escapeCsvCell(value) {
  const text = value == null ? '' : String(value)
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
