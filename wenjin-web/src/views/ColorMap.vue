<template>
  <div class="map-page">
    <!-- 顶栏：标题 + 章节筛选 + 图例 -->
    <header class="topbar">
      <div class="title">
        <strong>问津 · 染色地图</strong>
        <span v-if="store.course" class="course">{{ store.course.name }}</span>
        <span class="count" v-if="store.nodes.length">{{ store.nodes.length }} 节点 / {{ store.edges.length }} 边</span>
      </div>

      <div class="filter">
        <label>章节筛选</label>
        <select v-model="selectedChapter" @change="applyVisualState">
          <option value="">全部章节</option>
          <option v-for="c in store.chapters" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>

      <div class="legend">
        <span class="lg"><i class="dot" :style="{ background: 'var(--unlearned)' }"></i>未学</span>
        <span class="lg"><i class="dot" :style="{ background: 'var(--weak)' }"></i>薄弱</span>
        <span class="lg"><i class="dot" :style="{ background: 'var(--mastered)' }"></i>已掌握</span>
        <span class="sep"></span>
        <span class="lg"><i class="dot big"></i>重点</span>
        <span class="lg"><i class="dot small"></i>普通</span>
        <span class="sep"></span>
        <span class="lg"><i class="ln solid"></i>前置</span>
        <span class="lg"><i class="ln dashed"></i>包含</span>
        <span class="lg"><i class="ln faint"></i>相关</span>
      </div>
    </header>

    <!-- 图谱画布 -->
    <div class="canvas-wrap">
      <div ref="chartEl" class="chart"></div>

      <div v-if="store.loading" class="overlay">加载中…</div>
      <div v-else-if="store.error" class="overlay err">
        加载失败：{{ store.error }}
        <button class="retry" @click="reload">重试</button>
      </div>

      <!-- 节点详情抽屉 -->
      <aside class="drawer" :class="{ open: drawerOpen }">
        <template v-if="current">
          <div class="drawer-head">
            <h3>{{ current.name }}</h3>
            <button class="close" @click="closeDrawer">×</button>
          </div>
          <div class="drawer-body">
            <div class="row">
              <span class="k">编码</span><span class="v code">{{ current.nodeCode }}</span>
            </div>
            <div class="row">
              <span class="k">章节</span><span class="v">{{ current.chapter || '—' }}</span>
            </div>
            <div class="row">
              <span class="k">难度</span><span class="v">{{ current.difficulty ?? '—' }} / 5</span>
            </div>
            <div class="row">
              <span class="k">重点</span>
              <span class="v"><span class="tag" :class="{ on: current.isKey }">{{ current.isKey ? '是' : '否' }}</span></span>
            </div>
            <div class="row">
              <span class="k">掌握度</span>
              <span class="v">
                <span class="mdot" :style="{ background: masteryVar(current.mastery) }"></span>
                <span class="tag mastery">{{ masteryText(current) }}</span>
              </span>
            </div>
            <div class="row col">
              <span class="k">描述</span>
              <p class="desc">{{ current.description || '（暂无描述）' }}</p>
            </div>
            <div class="row col">
              <span class="k">前置知识点</span>
              <div class="prereq" v-if="prerequisites.length">
                <button
                  v-for="p in prerequisites"
                  :key="p.nodeCode"
                  class="prereq-btn"
                  @click="focus(p.nodeCode)"
                ><span class="pdot" :style="{ background: masteryVar(p.mastery) }"></span>{{ p.name }}</button>
              </div>
              <p v-else class="desc muted">无（这是一个入门/根知识点）</p>
            </div>
            <div class="drawer-actions">
              <button class="btn-companion" @click="askCompanion">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                </svg>
                向伴侣提问
              </button>
            </div>
          </div>
        </template>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { useGraphStore } from '../store/graph'

// 本阶段不做登录，演示课程写死为 courseId=1（schema.sql 已种入 code=52015CC4B4 的课程）
const DEMO_COURSE_ID = 1
// 演示学生写死为 studentId=2（schema.sql 已种入 id=2 的演示学生账户，role=2 学生）
const DEMO_STUDENT_ID = 2

const store = useGraphStore()
const router = useRouter()
const chartEl = ref(null)
const selectedChapter = ref('')
const selectedNodeCode = ref('')
const drawerOpen = ref(false)

let chart = null
let nodeData = []   // ECharts 节点数据（保持引用以维持力导向布局稳定）
let edgeData = []   // ECharts 边数据
let C = {}          // 从 CSS 变量读取的配色（占位主题的唯一来源）

// 当前抽屉展示的节点（来自 store，含完整字段）
const current = computed(() => store.nodeMap.get(selectedNodeCode.value) || null)
const prerequisites = computed(() =>
  selectedNodeCode.value ? store.prerequisitesOf(selectedNodeCode.value) : []
)

