<template>
  <div data-testid="admin-overview" :style="pageStyle">
    <AdminPageHeader title="平台总览" subtitle="账号与课程总量、登录活跃趋势，以及 AI 调用与 tokens 用量。">
      <template #actions>
        <div role="group" aria-label="统计天数" :style="segGroupStyle">
          <button
            v-for="n in DAY_OPTIONS"
            :key="n"
            type="button"
            :data-testid="`admin-overview-days-${n}`"
            :aria-pressed="days === n"
            class="wj-admin-btn"
            :style="dayBtnStyle(days === n)"
            @click="selectDays(n)"
          >近 {{ n }} 天</button>
        </div>
        <button type="button" data-testid="admin-overview-refresh" class="wj-admin-btn" :style="ghostBtnStyle" :disabled="loading" @click="load">刷新</button>
      </template>
    </AdminPageHeader>

    <div v-if="loading" data-testid="admin-overview-loading" role="status" :style="loadingStyle">{{ data ? '更新中…' : '加载中…' }}</div>
    <div v-if="loadError" data-testid="admin-overview-error" role="alert" :style="{ ...errorStyle, display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }">
      <span :style="{ flex: 1, minWidth: 0 }">{{ loadError }}</span>
      <button type="button" data-testid="admin-overview-retry" class="wj-admin-btn" :style="ghostBtnStyle" @click="load">重试</button>
    </div>

    <template v-if="data">
      <!-- 账号与课程 -->
      <section aria-labelledby="admin-overview-h-platform" :style="sectionStyle">
        <h2 id="admin-overview-h-platform" :style="h2Style">账号与课程</h2>
        <div class="wj-ov-cards">
          <AdminStatCard testid="admin-overview-card-users-total" label="用户总数" :value="formatCount(users.total)" :hint="`含管理员 ${formatCount(users.admins)}`" />
          <AdminStatCard testid="admin-overview-card-users-teachers" label="教师" :value="formatCount(users.teachers)" />
          <AdminStatCard testid="admin-overview-card-users-students" label="学生" :value="formatCount(users.students)" />
          <AdminStatCard testid="admin-overview-card-users-disabled" label="已禁用账号" :value="formatCount(users.disabled)" :tone="num(users.disabled) > 0 ? 'warn' : ''" />
          <AdminStatCard testid="admin-overview-card-courses-total" label="课程总数" :value="formatCount(courses.total)" />
          <AdminStatCard testid="admin-overview-card-courses-published" label="已发布课程" :value="formatCount(courses.published)" tone="ok" />
          <AdminStatCard testid="admin-overview-card-courses-draft" label="草稿课程" :value="formatCount(courses.draft)" />
          <AdminStatCard testid="admin-overview-card-courses-archived" label="已归档课程" :value="formatCount(courses.archived)" />
        </div>
      </section>

      <!-- 登录与活跃 -->
      <section aria-labelledby="admin-overview-h-activity" :style="sectionStyle">
        <h2 id="admin-overview-h-activity" :style="h2Style">登录与活跃<span :style="h2SubStyle">近 {{ shownDays }} 天</span></h2>
        <div class="wj-ov-cards">
          <AdminStatCard testid="admin-overview-card-today-active" label="今日活跃用户" :value="formatCount(activity.todayActiveUsers)" tone="ok" />
          <AdminStatCard testid="admin-overview-card-today-failures" label="今日登录失败" :value="formatCount(activity.todayLoginFailures)" :tone="num(activity.todayLoginFailures) > 0 ? 'acc' : ''" />
        </div>
        <div :style="chartCardStyle">
          <div
            ref="activityEl"
            data-testid="admin-overview-chart-activity"
            role="img"
            :aria-label="`近 ${shownDays} 天登录次数、活跃用户与登录失败趋势图，明细见下方数据表`"
            :style="chartBoxStyle"
          ></div>
          <details :style="detailsStyle">
            <summary :style="summaryStyle">查看明细</summary>
            <div :style="detailScrollStyle">
              <table data-testid="admin-overview-activity-table" :style="{ ...tableStyle, minWidth: '360px' }">
                <thead>
                  <tr>
                    <th scope="col" :style="thStyle()">日期</th>
                    <th scope="col" :style="numThStyle">登录次数</th>
                    <th scope="col" :style="numThStyle">活跃用户</th>
                    <th scope="col" :style="numThStyle">登录失败</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in activityDaily" :key="row.date" data-testid="admin-overview-activity-row">
                    <td :style="tdStyle">{{ row.date }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.logins) }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.activeUsers) }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.failures) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
        </div>
      </section>

      <!-- AI 用量 -->
      <section aria-labelledby="admin-overview-h-ai" :style="sectionStyle">
        <h2 id="admin-overview-h-ai" :style="h2Style">AI 用量<span :style="h2SubStyle">近 {{ shownDays }} 天</span></h2>
        <div class="wj-ov-cards">
          <AdminStatCard testid="admin-overview-card-ai-runs" label="AI 运行次数（伴侣+教师助教等）" :value="formatCount(ai.runs)" />
          <AdminStatCard testid="admin-overview-card-ai-failed" label="失败次数" :value="formatCount(ai.failedRuns)" :tone="num(ai.failedRuns) > 0 ? 'acc' : ''" />
          <AdminStatCard testid="admin-overview-card-ai-tokens" label="总 tokens" :value="formatCount(aiTokensTotal)" :hint="`输入 ${formatCount(ai.tokensIn)} · 输出 ${formatCount(ai.tokensOut)}`" />
          <AdminStatCard testid="admin-overview-card-ai-avg" label="平均耗时" :value="avgDurationText" />
        </div>
        <div :style="chartCardStyle">
          <div
            ref="aiEl"
            data-testid="admin-overview-chart-ai"
            role="img"
            :aria-label="`近 ${shownDays} 天 AI 调用次数与 tokens 用量趋势图，明细见下方数据表`"
            :style="chartBoxStyle"
          ></div>
          <details :style="detailsStyle">
            <summary :style="summaryStyle">查看明细</summary>
            <div :style="detailScrollStyle">
              <table data-testid="admin-overview-ai-daily-table" :style="{ ...tableStyle, minWidth: '300px' }">
                <thead>
                  <tr>
                    <th scope="col" :style="thStyle()">日期</th>
                    <th scope="col" :style="numThStyle">调用次数</th>
                    <th scope="col" :style="numThStyle">Tokens</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in aiDaily" :key="row.date" data-testid="admin-overview-ai-daily-row">
                    <td :style="tdStyle">{{ row.date }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.runs) }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.tokens) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
        </div>
        <div v-if="chartError" data-testid="admin-overview-chart-error" role="alert" :style="errorStyle">{{ chartError }}</div>

        <div :style="tablesGridStyle">
          <div>
            <h3 :style="h3Style">按调用类型</h3>
            <AdminTableCard testid-prefix="admin-overview-ai-kind" :empty="aiByKind.length === 0" empty-text="暂无 AI 调用记录。">
              <table data-testid="admin-overview-ai-kind-table" :style="tableStyle">
                <thead>
                  <tr>
                    <th scope="col" :style="thStyle()">类型</th>
                    <th scope="col" :style="numThStyle">调用次数</th>
                    <th scope="col" :style="numThStyle">Tokens</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in aiByKind" :key="row.kind" data-testid="admin-overview-ai-kind-row" class="wj-admin-row">
                    <td :style="{ ...tdStyle, overflowWrap: 'anywhere' }">{{ row.kind || '—' }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.runs) }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.tokens) }}</td>
                  </tr>
                </tbody>
              </table>
            </AdminTableCard>
          </div>
          <div>
            <h3 :style="h3Style">按模型</h3>
            <AdminTableCard testid-prefix="admin-overview-ai-model" :empty="aiByModel.length === 0" empty-text="暂无 AI 调用记录。">
              <table data-testid="admin-overview-ai-model-table" :style="tableStyle">
                <thead>
                  <tr>
                    <th scope="col" :style="thStyle()">模型</th>
                    <th scope="col" :style="numThStyle">调用次数</th>
                    <th scope="col" :style="numThStyle">Tokens</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in aiByModel" :key="row.model" data-testid="admin-overview-ai-model-row" class="wj-admin-row">
                    <td :style="{ ...tdStyle, overflowWrap: 'anywhere' }">{{ row.model || '—' }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.runs) }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.tokens) }}</td>
                  </tr>
                </tbody>
              </table>
            </AdminTableCard>
          </div>
          <div>
            <h3 :style="h3Style">教师侧 AI 作业</h3>
            <div data-testid="admin-overview-jobs-summary" :style="jobsSummaryStyle">
              共 {{ formatCount(jobs.total) }} 个 · 成功 {{ formatCount(jobs.succeeded) }} · 失败
              <span :style="num(jobs.failed) > 0 ? { color: 'var(--acc)', fontWeight: 600 } : null">{{ formatCount(jobs.failed) }}</span>
            </div>
            <AdminTableCard testid-prefix="admin-overview-jobs" :empty="jobsByKind.length === 0" empty-text="暂无 AI 作业记录。">
              <table data-testid="admin-overview-jobs-table" :style="tableStyle">
                <thead>
                  <tr>
                    <th scope="col" :style="thStyle()">作业类型</th>
                    <th scope="col" :style="numThStyle">次数</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in jobsByKind" :key="row.kind" data-testid="admin-overview-jobs-row" class="wj-admin-row">
                    <td :style="{ ...tdStyle, overflowWrap: 'anywhere' }">{{ row.kind || '—' }}</td>
                    <td :style="numTdStyle">{{ formatCount(row.count) }}</td>
                  </tr>
                </tbody>
              </table>
            </AdminTableCard>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import AdminStatCard from '../components/admin/AdminStatCard.vue'
