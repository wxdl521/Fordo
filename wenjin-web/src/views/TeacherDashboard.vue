<template>
  <div class="dashboard-page">
    <!-- 状态条 -->
    <header class="status-bar">
      <div class="status-left">
        <span v-if="dashboard">
          <strong>{{ dashboard.summary.totalStudents }}</strong> 名学生 · 已诊断
          <strong>{{ dashboard.summary.diagnosedStudents }}</strong> · 班级平均掌握率
          <strong>{{ Math.round(dashboard.summary.classAvgRate * 100) }}%</strong>
        </span>
      </div>
      <div class="status-right">
        <span v-if="dashboard">知识图谱 · <strong>{{ dashboard.summary.nodeCount }}</strong> 个节点</span>
      </div>
    </header>

    <!-- 主体：左侧图谱 + 右侧排行 -->
    <div class="main-body">
      <!-- 班级染色地图 -->
      <div class="map-section">
        <div ref="chartEl" class="chart"></div>

        <div v-if="loading" class="overlay">加载中…</div>
        <div v-else-if="error" class="overlay err">{{ error }}</div>

        <!-- 图例 -->
        <div class="legend">
          <div class="legend-item">
            <span class="dot mastered"></span>
            <span>已掌握 (≥72%)</span>
          </div>
          <div class="legend-item">
            <span class="dot weak"></span>
            <span>薄弱 (&lt;72%)</span>
          </div>
          <div class="legend-item">
            <span class="dot unlearned"></span>
            <span>未开课</span>
          </div>
        </div>

        <div class="hint">点击节点或排行项，回溯班级共同薄弱的根因链</div>
      </div>

      <!-- 薄弱排行 -->
      <aside class="ranking-panel">
        <div class="ranking-header">
          <span class="title">共同薄弱排行</span>
          <span class="subtitle">按薄弱人数 · 前 10</span>
        </div>

        <div class="ranking-body">
          <!-- 选中详情 -->
          <div v-if="selectedNode" class="detail-card">
            <div class="detail-header">
              <span class="detail-name">{{ selectedNode.name }}</span>
              <span class="detail-chapter">{{ selectedNode.chapter }}</span>
              <button class="close-btn" @click="clearSelection">×</button>
            </div>
            <div class="detail-stats">
              <div class="stat-bar">
                <div class="bar-seg mastered" :style="{ width: masteredPct }"></div>
                <div class="bar-seg weak" :style="{ width: weakPct }"></div>
              </div>
              <div class="stat-text">
                掌握 <strong>{{ selectedNode.mastered }}</strong> 人 ·
                薄弱 <strong>{{ selectedNode.weak }}</strong> 人 ·
                未诊断 <strong>{{ selectedNode.undiagnosed }}</strong> 人 ·
                掌握率 <strong>{{ selectedRate }}</strong>
              </div>
              <div v-if="rootCauseText" class="root-cause">
                {{ rootCauseText }}
              </div>
            </div>
          </div>

          <!-- 排行列表 -->
          <div
            v-for="(item, index) in dashboard?.weakRanking || []"
            :key="item.nodeCode"
            class="ranking-item"
            :class="{ selected: selectedCode === item.nodeCode }"
            @click="selectNode(item.nodeCode)"
          >
            <div class="item-header">
              <span class="rank" :class="{ top: index < 3 }">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="item-name">{{ item.name }}</span>
              <span class="weak-count">薄弱 {{ item.weak }} 人</span>
            </div>
            <div class="item-bar">
              <div class="bar-seg mastered" :style="{ width: item.masteredPct }"></div>
              <div class="bar-seg weak" :style="{ width: item.weakPct }"></div>
            </div>
            <div class="item-footer">
              <span>{{ item.chapter }}</span>
              <span>掌握率 {{ Math.round(item.rate * 100) }}%</span>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import { fetchDashboard } from '../api/teacher.js'
import { useGraphStore } from '../store/graph.js'

const COURSE_ID = 1

const store = useGraphStore()
const chartEl = ref(null)
const dashboard = ref(null)
const loading = ref(true)
const error = ref('')
const selectedCode = ref(null)

let chart = null
let statByCode = new Map() // nodeCode -> NodeStat

// 构建节点统计映射
function buildStatMap(nodes) {
  statByCode.clear()
  nodes.forEach(node => {
    statByCode.set(node.nodeCode, node)
  })
}

// 当前选中节点的统计数据
const selectedNode = computed(() => {
  if (!selectedCode.value) return null
  return statByCode.get(selectedCode.value) || null
})

const masteredPct = computed(() => {
  if (!selectedNode.value || !dashboard.value) return '0%'
  const total = dashboard.value.summary.totalStudents
  return `${Math.round((selectedNode.value.mastered / total) * 100)}%`
})

const weakPct = computed(() => {
  if (!selectedNode.value || !dashboard.value) return '0%'
  const total = dashboard.value.summary.totalStudents
  return `${Math.round((selectedNode.value.weak / total) * 100)}%`
})

