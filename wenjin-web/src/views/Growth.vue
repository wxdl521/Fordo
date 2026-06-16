<template>
  <div class="wj-growth">
    <!-- 加载/错误状态 -->
    <div v-if="loading" class="gr-center">加载中...</div>
    <div v-else-if="error" class="gr-center gr-error">{{ error }}</div>

    <!-- 空状态 -->
    <div v-else-if="!data || !data.curve || data.curve.length === 0" class="gr-center">
      <div class="gr-empty-title">还没有足够的学习记录</div>
      <div class="gr-empty-sub">完成练习后，这里会展示你的成长曲线</div>
    </div>

    <!-- 主内容 -->
    <div v-else class="gr-wrap">
      <!-- 头部 -->
      <div class="gr-header">
        <div class="gr-meta">学生 #{{ DEMO_STUDENT_ID }} · 自 {{ fmtDate(data.startAt) }} 起</div>
        <h1 class="gr-title">你的地图，正在变绿</h1>
        <div class="gr-stats">
          <div class="gr-stat-card">
            <div class="gr-stat-label">整体掌握度</div>
            <div class="gr-stat-value">
              <span class="gr-stat-main gr-stat-accent">{{ summary.overallNow }}%</span>
              <span v-if="summary.overallDelta > 0" class="gr-badge gr-badge-ok">较诊断时 +{{ summary.overallDelta }}</span>
              <span v-else-if="summary.overallDelta < 0" class="gr-badge gr-badge-warn">{{ summary.overallDelta }}</span>
            </div>
          </div>
          <div class="gr-stat-card">
            <div class="gr-stat-label">已掌握节点</div>
            <div class="gr-stat-value">
              <span class="gr-stat-main">{{ summary.masteredNow }}<span class="gr-stat-sub"> / {{ summary.totalNodes }}</span></span>
              <span class="gr-stat-hint">诊断时 {{ summary.masteredThen }}</span>
            </div>
          </div>
          <div class="gr-stat-card">
            <div class="gr-stat-label">待修薄弱点</div>
            <div class="gr-stat-value">
              <span class="gr-stat-main gr-stat-warn">{{ summary.weakNow }}</span>
              <span class="gr-stat-hint">诊断时 {{ summary.weakThen }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 掌握度曲线 -->
      <div class="gr-card gr-curve-card">
        <div class="gr-card-header">
          <span class="gr-section-label">掌握度曲线</span>
          <span class="gr-card-hint">按学习活动记录</span>
        </div>
        <svg viewBox="0 0 720 250" class="gr-svg">
          <!-- 网格线 -->
          <line x1="40" y1="20" x2="680" y2="20" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="40" y1="70" x2="680" y2="70" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="40" y1="120" x2="680" y2="120" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="40" y1="170" x2="680" y2="170" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="40" y1="220" x2="680" y2="220" stroke="var(--line)" stroke-width="1" />

          <!-- Y 轴标签 -->
          <text x="704" y="24" text-anchor="end" font-size="10.5" fill="var(--mut)">100%</text>
          <text x="704" y="74" text-anchor="end" font-size="10.5" fill="var(--mut)">75%</text>
          <text x="704" y="124" text-anchor="end" font-size="10.5" fill="var(--mut)">50%</text>
          <text x="704" y="174" text-anchor="end" font-size="10.5" fill="var(--mut)">25%</text>
          <text x="704" y="224" text-anchor="end" font-size="10.5" fill="var(--mut)">0%</text>

          <!-- 填充区域 -->
          <path v-if="polyPoints" :d="`M ${polyPoints} L ${lastPoint.x} 220 L 40 220 Z`" fill="var(--ok)" opacity="0.08" />

          <!-- 曲线 -->
          <polyline v-if="polyPoints" :points="polyPoints" fill="none" stroke="var(--ok)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />

          <!-- 数据点 -->
          <circle v-for="(pt, idx) in curve" :key="idx" :cx="pt.x" :cy="pt.y" :r="idx === curve.length - 1 ? 5 : 3.5" :fill="idx === curve.length - 1 ? 'var(--acc)' : 'var(--ok)'" />

          <!-- 最后一个点的标签 -->
          <text v-if="lastPoint" :x="lastPoint.x - 12" :y="lastPoint.y - 11" text-anchor="end" font-size="13" font-weight="600" fill="var(--acc)">{{ lastPoint.label }}%</text>

          <!-- X 轴日期标签 -->
          <text v-if="curve.length > 0" :x="curve[0].x" y="242" font-size="11" fill="var(--mut)">{{ fmtDate(data.curve[0].snapshotAt) }}</text>
          <text v-if="curve.length > 1" :x="lastPoint.x" y="242" text-anchor="end" font-size="11" fill="var(--mut)">{{ fmtDate(data.curve[data.curve.length - 1].snapshotAt) }}</text>
        </svg>
        <div class="gr-curve-note">每完成一次练习、看完一份资源，问津都会重估相关节点的掌握度。</div>
      </div>

      <!-- 前后对比 -->
      <div v-if="data.compare" class="gr-card gr-compare-card">
        <div class="gr-card-header">
          <span class="gr-section-label">地图前后对比</span>
          <span v-if="summary.turnedGreen > 0" class="gr-badge gr-badge-ok gr-badge-ml-auto">+{{ summary.turnedGreen }} 个节点转绿</span>
        </div>

        <div class="gr-panels">
          <!-- 基线面板 -->
          <div class="gr-panel">
            <div class="gr-panel-header">
              <span class="gr-panel-title">{{ fmtDate(data.startAt) }} · 诊断时</span>
              <span class="gr-panel-sub">已掌握 {{ summary.masteredThen }} · 薄弱 {{ summary.weakThen }} · 未学 {{ summary.unlearnedThen }}</span>
            </div>
            <div class="gr-panel-rows">
              <div v-for="(panel, idx) in baselinePanels" :key="idx" class="gr-panel-row">
                <span class="gr-panel-chapter">{{ panel.chapter }}</span>
                <div class="gr-dots">
                  <span v-for="(dot, di) in panelDots(panel.nodes)" :key="di" class="gr-dot" :style="{ background: dot }"></span>
                </div>
              </div>
            </div>
          </div>

          <!-- 当前面板 -->
          <div class="gr-panel">
            <div class="gr-panel-header">
              <span class="gr-panel-title">今天</span>
              <span class="gr-panel-sub">已掌握 {{ summary.masteredNow }} · 薄弱 {{ summary.weakNow }} · 未学 {{ summary.unlearnedNow }}</span>
            </div>
            <div class="gr-panel-rows">
              <div v-for="(panel, idx) in latestPanels" :key="idx" class="gr-panel-row">
                <span class="gr-panel-chapter">{{ panel.chapter }}</span>
                <div class="gr-dots">
                  <span v-for="(dot, di) in panelDots(panel.nodes)" :key="di" class="gr-dot" :style="{ background: dot }"></span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="gr-legend">
          <span v-for="(lg, li) in legend" :key="li" class="gr-legend-item">
            <span class="gr-legend-dot" :style="{ background: lg.c }"></span>{{ lg.t }}
          </span>
          <router-link to="/map" class="gr-link-map">在地图上查看</router-link>
        </div>
      </div>

      <!-- 成就时刻 -->
      <div class="gr-footer">
        <div class="gr-footer-title">来路渐明，前路可期</div>
        <div v-if="summary.turnedGreen > 0" class="gr-footer-sub">{{ summary.turnedGreen }} 个节点由琥珀与灰，转为苍绿。</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { fetchGrowth } from '../api/growth.js'

// 常量
const DEMO_STUDENT_ID = 2
const DEMO_COURSE_ID = 1

// 状态
const data = ref(null)
const loading = ref(false)
const error = ref(null)

// 图例
const legend = [
  { c: 'var(--ok)', t: '已掌握' },
  { c: 'var(--warn)', t: '薄弱 · 待修' },
  { c: 'var(--dim)', t: '未学' }
]

// 计算属性：汇总统计
const summary = computed(() => {
  if (!data.value || !data.value.compare || !data.value.compare.summary) {
    return {
      overallThen: 0,
      overallNow: 0,
      overallDelta: 0,
      masteredThen: 0,
      masteredNow: 0,
      weakThen: 0,
      weakNow: 0,
      unlearnedThen: 0,
      unlearnedNow: 0,
      turnedGreen: 0,
      totalNodes: 0
    }
  }
  const s = data.value.compare.summary
  const overallThen = Math.round(parseFloat(s.overallThen || 0))
  const overallNow = Math.round(parseFloat(s.overallNow || 0))
  return {
    overallThen,
    overallNow,
    overallDelta: overallNow - overallThen,
    masteredThen: s.masteredThen || 0,
    masteredNow: s.masteredNow || 0,
    weakThen: s.weakThen || 0,
    weakNow: s.weakNow || 0,
    unlearnedThen: s.unlearnedThen || 0,
    unlearnedNow: s.unlearnedNow || 0,
    turnedGreen: s.turnedGreen || 0,
    totalNodes: (s.masteredNow || 0) + (s.weakNow || 0) + (s.unlearnedNow || 0)
  }
})

// 计算属性：曲线数据转 SVG 坐标
const curve = computed(() => {
  if (!data.value || !data.value.curve || data.value.curve.length === 0) {
    return []
  }
  const points = data.value.curve
  const n = points.length
  const xMin = 40
  const xMax = 680
  const yMin = 20
  const yMax = 220

  return points.map((pt, idx) => {
    const x = xMin + (xMax - xMin) * (idx / Math.max(n - 1, 1))
    const mastery = parseFloat(pt.overallMastery || 0)
    const y = yMax - (yMax - yMin) * (mastery / 100)
    return { x, y }
  })
})

// 计算属性：polyline points 字符串
const polyPoints = computed(() => {
  if (curve.value.length === 0) return ''
  return curve.value.map(pt => `${pt.x},${pt.y}`).join(' ')
})

// 计算属性：最后一个点
const lastPoint = computed(() => {
  if (curve.value.length === 0 || !data.value || !data.value.curve) return null
  const pt = curve.value[curve.value.length - 1]
  const mastery = Math.round(parseFloat(data.value.curve[data.value.curve.length - 1].overallMastery || 0))
  return { x: pt.x, y: pt.y, label: mastery }
})

// 计算属性：baseline 面板
const baselinePanels = computed(() => {
  if (!data.value || !data.value.compare || !data.value.compare.baseline) return []
  return data.value.compare.baseline
})

// 计算属性：latest 面板
const latestPanels = computed(() => {
  if (!data.value || !data.value.compare || !data.value.compare.latest) return []
  return data.value.compare.latest
})

// 方法：将节点列表转为颜色点数组
function panelDots(nodes) {
  if (!nodes) return []
  return nodes.map(node => {
    if (node.level === 2) return 'var(--ok)'
    if (node.level === 1) return 'var(--warn)'
    return 'var(--dim)'
  })
}

// 方法：格式化日期
function fmtDate(dt) {
  if (!dt) return ''
  const d = new Date(dt)
  const m = d.getMonth() + 1
  const day = d.getDate()
  return `${m}月${day}日`
}

// 方法：加载数据
async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await fetchGrowth(DEMO_STUDENT_ID, DEMO_COURSE_ID)
  } catch (e) {
    error.value = e.message || '加载失败'
    console.error('Growth load error:', e)
  } finally {
    loading.value = false
  }
}

