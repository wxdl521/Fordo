/**
 * 管理后台总览两张趋势图的 ECharts option 构造（纯函数，不依赖 echarts 本体，便于单测）。
 * 颜色取自当前主题的 CSS 变量，亮 / 暗主题下都可读；主题切换后重新 readChartPalette() 再 setOption。
 */

const FALLBACK = {
  ink: '#2a2520',
  mut: '#6f6759',
  line: '#e5decf',
  card: '#fffdf8',
  acc: '#b4422e',
  ok: '#3d7a5e',
  warn: '#c8862a'
}

/** 从 :root 读取当前主题的图表配色（读不到时用暖纸主题的默认值）。 */
export function readChartPalette(root) {
  const el = root || (typeof document !== 'undefined' ? document.documentElement : null)
  const palette = { ...FALLBACK }
  if (!el || typeof getComputedStyle !== 'function') return palette
  const style = getComputedStyle(el)
  for (const key of Object.keys(FALLBACK)) {
    const value = style.getPropertyValue(`--${key}`).trim()
    if (value) palette[key] = value
  }
  return palette
}

function trimZero(n) {
  return String(Math.round(n * 10) / 10)
}

/** 1200 → 1.2k，1500000 → 1.5M；用于坐标轴刻度。 */
export function compactNumber(value) {
  const n = Number(value) || 0
  const abs = Math.abs(n)
  if (abs >= 1e6) return `${trimZero(n / 1e6)}M`
  if (abs >= 1e3) return `${trimZero(n / 1e3)}k`
  return String(n)
}

function num(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function baseOption(palette, dates) {
  return {
    backgroundColor: 'transparent',
    textStyle: { color: palette.mut },
    grid: { left: 8, right: 16, top: 44, bottom: 8, containLabel: true },
    legend: {
      top: 0,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { color: palette.mut, fontSize: 12 }
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: palette.card,
      borderColor: palette.line,
      textStyle: { color: palette.ink, fontSize: 12 },
      confine: true
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: palette.line } },
      axisTick: { show: false },
      axisLabel: { color: palette.mut, fontSize: 11, hideOverlap: true, formatter: (v) => String(v).slice(5) }
    }
  }
}

function valueAxis(palette, extra = {}) {
  return {
    type: 'value',
    minInterval: 1,
    axisLabel: { color: palette.mut, fontSize: 11, formatter: compactNumber },
    splitLine: { lineStyle: { color: palette.line, type: 'dashed' } },
    ...extra
  }
}

/**
 * 登录 / 活跃趋势：三条折线（登录次数、活跃用户、登录失败）。
 * @param {{ date: string, logins: number, activeUsers: number, failures: number }[]} daily
 */
export function buildActivityOption(daily, palette) {
  const rows = Array.isArray(daily) ? daily : []
  const line = (name, key, color) => ({
    name,
    type: 'line',
    data: rows.map((d) => num(d[key])),
    symbolSize: 6,
    lineStyle: { width: 2, color },
    itemStyle: { color }
  })
  return {
    ...baseOption(palette, rows.map((d) => d.date)),
    aria: { enabled: true, label: { description: `近 ${rows.length} 天登录次数、活跃用户与登录失败趋势折线图。` } },
    yAxis: valueAxis(palette),
    series: [
      line('登录次数', 'logins', palette.warn),
      line('活跃用户', 'activeUsers', palette.ok),
      line('登录失败', 'failures', palette.acc)
    ]
  }
}

/**
 * AI 用量趋势：调用次数（柱，左轴）+ tokens（折线，右轴）。
 * @param {{ date: string, runs: number, tokens: number }[]} daily
 */
export function buildAiOption(daily, palette) {
  const rows = Array.isArray(daily) ? daily : []
  return {
    ...baseOption(palette, rows.map((d) => d.date)),
    aria: { enabled: true, label: { description: `近 ${rows.length} 天 AI 调用次数与 tokens 用量趋势图。` } },
    yAxis: [
      valueAxis(palette),
      valueAxis(palette, { position: 'right', splitLine: { show: false } })
    ],
    series: [
      {
        name: '调用次数',
        type: 'bar',
        yAxisIndex: 0,
        data: rows.map((d) => num(d.runs)),
        barMaxWidth: 28,
        itemStyle: { color: palette.acc, borderRadius: [3, 3, 0, 0] }
      },
      {
        name: 'Tokens',
        type: 'line',
        yAxisIndex: 1,
        data: rows.map((d) => num(d.tokens)),
        symbolSize: 6,
        lineStyle: { width: 2, color: palette.ok },
        itemStyle: { color: palette.ok }
      }
    ]
  }
}