const selectedRate = computed(() => {
  if (!selectedNode.value || selectedNode.value.rate == null) return '—'
  return `${Math.round(selectedNode.value.rate * 100)}%`
})

// 根因回溯逻辑（逆向 BFS）
function backTrace(startCode) {
  const visited = new Set()
  const queue = [startCode]
  const chain = []

  while (queue.length > 0) {
    const code = queue.shift()
    if (visited.has(code)) continue
    visited.add(code)
    chain.push(code)

    // 找所有指向当前节点的前置边（target=code, type='前置'）
    const edges = store.edges.filter(e => e.target === code && e.type === '前置')
    edges.forEach(e => {
      if (!visited.has(e.source)) {
        queue.push(e.source)
      }
    })
  }

  return chain
}

// 根因提示文案
const rootCauseText = computed(() => {
  if (!selectedNode.value || selectedNode.value.weak === 0) return ''

  const chain = backTrace(selectedCode.value)
  if (chain.length <= 1) return ''

  // 找链中薄弱人数最多的前置节点
  let maxWeakNode = null
  let maxWeak = 0

  for (let i = 1; i < chain.length; i++) {
    const stat = statByCode.get(chain[i])
    if (stat && stat.weak > maxWeak) {
      maxWeak = stat.weak
      maxWeakNode = stat
    }
  }

  if (!maxWeakNode || maxWeak === 0) return ''

  return `${selectedNode.value.weak} 名薄弱学生中，前置点「${maxWeakNode.name}」同样有 ${maxWeak} 人薄弱`
})

// 读取 CSS 变量配色
function readColors() {
  const s = getComputedStyle(document.documentElement)
  return {
    mastered: s.getPropertyValue('--mastered').trim(),
    weak: s.getPropertyValue('--weak').trim(),
    unlearned: s.getPropertyValue('--unlearned').trim(),
    accent: s.getPropertyValue('--accent').trim(),
    text: s.getPropertyValue('--text').trim(),
    textMut: s.getPropertyValue('--text-mut').trim()
  }
}

// 渲染 ECharts 图表
function renderChart() {
  if (!dashboard.value || !store.nodes.length) return

  const colors = readColors()

  if (!chart) {
    chart = echarts.init(chartEl.value)
  }

  // 构建节点数据
  const nodeData = store.nodes.map(n => {
    const stat = statByCode.get(n.nodeCode)
    let color = colors.unlearned

    if (stat && stat.rate != null) {
      color = stat.rate >= 0.72 ? colors.mastered : colors.weak
    }

    return {
      id: n.nodeCode,
      name: n.name,
      symbolSize: n.isKey ? 40 : 24,
      itemStyle: {
        color: color,
        borderColor: colors.accent,
        borderWidth: 0
      },
      label: { opacity: 1 }
    }
  })

  // 构建边数据（仅使用前置边）
  const edgeData = store.edges
    .filter(e => e.type === '前置')
    .map(e => ({
      source: e.source,
      target: e.target,
      symbol: ['none', 'arrow'],
      symbolSize: 7,
      lineStyle: {
        type: 'solid',
        width: 1.5,
        color: colors.accent,
        opacity: 0.5,
        curveness: 0.1
      }
    }))

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      formatter: (p) => (p.dataType === 'node' ? p.data.name : '')
    },
    series: [
      {
        id: 'kg',
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        zoom: 0.85,
        force: {
          repulsion: 350,
          edgeLength: [80, 200],
          gravity: 0.08,
          friction: 0.3
        },
        label: {
          show: true,
          position: 'right',
          color: colors.text,
          fontSize: 11,
          formatter: (p) => {
            const s = p.data.name || ''
            return s.length > 10 ? s.slice(0, 10) + '…' : s
          }
        },
        emphasis: {
          focus: 'adjacency',
          label: { fontSize: 12 },
          lineStyle: { width: 2.5 }
        },
        data: nodeData,
        links: edgeData
      }
    ]
  }

  chart.setOption(option, { notMerge: true })
  applySelection()

  chart.off('click')
  chart.on('click', (params) => {
    if (params.dataType === 'node') {
      selectNode(params.data.id)
    }
  })
}

// 应用选中状态
function applySelection() {
  if (!chart || !dashboard.value) return

  const colors = readColors()
  const chain = selectedCode.value ? backTrace(selectedCode.value) : []
  const chainSet = new Set(chain)

  // 更新节点样式
  const series = chart.getOption().series[0]
  series.data.forEach(node => {
    const stat = statByCode.get(node.id)
    const inChain = chainSet.has(node.id)
    const isSelected = node.id === selectedCode.value

    // 计算颜色
    let color = colors.unlearned
    if (stat && stat.rate != null) {
      color = stat.rate >= 0.72 ? colors.mastered : colors.weak
    }

    // 选中节点用强调色
    if (isSelected) {
      color = colors.accent
    }

    node.itemStyle = {
      color: color,
      borderColor: colors.accent,
      borderWidth: isSelected ? 3 : 0,
      opacity: chainSet.size > 0 && !inChain ? 0.2 : 1
    }

    node.label = {
      opacity: chainSet.size > 0 && !inChain ? 0.15 : 1,
      color: inChain && !isSelected ? colors.accent : colors.text
    }
  })

  // 更新边样式
  series.links.forEach(edge => {
    const inChain = chainSet.has(edge.source) && chainSet.has(edge.target)
    edge.lineStyle = {
      ...edge.lineStyle,
      opacity: chainSet.size > 0 ? (inChain ? 0.85 : 0.1) : 0.5,
      width: inChain ? 2 : 1.5,
      color: inChain ? colors.accent : colors.accent
    }
  })

  chart.setOption({ series: [series] })
}