// 生命周期
onMounted(() => {
  load()
  // DEV 钩子
  if (typeof window !== 'undefined') {
    window.__wjGrowth = { data, load }
  }
})
</script>

<style scoped>
.wj-growth {
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
  padding: 32px 20px;
  box-sizing: border-box;
}

.gr-center {
  text-align: center;
  padding: 80px 20px;
  color: var(--mut);
}

.gr-error {
  color: var(--warn);
}

.gr-empty-title {
  font-size: 15px;
  margin-bottom: 8px;
}

.gr-empty-sub {
  font-size: 12px;
}

.gr-wrap {
  max-width: 1024px;
  margin: 0 auto;
}

/* 头部 */
.gr-header {
  margin-bottom: 24px;
}

.gr-meta {
  font-size: 12.5px;
  color: var(--mut);
  margin-bottom: 12px;
}

.gr-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 30px;
  font-weight: 600;
  line-height: 1.4;
  margin: 0 0 22px 0;
}

.gr-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}

.gr-stat-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px 18px;
  box-sizing: border-box;
  transition: background-color 0.35s, border-color 0.35s;
}

.gr-stat-label {
  font-size: 11.5px;
  letter-spacing: 2px;
  color: var(--mut);
  margin-bottom: 10px;
}

.gr-stat-value {
  display: flex;
  align-items: baseline;
  gap: 9px;
  flex-wrap: wrap;
}

