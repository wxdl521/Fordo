<template>
  <div class="trace-page">
    <header class="toolbar">
      <div class="left">
        <span class="title">Agent 轨迹</span>
        <span v-if="courseId" class="meta">课程 #{{ courseId }}</span>
      </div>
      <div class="right">
        <label class="field">
          <span>学生 ID</span>
          <input v-model="studentIdInput" type="number" min="1" placeholder="可选" @keyup.enter="reload" />
        </label>
        <button class="btn" :disabled="loading || !courseId" @click="reload">刷新</button>
      </div>
    </header>

    <div v-if="!courseId" class="empty">请先在「图谱审核」选择课程</div>
    <div v-else-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty err" role="alert">{{ error }}</div>
    <div v-else class="table-wrap">
      <table class="trace-table">
        <thead>
          <tr>
            <th></th>
            <th>ID</th>
            <th>Kind</th>
            <th>Status</th>
            <th>Model</th>
            <th>Tokens</th>
            <th>Duration</th>
            <th>Steps</th>
            <th>Error</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in items" :key="row.id">
            <tr class="run-row" @click="toggle(row.id)">
              <td class="exp">{{ expandedId === row.id ? '▼' : '▶' }}</td>
              <td>{{ row.id }}</td>
              <td>{{ row.kind }}</td>
              <td>{{ statusLabel(row.status) }}</td>
              <td class="muted">{{ row.model || '—' }}</td>
              <td class="muted">{{ row.tokensIn ?? 0 }}/{{ row.tokensOut ?? 0 }}</td>
              <td class="muted">{{ row.durationMs != null ? row.durationMs + 'ms' : '—' }}</td>
              <td>{{ row.stepCount ?? 0 }}</td>
              <td class="err-cell">{{ row.errorCode || '—' }}</td>
              <td class="muted">{{ row.createdAt || '—' }}</td>
            </tr>
            <tr v-if="expandedId === row.id" class="steps-row">
              <td colspan="10">
                <div v-if="stepsLoading" class="steps-msg">加载 steps…</div>
                <div v-else-if="stepsError" class="steps-msg err">{{ stepsError }}</div>
                <table v-else class="steps-table">
                  <thead>
                    <tr>
                      <th>Seq</th>
                      <th>Type</th>
                      <th>Name</th>
                      <th>Status</th>
                      <th>Tokens</th>
                      <th>Duration</th>
                      <th>Error</th>
                      <th>I/O</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="s in steps" :key="s.id">
                      <td>{{ s.seq }}</td>
                      <td>{{ s.stepType }}</td>
                      <td>{{ s.name }}</td>
                      <td>{{ stepStatusLabel(s.status) }}</td>
                      <td class="muted">{{ s.tokensIn ?? 0 }}/{{ s.tokensOut ?? 0 }}</td>
                      <td class="muted">{{ s.durationMs != null ? s.durationMs + 'ms' : '—' }}</td>
                      <td class="err-cell">{{ s.errorCode || '—' }}</td>
                      <td class="io">
                        <details v-if="s.inputJson || s.outputJson">
                          <summary>查看</summary>
                          <pre v-if="s.inputJson">IN: {{ s.inputJson }}</pre>
                          <pre v-if="s.outputJson">OUT: {{ s.outputJson }}</pre>
                        </details>
                        <span v-else class="muted">—</span>
                      </td>
                    </tr>
                    <tr v-if="!steps.length">
                      <td colspan="8" class="muted">无 step 记录</td>
                    </tr>
                  </tbody>
                </table>
              </td>
            </tr>
          </template>
          <tr v-if="!items.length">
            <td colspan="10" class="empty-row">暂无轨迹</td>
          </tr>
        </tbody>
      </table>
      <div class="pager" v-if="total > 0">
        <button class="btn ghost" :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
        <span class="muted">第 {{ page }} 页 · 共 {{ total }} 条 · 每页 {{ size }}</span>
        <button class="btn ghost" :disabled="page * size >= total || loading" @click="goPage(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { fetchAgentRuns, fetchAgentRunSteps } from '../api/teacher.js'
import { useTeacherCourse } from '../composables/useTeacherCourse.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const { courseId } = useTeacherCourse()

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const studentIdInput = ref('')