import AdminTableCard from '../components/admin/AdminTableCard.vue'
import {
  errorStyle,
  ghostBtnStyle,
  pageStyle,
  primaryBtnStyle,
  tableStyle,
  tdStyle,
  thStyle
} from '../components/admin/adminStyle.js'
import { formatCount, isAbortError } from '../components/admin/adminUtils.js'
import { getOverview } from '../api/systemAdmin.js'
import { useTheme } from '../composables/useTheme.js'
import { buildActivityOption, buildAiOption, readChartPalette } from '../utils/adminTrendOptions.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const DAY_OPTIONS = [7, 14, 30]

const { theme } = useTheme()

const days = ref(7)
const shownDays = ref(7) // 当前展示数据对应的天数（切换失败时不让标题与数据错位）
const data = ref(null)
const loading = ref(false)
const loadError = ref('')
const chartError = ref('')
const latest = createLatestRequest()

const activityEl = ref(null)
const aiEl = ref(null)
const charts = { activity: null, ai: null }
let trend = null // 动态加载的 echartsTrend 模块（首次出数据后才加载，不进首包）
let disposed = false

function num(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function asArray(value) {
  return Array.isArray(value) ? value : []
}

const users = computed(() => data.value?.users || {})
const courses = computed(() => data.value?.courses || {})
const activity = computed(() => data.value?.activity || {})
const ai = computed(() => data.value?.ai || {})
const jobs = computed(() => ai.value.jobs || {})
const activityDaily = computed(() => asArray(activity.value.daily))
const aiDaily = computed(() => asArray(ai.value.daily))
const aiByKind = computed(() => asArray(ai.value.byKind))
const aiByModel = computed(() => asArray(ai.value.byModel))
const jobsByKind = computed(() => asArray(jobs.value.byKind))
const aiTokensTotal = computed(() => num(ai.value.tokensIn) + num(ai.value.tokensOut))

const avgDurationText = computed(() => {
  const ms = num(ai.value.avgDurationMs)
  if (num(ai.value.runs) === 0 || ms <= 0) return '—'
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} 秒`
})

// ── 加载 ──
async function load() {
  if (disposed) return
  const request = latest.next()
  const requestedDays = days.value
  loading.value = true
  loadError.value = ''
  try {
    const res = await getOverview({ days: requestedDays }, { signal: request.signal })
    if (!request.isCurrent()) return
    data.value = res || {}
    shownDays.value = requestedDays
    void renderCharts()
  } catch (err) {
    if (!request.isCurrent() || isAbortError(err)) return
    loadError.value = err?.message || '加载平台总览失败'
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

function selectDays(n) {
  if (n === days.value) return
  days.value = n
  void load()
}

// ── 图表 ──
function draw(key, el, option) {
  if (!el) return
  let chart = charts[key]
  if (chart && typeof chart.getDom === 'function' && chart.getDom() !== el) {
    trend.disposeTrendChart(chart)
    chart = null
  }
  if (!chart) {
    chart = trend.initTrendChart(el)
    charts[key] = chart
  }
  chart.setOption(option, { notMerge: true })
}

async function renderCharts() {
  await nextTick() // 等数据区块（含图表容器）渲染出来
  if (disposed || !data.value) return
  try {
    if (!trend) trend = await import('../utils/echartsTrend.js')
    if (disposed || !data.value) return
    const palette = readChartPalette()
    draw('activity', activityEl.value, buildActivityOption(activityDaily.value, palette))
    draw('ai', aiEl.value, buildAiOption(aiDaily.value, palette))
    chartError.value = ''
  } catch {
    chartError.value = '趋势图加载失败，可展开图下方的「查看明细」查看数据。'
  }
}

function disposeCharts() {
  for (const key of Object.keys(charts)) {
    if (charts[key] && trend) trend.disposeTrendChart(charts[key])
    charts[key] = null
  }
}

function onResize() {
  for (const chart of Object.values(charts)) chart?.resize?.()
}

// 主题切换后按新主题的 CSS 变量重绘
watch(theme, () => {
  void renderCharts()
})

onMounted(() => {
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  disposed = true
  latest.cancel()
  window.removeEventListener('resize', onResize)
  disposeCharts()
})

void load()

// ── 样式（通用部分见 components/admin/adminStyle.js） ──
const segGroupStyle = {
  display: 'inline-flex',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  overflow: 'hidden'
}

function dayBtnStyle(active) {
  return {
    ...(active ? primaryBtnStyle : ghostBtnStyle),
    border: 'none',
    borderRadius: 0,
    padding: '0 14px'
  }
}

const loadingStyle = {
  fontSize: '12.5px',
  color: 'var(--mut)',
  margin: '0 0 12px'
}

const sectionStyle = {
  marginBottom: '28px'
}

const h2Style = {
  margin: '0 0 12px',
  fontFamily: "'Noto Serif SC', serif",
  fontSize: '16px',
  fontWeight: 600,
  letterSpacing: '1px'
}

const h2SubStyle = {
  marginLeft: '10px',
  fontFamily: 'inherit',
  fontSize: '12px',
  fontWeight: 400,
  letterSpacing: 0,
  color: 'var(--mut)'
}

const h3Style = {
  margin: '0 0 8px',
  fontSize: '13px',
  fontWeight: 600,
  color: 'var(--ink)'
}

const chartCardStyle = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  padding: '14px 16px 10px',
  marginBottom: '14px',
  transition: 'background-color 0.35s, border-color 0.35s'
}

const chartBoxStyle = {
  width: '100%',
  height: '260px'
}

const detailsStyle = {
  marginTop: '6px'
}

const summaryStyle = {
  fontSize: '12.5px',
  color: 'var(--mut)',
  cursor: 'pointer',
  padding: '4px 0'
}

const detailScrollStyle = {
  overflowX: 'auto',
  marginTop: '6px'
}

const tablesGridStyle = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
  gap: '16px',
  alignItems: 'start'
}

const jobsSummaryStyle = {
  fontSize: '12.5px',
  color: 'var(--mut)',
  margin: '0 0 8px'
}

const numThStyle = { ...thStyle(), textAlign: 'right' }
const numTdStyle = { ...tdStyle, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }
</script>

<style scoped>
/* 数字卡：宽屏一行 4 张，窄屏 2 张 */
.wj-ov-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
@media (max-width: 720px) {
  .wj-ov-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