function masteryLabel(m) {
  return m === 'mastered' ? '已掌握' : m === 'weak' ? '薄弱' : '未学'
}
function masteryColor(m) {
  return m === 'mastered' ? C.mastered : m === 'weak' ? C.weak : C.unlearned
}
// 掌握度三态 → CSS 变量色（与图节点配色同源，模板里直接用，避免依赖 C 的读取时序）
function masteryVar(m) {
  return m === 'mastered' ? 'var(--mastered)' : m === 'weak' ? 'var(--weak)' : 'var(--unlearned)'
}
// 抽屉掌握度文案：有分值时显示「薄弱 · 62」，否则仅级别
function masteryText(node) {
  const label = masteryLabel(node.mastery)
  return node.masteryScore == null ? label : `${label} · ${Math.round(node.masteryScore)}`
}

// 读取占位主题配色
function readColors() {
  const s = getComputedStyle(document.documentElement)
  const g = (k) => s.getPropertyValue(k).trim()
  C = {
    mastered: g('--mastered'),
    weak: g('--weak'),
    unlearned: g('--unlearned'),
    accent: g('--accent'),
    line: g('--line'),
    text: g('--text'),
    textMut: g('--text-mut')
  }
}

// 由接口数据构建 ECharts 节点/边数据
function buildData() {
  nodeData = store.nodes.map((n) => ({
    id: n.nodeCode,
    name: n.name,
    chapter: n.chapter,
    // 节点大小按是否重点两档
    symbolSize: n.isKey ? 44 : 26,
    itemStyle: { color: masteryColor(n.mastery), borderColor: C.accent, borderWidth: 0 },
    label: { opacity: 1 }
  }))

  const chapterOf = {}
  store.nodes.forEach((n) => (chapterOf[n.nodeCode] = n.chapter))

  edgeData = store.edges.map((e) => {
    const base = { source: e.source, target: e.target, _type: e.type }
    if (e.type === '前置') {
      // 前置：带箭头实线
      base.symbol = ['none', 'arrow']
      base.symbolSize = 7
      base._opacity = 0.85
      base.lineStyle = { type: 'solid', width: 1.6, color: C.accent, opacity: 0.85, curveness: 0.06 }
    } else if (e.type === '包含') {
      // 包含：虚线
      base.symbol = ['none', 'none']
      base._opacity = 0.6
      base.lineStyle = { type: 'dashed', width: 1.3, color: C.textMut, opacity: 0.6, curveness: 0.06 }
    } else {
      // 相关/应用：低透明度细线
      base.symbol = ['none', 'none']
      base._opacity = 0.22
      base.lineStyle = { type: 'solid', width: 0.8, color: C.textMut, opacity: 0.22, curveness: 0.12 }
    }
    return base
  })

  return { chapterOf }
}

let chapterOf = {}

function renderChart() {
  readColors() // 每次渲染前刷新配色，避免依赖 onMounted 的调用时序（占位主题/HMR 下更稳）
  if (!chart) chart = echarts.init(chartEl.value)
  const built = buildData()
  chapterOf = built.chapterOf

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
        zoom: 0.9,
        force: { repulsion: 300, edgeLength: [70, 180], gravity: 0.06, friction: 0.2 },
        edgeSymbol: ['none', 'none'],
        edgeSymbolSize: 7,
        label: {
          show: true,
          position: 'right',
          color: C.text,
          fontSize: 11,
          formatter: (p) => {
            const s = p.data.name || ''
            return s.length > 11 ? s.slice(0, 11) + '…' : s
          }
        },
        emphasis: { focus: 'adjacency', label: { fontSize: 12 }, lineStyle: { width: 2.4 } },
        data: nodeData,
        links: edgeData
      }
    ]
  }

  chart.setOption(option, { notMerge: true })
  applyVisualState()

  chart.off('click')
  chart.on('click', (params) => {
    if (params.dataType === 'node') focus(params.data.id)
  })
}

// 应用章节筛选（高亮/淡化）与选中描边；复用同一批数据对象，布局保持稳定
function applyVisualState() {
  if (!chart) return
  const ch = selectedChapter.value
  nodeData.forEach((nd) => {
    const inCh = !ch || nd.chapter === ch
    nd.itemStyle.opacity = inCh ? 1 : 0.12
    nd.label = { opacity: inCh ? 1 : 0.1 }
    nd.itemStyle.borderWidth = nd.id === selectedNodeCode.value ? 3 : 0
  })
  edgeData.forEach((ed) => {
    const intra = !ch || (chapterOf[ed.source] === ch && chapterOf[ed.target] === ch)
    ed.lineStyle.opacity = intra ? ed._opacity : 0.04
  })
  chart.setOption({ series: [{ id: 'kg', data: nodeData, links: edgeData }] })
}

// 聚焦某节点：打开抽屉 + 高亮其邻接 + 朱砂描边
function focus(code) {
  selectedNodeCode.value = code
  drawerOpen.value = true
  applyVisualState()
  if (!chart) return
  const idx = nodeData.findIndex((n) => n.id === code)
  if (idx >= 0) {
    chart.dispatchAction({ type: 'downplay', seriesIndex: 0 })
    chart.dispatchAction({ type: 'highlight', seriesIndex: 0, dataIndex: idx })
  }
}

function closeDrawer() {
  drawerOpen.value = false
  selectedNodeCode.value = ''
  applyVisualState()
  if (chart) chart.dispatchAction({ type: 'downplay', seriesIndex: 0 })
}