.gr-stat-main {
  font-size: 28px;
  font-weight: 600;
}

.gr-stat-accent {
  color: var(--acc);
}

.gr-stat-warn {
  color: var(--warn);
}

.gr-stat-sub {
  font-size: 15px;
  color: var(--mut);
  font-weight: 400;
}

.gr-stat-hint {
  font-size: 12px;
  color: var(--mut);
}

.gr-badge {
  font-size: 12px;
  border-radius: 999px;
  padding: 2px 9px;
}

.gr-badge-ok {
  color: var(--ok);
  background: var(--okSoft);
}

.gr-badge-warn {
  color: var(--warn);
  background: var(--warnSoft);
}

.gr-badge-ml-auto {
  margin-left: auto;
}

/* 卡片 */
.gr-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 22px 24px;
  box-sizing: border-box;
  transition: background-color 0.35s, border-color 0.35s;
  margin-bottom: 24px;
}

.gr-card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.gr-section-label {
  font-size: 11.5px;
  letter-spacing: 3px;
  color: var(--mut);
}

.gr-card-hint {
  font-size: 12px;
  color: var(--mut);
  opacity: 0.8;
  margin-left: auto;
}

/* 曲线 */
.gr-svg {
  width: 100%;
  height: auto;
  display: block;
}

.gr-curve-note {
  font-size: 12px;
  color: var(--mut);
  line-height: 1.7;
  margin-top: 10px;
}

