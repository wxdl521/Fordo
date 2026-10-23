<template>
  <div class="reports-page">
    <header class="toolbar">
      <div class="left">
        <span class="title">周报 / 抽查</span>
        <span v-if="courseId" class="meta">课程 #{{ courseId }}</span>
      </div>
      <div class="right">
        <label class="field">
          <span>类型</span>
          <select v-model="kindFilter" @change="reload">
            <option value="">全部</option>
            <option value="0">教师周报</option>
            <option value="1">督导抽查</option>
          </select>
        </label>
        <button class="btn" :disabled="loading || !courseId" @click="reload">刷新</button>
      </div>
    </header>

    <div v-if="!courseId" class="empty">请先在「图谱审核」选择课程</div>
    <div v-else-if="loading" class="empty">加载中…</div>
    <div v-else-if="flagOff" class="empty err" role="alert">功能未开启</div>
    <div v-else-if="error" class="empty err" role="alert">{{ error }}</div>
    <div v-else class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th></th>
            <th>ID</th>
            <th>类型</th>
            <th>周期</th>
            <th>状态</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in items" :key="row.id">
            <tr class="run-row" @click="toggle(row.id)">
              <td class="exp">{{ expandedId === row.id ? '▼' : '▶' }}</td>
              <td>{{ row.id }}</td>
              <td>{{ kindLabel(row.kind) }}</td>
              <td class="muted">{{ row.periodStart || '—' }} ~ {{ row.periodEnd || '—' }}</td>
              <td>{{ statusLabel(row.status) }}</td>
              <td class="muted">{{ row.createdAt || '—' }}</td>
            </tr>
            <tr v-if="expandedId === row.id" class="detail-row">
              <td colspan="6">
                <div class="payload">
                  <template v-if="row.payload">
                    <p v-if="row.payload.summary" class="summary" data-testid="report-summary">
                      {{ row.payload.summary }}
                    </p>
                    <div v-if="isEmptyWeekly(row.payload)" class="muted" data-testid="report-empty">
                      本周期暂无学习数据（没有薄弱节点、异常或作答活动）
                    </div>
                    <section v-if="weakNodes(row).length" class="block">
                      <h4>薄弱节点</h4>
                      <table class="mini-table" data-testid="weak-table">
                        <thead>
                          <tr><th>节点</th><th>平均掌握度</th><th>样本</th></tr>
                        </thead>
                        <tbody>
                          <tr v-for="(n, i) in weakNodes(row)" :key="i">
                            <td>
                              {{ n.name || n.nodeCode || n.code || '—' }}
                              <span v-if="n.name && (n.nodeCode || n.code)" class="muted"> · {{ n.nodeCode || n.code }}</span>
                            </td>
                            <td>{{ n.avgEffective != null ? pct(n.avgEffective) : (n.score ?? '—') }}</td>
                            <td>{{ n.sampleSize != null ? n.sampleSize + ' 人' : '—' }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </section>
                    <section v-if="anomalies(row).length" class="block">
                      <h4>
                        异常
                        <span v-if="row.payload.anomalyTotal > anomalies(row).length" class="muted">
                          （共 {{ row.payload.anomalyTotal }} 项，仅显示前 {{ anomalies(row).length }} 项）
                        </span>
                      </h4>
                      <table class="mini-table" data-testid="anomaly-table">
                        <thead>
                          <tr><th>学生</th><th>类型</th><th>说明</th></tr>
                        </thead>
                        <tbody>
                          <tr v-for="(a, i) in anomalies(row)" :key="i">
                            <td>{{ a.studentName || (a.studentId != null ? '#' + a.studentId : '—') }}</td>
                            <td>{{ anomalyTypeLabel(a.type || a.kind) }}</td>
                            <td>{{ a.reason || a.detail || a.message || '—' }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </section>
                    <section v-if="engagementRows(row.payload.engagement).length" class="block">
                      <h4>参与度（本周 vs 上周）</h4>
                      <table class="mini-table" data-testid="engagement-table">
                        <thead>
                          <tr><th>指标</th><th>本周</th><th>上周</th><th>变化</th></tr>
                        </thead>
                        <tbody>
                          <tr v-for="r in engagementRows(row.payload.engagement)" :key="r.label">
                            <td>{{ r.label }}</td>
                            <td>{{ r.cur ?? '—' }}</td>
                            <td>{{ r.prev ?? '—' }}</td>
                            <td :class="['delta', trendClass(r.delta)]">{{ trend(r.delta) }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </section>
                    <section v-if="flags(row).length" class="block">
                      <h4>Flags</h4>
                      <ul>
                        <li v-for="(f, i) in flags(row)" :key="i">
                          <template v-if="flagRunId(f)">
                            <router-link class="link" :to="{ path: '/teacher/agent-runs', query: { runId: flagRunId(f) } }">
                              run #{{ flagRunId(f) }}
                            </router-link>
                            <span v-if="flagLabel(f)" class="muted"> · {{ flagLabel(f) }}</span>
                          </template>
                          <span v-else>{{ typeof f === 'string' ? f : pretty(f) }}</span>
                        </li>
                      </ul>
                    </section>
                    <section v-if="row.payload.narrative" class="block">
                      <h4>AI 叙述</h4>
                      <p class="narrative">{{ row.payload.narrative }}</p>
                    </section>
                    <section v-if="!hasKnownSections(row)" class="block">
                      <h4>原始 payload</h4>
                      <pre>{{ pretty(row.payload) }}</pre>
                    </section>
                  </template>
                  <div v-else class="muted">暂无 payload（可能仍在生成）</div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!items.length">
            <td colspan="6" class="empty-row">暂无报告</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { fetchTeacherReports } from '../api/teacher.js'
import { useTeacherCourse } from '../composables/useTeacherCourse.js'
import { createLatestRequest } from '../utils/latestRequest.js'
import { anomalyTypeLabel, engagementRows, isEmptyWeekly, pct, trend, trendClass } from '../utils/weeklyReport.js'

const { courseId } = useTeacherCourse()

const items = ref([])
const loading = ref(false)
const error = ref('')
const flagOff = ref(false)
const kindFilter = ref('')
const expandedId = ref(null)

const listRequest = createLatestRequest()
let disposed = false

function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

function isNotFound(err) {
  return err?.httpStatus === 404 || err?.code === 404
}

function kindLabel(k) {
  return ({ 0: '教师周报', 1: '督导抽查' })[k] ?? String(k)
}
function statusLabel(s) {
  return ({ 0: '生成中', 1: '就绪', 2: '失败' })[s] ?? String(s)
}
function pretty(v) {
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}
function weakNodes(row) {
  const w = row?.payload?.weakNodes
  return Array.isArray(w) ? w : []
}
function anomalies(row) {
  const a = row?.payload?.anomalies
  return Array.isArray(a) ? a : []
}
function flags(row) {
  const f = row?.payload?.flags
  return Array.isArray(f) ? f : []
}
function flagRunId(f) {
  if (f == null) return null
  if (typeof f === 'number') return f
  return f.runId || f.agentRunId || f.id || null
}
function flagLabel(f) {
  if (f == null || typeof f === 'string' || typeof f === 'number') return ''
  return f.reason || f.label || f.code || ''
}
function hasKnownSections(row) {
  return weakNodes(row).length
    || anomalies(row).length
    || engagementRows(row?.payload?.engagement).length > 0
    || !!row?.payload?.summary
    || flags(row).length
    || !!row?.payload?.narrative
}

async function reload() {
  if (!courseId.value) return
  error.value = ''
  flagOff.value = false
  loading.value = true
  expandedId.value = null
  const request = listRequest.next()
  try {
    const kind = kindFilter.value === '' ? undefined : Number(kindFilter.value)
    const data = await fetchTeacherReports(
      courseId.value,
      Number.isFinite(kind) ? kind : undefined,
      { signal: request.signal }
    )
    if (!request.isCurrent() || disposed) return
    items.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (isAbortError(e) || !request.isCurrent() || disposed) return
    if (isNotFound(e)) {
      flagOff.value = true
      error.value = ''
      items.value = []
    } else {
      error.value = e?.response?.data?.message || e?.message || '加载失败'
      items.value = []
    }
  } finally {
    if (request.isCurrent() && !disposed) loading.value = false
  }
}

function toggle(id) {
  expandedId.value = expandedId.value === id ? null : id
}

watch(courseId, () => reload(), { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  listRequest.cancel()
})
</script>

<style scoped>
.reports-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: var(--bg);
  color: var(--text);
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--line);
  flex: none;
}
.left { display: flex; align-items: baseline; gap: 12px; }
.title { font-weight: 600; font-size: 15px; }
.meta { font-size: 12px; color: var(--mut); }
.right { display: flex; align-items: center; gap: 10px; }
.field {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--mut);
}
.field select {
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--bg);
  color: var(--text);
  font-size: 12px;
}
.btn {
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid var(--line);
  background: var(--acc);
  color: #fffdf8;
  cursor: pointer;
}
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.empty, .empty-row {
  padding: 40px 20px;
  text-align: center;
  color: var(--mut);
  font-size: 13px;
}
.empty.err { color: #c44; }
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 20px 24px;
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.data-table th, .data-table td {
  border-bottom: 1px solid var(--line);
  padding: 8px 6px;
  text-align: left;
  vertical-align: top;
}
.data-table th {
  color: var(--mut);
  font-weight: 500;
  position: sticky;
  top: 0;
  background: var(--bg);
}
.run-row { cursor: pointer; }
.run-row:hover { background: color-mix(in srgb, var(--acc) 6%, transparent); }
.exp { width: 20px; color: var(--mut); }
.muted { color: var(--mut); }
.detail-row td {
  background: color-mix(in srgb, var(--line) 35%, transparent);
  padding: 12px 14px;
}
.payload { display: flex; flex-direction: column; gap: 12px; }
.block h4 {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.block ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.55;
}
.block pre {
  margin: 0;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 11px;
  padding: 8px;
  background: var(--bg);
  border-radius: 6px;
}
.summary {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink);
}
.mini-table {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 320px;
}
.mini-table th, .mini-table td {
  border-bottom: 1px solid var(--line);
  padding: 4px 12px 4px 0;
  text-align: left;
}
.mini-table th { color: var(--mut); font-weight: 500; }
.delta.up { color: #2f7d4f; }
.delta.down { color: #c44; }
.delta.flat { color: var(--mut); }
.narrative {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink);
}
.link {
  color: var(--acc);
  text-decoration: none;
}
.link:hover { text-decoration: underline; }
</style>