function askCompanion() {
  router.push(`/companion?nodeCode=${selectedNodeCode.value}`)
}

async function reload() {
  await store.load(DEMO_COURSE_ID, DEMO_STUDENT_ID)
}

function onResize() {
  if (chart) chart.resize()
}

watch(
  () => store.nodes,
  (nodes) => {
    if (nodes && nodes.length) renderChart()
  }
)

onMounted(async () => {
  readColors()
  await store.load(DEMO_COURSE_ID, DEMO_STUDENT_ID)
  window.addEventListener('resize', onResize)
  // 仅开发期暴露给端到端（Playwright）测试用；import.meta.env.DEV 守卫，生产构建会被剔除
  if (import.meta.env.DEV) {
    window.__wj = { store, focus, closeDrawer }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) chart.dispose()
})
</script>

<style scoped>
.map-page {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 顶栏 */
.topbar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 18px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.title { display: flex; align-items: baseline; gap: 12px; }
.title strong { font-size: 16px; }
.title .course { color: var(--text-mut); font-size: 13px; }
.title .count { color: var(--text-mut); font-size: 12px; }

.filter { display: flex; align-items: center; gap: 8px; }
.filter label { font-size: 13px; color: var(--text-mut); }
.filter select {
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 13px;
}

.legend { display: flex; align-items: center; gap: 14px; margin-left: auto; flex-wrap: wrap; }
.legend .lg { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-mut); }
.legend .dot { width: 12px; height: 12px; border-radius: 50%; background: var(--unlearned); display: inline-block; }
.legend .dot.big { width: 16px; height: 16px; background: var(--text-mut); }
.legend .dot.small { width: 9px; height: 9px; background: var(--text-mut); }
.legend .ln { width: 20px; height: 0; display: inline-block; }
.legend .ln.solid { border-top: 2px solid var(--accent); }
.legend .ln.dashed { border-top: 2px dashed var(--text-mut); }
.legend .ln.faint { border-top: 1px solid var(--text-mut); opacity: 0.4; }
.legend .sep { width: 1px; height: 16px; background: var(--line); }

/* 画布 */
.canvas-wrap { position: relative; flex: 1; min-height: 0; }
.chart { width: 100%; height: 100%; }

.overlay {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center; gap: 12px;
  color: var(--text-mut); font-size: 14px;
}
.overlay.err { color: var(--accent); }
.retry {
  background: var(--accent); border: none; color: #fff;
  padding: 6px 14px; border-radius: 8px; cursor: pointer;
}

/* 抽屉 */
.drawer {
  position: absolute; top: 0; right: 0; height: 100%; width: 340px;
  background: var(--panel);
  border-left: 1px solid var(--line);
  transform: translateX(100%);
  transition: transform 0.25s ease;
  display: flex; flex-direction: column;
}
.drawer.open { transform: translateX(0); }
.drawer-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 12px; padding: 16px 16px 12px; border-bottom: 1px solid var(--line);
}
.drawer-head h3 { margin: 0; font-size: 15px; line-height: 1.4; }
.close {
  background: transparent; border: none; color: var(--text-mut);
  font-size: 22px; line-height: 1; cursor: pointer;
}
.drawer-body { padding: 14px 16px; overflow-y: auto; }
.row { display: flex; gap: 10px; padding: 7px 0; font-size: 13px; align-items: baseline; }
.row.col { flex-direction: column; gap: 6px; }
.row .k { color: var(--text-mut); min-width: 64px; flex-shrink: 0; }
.row .v { color: var(--text); }
.row .v.code { font-family: ui-monospace, Consolas, monospace; }
.desc { margin: 0; line-height: 1.6; color: var(--text); font-size: 13px; }
.desc.muted { color: var(--text-mut); }

.tag {
  display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px;
  background: var(--panel-2); color: var(--text-mut); border: 1px solid var(--line);
}
.tag.on { background: rgba(216, 103, 74, 0.16); color: var(--accent); border-color: var(--accent); }
.tag.mastery { background: rgba(107, 114, 128, 0.18); }

.prereq { display: flex; flex-wrap: wrap; gap: 8px; }
.prereq-btn {
  background: var(--panel-2); border: 1px solid var(--line); color: var(--text);
  padding: 5px 10px; border-radius: 8px; font-size: 12px; cursor: pointer;
  display: inline-flex; align-items: center;
}
.prereq-btn:hover { border-color: var(--accent); color: var(--accent); }
.mdot, .pdot {
  width: 9px; height: 9px; border-radius: 50%;
  display: inline-block; vertical-align: middle; margin-right: 6px;
  flex-shrink: 0;
}

.drawer-actions {
  margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--line);
}
.btn-companion {
  width: 100%; height: 40px; background: var(--accent); border: none;
  border-radius: 8px; color: #fff; font-size: 13px; font-weight: 500;
  cursor: pointer; font-family: inherit; display: flex; align-items: center;
  justify-content: center; gap: 8px; transition: opacity 0.2s;
}
.btn-companion:hover { opacity: 0.9; }
.btn-companion svg { flex-shrink: 0; }
</style>
