/**
 * 管理后台趋势图用的 ECharts 按需入口（折线 + 柱状 + 网格/图例/提示 + Canvas 渲染）。
 * 只由 AdminOverview 通过动态 import 加载，保证 echarts 不进首包；
 * 与 echartsGraph.js 共享同一份 echarts/core。
 */
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, AriaComponent, CanvasRenderer])

/** @param {HTMLElement} el */
export function initTrendChart(el) {
  return echarts.init(el)
}

export function disposeTrendChart(chart) {
  if (chart && !chart.isDisposed?.()) {
    echarts.dispose(chart)
  }
}