// 选中节点
function selectNode(nodeCode) {
  if (selectedCode.value === nodeCode) {
    clearSelection()
  } else {
    selectedCode.value = nodeCode
    applySelection()
  }
}

// 清除选中
function clearSelection() {
  selectedCode.value = null
  applySelection()
}

// 窗口调整
function onResize() {
  if (chart) chart.resize()
}

// 加载数据
async function loadData() {
  loading.value = true
  error.value = ''

  try {
    // 并行加载图谱结构和聚合数据
    await Promise.all([
      store.load(COURSE_ID),
      fetchDashboard(COURSE_ID).then(data => {
        dashboard.value = data
        buildStatMap(data.nodes)
      })
    ])
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

// 监听数据变化
watch([() => dashboard.value, () => store.nodes], () => {
  if (dashboard.value && store.nodes.length > 0) {
    renderChart()
  }
})

onMounted(async () => {
  await loadData()
  window.addEventListener('resize', onResize)

  // DEV 钩子
  if (import.meta.env.DEV) {
    window.__wjTeacherDashboard = {
      state: () => ({
        dashboard: dashboard.value,
        store: store.$state
      }),
      select: (code) => selectNode(code)
    }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 1180px;
  background: var(--bg);
  color: var(--text);
}

/* 状态条 */
.status-bar {
  height: 48px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  font-size: 13px;
  color: var(--text-mut);
}

.status-bar strong {
  color: var(--text);
  font-weight: 600;
}

/* 主体 */
.main-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 地图区域 */
.map-section {
  flex: 1;
  min-width: 0;
  position: relative;
  overflow: hidden;
}

.chart {
  width: 100%;
  height: 100%;
}

.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-mut);
  font-size: 14px;
  background: var(--bg);
}

.overlay.err {
  color: var(--accent);
}

/* 图例 */
.legend {
  position: absolute;
  left: 16px;
  bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 12px;
  color: var(--text-mut);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: none;
}

.dot.mastered {
  background: var(--mastered);
}

.dot.weak {
  background: var(--weak);
}

.dot.unlearned {
  background: var(--unlearned);
}

.hint {
  position: absolute;
  right: 16px;
  top: 12px;
  font-size: 12px;
  color: var(--text-mut);
  opacity: 0.7;
}

/* 排行面板 */
.ranking-panel {
  width: 400px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-left: 1px solid var(--line);
}

.ranking-header {
  flex: none;
  padding: 16px 20px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
}

.ranking-header .title {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
}

.ranking-header .subtitle {
  font-size: 12px;
  color: var(--text-mut);
}

.ranking-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 选中详情 */
.detail-card {
  background: var(--panel-2);
  border: 2px solid var(--accent);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 6px;
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-name {
  font-size: 14px;
  font-weight: 600;
}

.detail-chapter {
  font-size: 11px;
  color: var(--text-mut);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 2px 8px;
}

.close-btn {
  margin-left: auto;
  background: transparent;
  border: none;
  color: var(--text-mut);
  font-size: 20px;
  cursor: pointer;
  padding: 0;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  color: var(--accent);
}

.detail-stats {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.stat-bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  background: var(--bg);
}

.bar-seg {
  height: 100%;
}

.bar-seg.mastered {
  background: var(--mastered);
}

.bar-seg.weak {
  background: var(--weak);
}

.stat-text {
  font-size: 12px;
  color: var(--text-mut);
}

.stat-text strong {
  color: var(--text);
  font-weight: 600;
}

.root-cause {
  font-size: 12px;
  color: var(--weak);
  background: rgba(224, 163, 62, 0.12);
  border-radius: 6px;
  padding: 10px 12px;
  line-height: 1.6;
}

/* 排行项 */
.ranking-item {
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.ranking-item:hover {
  border-color: var(--text-mut);
}

.ranking-item.selected {
  border-color: var(--accent);
  border-width: 2px;
  padding: 11px 13px;
}

.item-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.rank {
  font-family: 'Courier New', monospace;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-mut);
  flex: none;
  width: 24px;
}

.rank.top {
  color: var(--accent);
}

.item-name {
  font-size: 13px;
  font-weight: 500;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.weak-count {
  font-size: 12px;
  color: var(--weak);
  flex: none;
}

.item-bar {
  display: flex;
  height: 5px;
  border-radius: 3px;
  overflow: hidden;
  background: var(--bg);
  margin-bottom: 8px;
}

.item-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-mut);
}
</style>