const expandedId = ref(null)
const steps = ref([])
const stepsLoading = ref(false)
const stepsError = ref('')

const listRequest = createLatestRequest()
const stepsRequest = createLatestRequest()
let disposed = false

function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

function statusLabel(s) {
  const map = { 0: 'RUNNING', 1: 'SUCCEEDED', 2: 'FAILED', 3: 'DEGRADED', 4: 'CANCELLED' }
  return map[s] ?? String(s)
}

function stepStatusLabel(s) {
  const map = { 0: 'OK', 1: 'FAILED', 2: 'SKIPPED', 3: 'CACHED' }
  return map[s] ?? String(s)
}

async function reload() {
  if (!courseId.value) return
  error.value = ''
  loading.value = true
  expandedId.value = null
  steps.value = []
  const request = listRequest.next()
  try {
    const studentId = studentIdInput.value ? Number(studentIdInput.value) : undefined
    const data = await fetchAgentRuns({
      courseId: courseId.value,
      studentId: Number.isFinite(studentId) && studentId > 0 ? studentId : undefined,
      page: page.value,
      size: size.value
    }, { signal: request.signal })
    if (!request.isCurrent() || disposed) return
    items.value = data?.items || []
    total.value = data?.total ?? 0
    page.value = data?.page ?? page.value
    size.value = data?.size ?? size.value
  } catch (e) {
    if (isAbortError(e) || !request.isCurrent() || disposed) return
    error.value = e?.response?.data?.message || e?.message || '加载失败'
    items.value = []
    total.value = 0
  } finally {
    if (request.isCurrent() && !disposed) loading.value = false
  }
}

function goPage(p) {
  page.value = p
  reload()
}

async function toggle(runId) {
  if (expandedId.value === runId) {
    expandedId.value = null
    steps.value = []
    stepsError.value = ''
    return
  }
  expandedId.value = runId
  steps.value = []
  stepsError.value = ''
  stepsLoading.value = true
  const request = stepsRequest.next()
  try {
    const data = await fetchAgentRunSteps(runId, { signal: request.signal })
    if (!request.isCurrent() || disposed) return
    steps.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (isAbortError(e) || !request.isCurrent() || disposed) return
    stepsError.value = e?.response?.data?.message || e?.message || '加载 steps 失败'
  } finally {
    if (request.isCurrent() && !disposed) stepsLoading.value = false
  }
}

watch(courseId, () => {
  page.value = 1
  reload()
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  listRequest.abort()
  stepsRequest.abort()
})
</script>

<style scoped>
.trace-page {
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
.field input {
  width: 100px;
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
  background: var(--accent, #3b6);
  color: #fff;
  cursor: pointer;
}
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.ghost {
  background: transparent;
  color: var(--text);
}
.empty, .empty-row {
  padding: 40px 20px;
  text-align: center;
  color: var(--mut);
  font-size: 13px;
}
.empty.err, .steps-msg.err, .err { color: #c44; }
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 20px 24px;
}
.trace-table, .steps-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.trace-table th, .trace-table td,
.steps-table th, .steps-table td {
  border-bottom: 1px solid var(--line);
  padding: 8px 6px;
  text-align: left;
  vertical-align: top;
}
.trace-table th, .steps-table th {
  color: var(--mut);
  font-weight: 500;
  position: sticky;
  top: 0;
  background: var(--bg);
}
.run-row { cursor: pointer; }
.run-row:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.exp { width: 20px; color: var(--mut); }
.muted { color: var(--mut); }
.err-cell { color: #c44; max-width: 120px; word-break: break-all; }
.steps-row td { background: color-mix(in srgb, var(--line) 35%, transparent); padding: 10px 12px; }
.steps-table { background: var(--bg); border-radius: 6px; }
.steps-msg { padding: 8px; font-size: 12px; color: var(--mut); }
.io details summary { cursor: pointer; color: var(--accent); }
.io pre {
  max-width: 420px;
  max-height: 160px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 11px;
  margin: 4px 0 0;
  padding: 6px;
  background: color-mix(in srgb, var(--line) 40%, transparent);
  border-radius: 4px;
}
.pager {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  font-size: 12px;
}
</style>
