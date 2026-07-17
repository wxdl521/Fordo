/**
 * Modular ECharts entry for knowledge-graph charts only.
 * Import from this module instead of `echarts` so tree-shaking keeps the
 * graph/force chart surface small. AriaComponent is registered for a11y.
 */
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, AriaComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GraphChart, TooltipComponent, AriaComponent, CanvasRenderer])

/**
 * @param {HTMLElement} el
 * @param {string|object|null} [theme]
 * @param {import('echarts/core').EChartsInitOpts} [opts]
 */
export function initChart(el, theme, opts) {
  return echarts.init(el, theme, opts)
}

export function disposeChart(chart) {
  if (chart && !chart.isDisposed?.()) {
    echarts.dispose(chart)
  }
}

/** Shared default aria block for graph options */
export const graphAriaOption = {
  aria: {
    show: true,
    description: '知识图谱关系图，可用键盘与读屏浏览节点与连线。'
  }
}

export { echarts }
export default echarts