/* 对比面板 */
.gr-panels {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 20px;
  margin-bottom: 18px;
}

.gr-panel {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px 18px;
  box-sizing: border-box;
}

.gr-panel-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.gr-panel-title {
  font-size: 13px;
  font-weight: 600;
}

.gr-panel-sub {
  font-size: 11.5px;
  color: var(--mut);
  margin-left: auto;
}

.gr-panel-rows {
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.gr-panel-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.gr-panel-chapter {
  width: 96px;
  flex: none;
  font-size: 12px;
  color: var(--mut);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gr-dots {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.gr-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  transition: background-color 0.35s;
}

/* 图例 */
.gr-legend {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  align-items: center;
}

.gr-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--mut);
}

.gr-legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.gr-link-map {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--mut);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.gr-link-map:hover {
  color: var(--acc);
}

/* 页脚 */
.gr-footer {
  text-align: center;
  padding: 8px 0 44px;
}

.gr-footer-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 17px;
  letter-spacing: 4px;
  margin-bottom: 10px;
}

.gr-footer-sub {
  font-size: 12.5px;
  color: var(--mut);
}

/* 响应式 */
@media (max-width: 720px) {
  .gr-title {
    font-size: 24px;
  }

  .gr-stats {
    grid-template-columns: 1fr;
  }

  .gr-panels {
    grid-template-columns: 1fr;
  }

  .gr-card {
    padding: 18px 16px;
  }
}
</style>
