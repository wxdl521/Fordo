<template>
  <div class="rag-page">
    <header class="toolbar">
      <div class="left">
        <span class="title">语料审核</span>
        <span v-if="courseId" class="meta">课程 #{{ courseId }}</span>
      </div>
      <div class="right">
        <div class="tabs">
          <button class="tab" :class="{ active: tab === 'sources' }" @click="tab = 'sources'">来源列表</button>
          <button class="tab" :class="{ active: tab === 'mappings' }" @click="tab = 'mappings'">映射审核</button>
        </div>
        <button class="btn" :disabled="loading || !courseId" @click="reload">刷新</button>
      </div>
    </header>

    <div v-if="!courseId" class="empty">请先在「图谱审核」选择课程</div>
    <div v-else-if="flagOff" class="empty err" role="alert">功能未开启（rag-enabled）</div>
    <div v-else-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty err" role="alert">{{ error }}</div>

    <!-- 来源列表 -->
    <div v-else-if="tab === 'sources'" class="table-wrap">
      <div class="actions">
        <button class="btn" @click="showUpload = true">上传 NODE_REF Markdown</button>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>标题</th>
            <th>类型</th>
            <th>状态</th>
            <th>切块</th>
            <th>待审</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in sources" :key="row.id">
            <td>{{ row.title || '—' }}</td>
            <td>{{ kindLabel(row.kind) }}</td>
            <td>{{ statusLabel(row.status) }}</td>
            <td>{{ row.chunkCount ?? 0 }}</td>
            <td :class="{ warn: (row.pendingMapCount || 0) > 0 }">{{ row.pendingMapCount ?? 0 }}</td>
            <td class="muted">{{ row.createdAt || '—' }}</td>
          </tr>
          <tr v-if="!sources.length">
            <td colspan="6" class="empty-row">暂无语料来源</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 映射审核 -->
    <div v-else class="table-wrap">
      <div class="filters">
        <label class="field">
          <span>状态</span>
          <select v-model="mapStatus" @change="page = 1; loadMappings()">
            <option value="">全部</option>
            <option value="0">待审</option>
            <option value="1">已通过</option>
            <option value="2">已驳回</option>
          </select>
        </label>
        <label class="field">
          <span>节点</span>
          <input v-model="mapNodeCode" type="text" placeholder="nodeCode" @keyup.enter="page = 1; loadMappings()" />
        </label>
        <button class="btn ghost" :disabled="loading" @click="page = 1; loadMappings()">筛选</button>
        <button class="btn" :disabled="batching || !courseId" @click="doBatchApprove">批量通过待审</button>
      </div>

      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>节点</th>
            <th>置信度</th>
            <th>状态</th>
            <th>来源</th>
            <th>正文预览</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in mappings" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.nodeCode }}</td>
            <td>{{ formatConf(row.confidence) }}</td>
            <td>{{ reviewLabel(row.reviewStatus) }}</td>
            <td class="muted">{{ row.sourceTitle || '—' }}</td>
            <td class="preview">{{ row.chunkPreview || '—' }}</td>
            <td class="ops">
              <template v-if="row.reviewStatus === 0">
                <button class="btn tiny" :disabled="actingId === row.id" @click="doApprove(row.id)">通过</button>
                <button class="btn tiny ghost" :disabled="actingId === row.id" @click="doReject(row.id)">驳回</button>
              </template>
              <span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!mappings.length">
            <td colspan="7" class="empty-row">暂无映射</td>
          </tr>
        </tbody>
      </table>
      <div class="pager" v-if="total > 0">
        <button class="btn ghost" :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
        <span class="muted">第 {{ page }} 页 · 共 {{ total }} 条 · 每页 {{ size }}</span>
        <button class="btn ghost" :disabled="page * size >= total || loading" @click="goPage(page + 1)">下一页</button>
      </div>
    </div>

    <!-- 上传弹窗 -->
    <div v-if="showUpload" class="modal-mask" @click.self="showUpload = false">
      <div class="modal" role="dialog" aria-modal="true" aria-label="上传 NODE_REF">
        <h3 class="modal-title">上传 NODE_REF Markdown</h3>
        <label class="field block">
          <span>标题（可选）</span>
          <input v-model="uploadTitle" type="text" maxlength="200" placeholder="默认取 frontmatter 或「NODE_REF 导入」" />
        </label>
        <label class="field block">
          <span>Markdown</span>
          <textarea v-model="uploadMd" rows="12" placeholder="---&#10;title: 示例&#10;nodeCode: SE-1.1&#10;---&#10;&#10;## 正文…"></textarea>
        </label>
        <div v-if="uploadError" class="upload-err" role="alert">{{ uploadError }}</div>
        <div class="modal-actions">
          <button class="btn ghost" :disabled="uploading" @click="showUpload = false">取消</button>
          <button class="btn" :disabled="uploading || !uploadMd.trim()" @click="doUpload">
            {{ uploading ? '入库中…' : '提交入库' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import {
  fetchRagSources,
  fetchRagMappings,
  approveRagMapping,
  rejectRagMapping,
  batchApproveRagMappings,
  createNodeRefSource
} from '../api/teacher.js'
import { useTeacherCourse } from '../composables/useTeacherCourse.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const { courseId } = useTeacherCourse()

const tab = ref('sources')
const sources = ref([])
const mappings = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const mapStatus = ref('0')
const mapNodeCode = ref('')
const loading = ref(false)
const error = ref('')
const flagOff = ref(false)
const actingId = ref(null)
const batching = ref(false)

const showUpload = ref(false)
const uploadTitle = ref('')
const uploadMd = ref('')
const uploadError = ref('')
const uploading = ref(false)

const listRequest = createLatestRequest()
let disposed = false

function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

function isNotFound(err) {
  return err?.httpStatus === 404 || err?.code === 404
}

function kindLabel(k) {
  return ({ 0: '课件', 1: '教材', 2: 'NODE_REF' })[k] ?? String(k)
}
function statusLabel(s) {
  return ({ 0: '入库中', 1: '就绪', 2: '失败', 3: '归档' })[s] ?? String(s)
}
function reviewLabel(s) {
  return ({ 0: '待审', 1: '已通过', 2: '已驳回' })[s] ?? String(s)
}
function formatConf(c) {
  if (c == null) return '—'
  const n = Number(c)
  return Number.isFinite(n) ? n.toFixed(3) : String(c)
}

async function reload() {
  if (!courseId.value) return
  if (tab.value === 'sources') await loadSources()
  else await loadMappings()
}

async function loadSources() {
  if (!courseId.value) return
  error.value = ''
  flagOff.value = false
  loading.value = true
  const request = listRequest.next()
  try {
    const data = await fetchRagSources(courseId.value, { signal: request.signal })
    if (!request.isCurrent() || disposed) return
    sources.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (isAbortError(e) || !request.isCurrent() || disposed) return
    if (isNotFound(e)) {
      flagOff.value = true
      error.value = ''
    } else {
      error.value = e?.response?.data?.message || e?.message || '加载失败'
    }
    sources.value = []
  } finally {
    if (request.isCurrent() && !disposed) loading.value = false
  }
}

async function loadMappings() {
  if (!courseId.value) return
  error.value = ''
  flagOff.value = false
  loading.value = true
  const request = listRequest.next()
  try {
    const status = mapStatus.value === '' ? undefined : Number(mapStatus.value)
    const data = await fetchRagMappings({
      courseId: courseId.value,
      nodeCode: mapNodeCode.value.trim() || undefined,
      status: Number.isFinite(status) ? status : undefined,
      page: page.value,
      size: size.value
    }, { signal: request.signal })
    if (!request.isCurrent() || disposed) return
    mappings.value = data?.items || []
    total.value = data?.total ?? 0
    page.value = data?.page ?? page.value
    size.value = data?.size ?? size.value
  } catch (e) {
    if (isAbortError(e) || !request.isCurrent() || disposed) return
    if (isNotFound(e)) {
      flagOff.value = true
      error.value = ''
    } else {
      error.value = e?.response?.data?.message || e?.message || '加载失败'
    }
    mappings.value = []
    total.value = 0
  } finally {
    if (request.isCurrent() && !disposed) loading.value = false
  }
}

function goPage(p) {
  page.value = p
  loadMappings()
}

async function doApprove(id) {
  actingId.value = id
  try {
    await approveRagMapping(id)
    await loadMappings()
  } catch (e) {
    if (!isAbortError(e)) error.value = e?.message || '通过失败'
  } finally {
    actingId.value = null
  }
}

async function doReject(id) {
  actingId.value = id
  try {
    await rejectRagMapping(id)
    await loadMappings()
  } catch (e) {
    if (!isAbortError(e)) error.value = e?.message || '驳回失败'
  } finally {
    actingId.value = null
  }
}

async function doBatchApprove() {
  if (!courseId.value) return
  batching.value = true
  try {
    await batchApproveRagMappings(courseId.value, {
      nodeCode: mapNodeCode.value.trim() || undefined
    })
    await loadMappings()
  } catch (e) {
    if (!isAbortError(e)) error.value = e?.message || '批量通过失败'
  } finally {
    batching.value = false
  }
}

async function doUpload() {
  if (!courseId.value || !uploadMd.value.trim()) return
  uploading.value = true
  uploadError.value = ''
  try {
    await createNodeRefSource({
      courseId: courseId.value,
      title: uploadTitle.value.trim() || undefined,
      markdown: uploadMd.value
    })
    showUpload.value = false
    uploadTitle.value = ''
    uploadMd.value = ''
    tab.value = 'sources'
    await loadSources()
  } catch (e) {
    uploadError.value = e?.message || '入库失败'
  } finally {
    uploading.value = false
  }
}

watch(courseId, () => {
  page.value = 1
  reload()
}, { immediate: true })

watch(tab, () => {
  page.value = 1
  reload()
})

onBeforeUnmount(() => {
  disposed = true
  listRequest.cancel()
})
</script>

<style scoped>
.rag-page {
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
.right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tabs { display: flex; gap: 4px; background: var(--card2, var(--card)); border-radius: 8px; padding: 2px; }
.tab {
  font-size: 12px;
  padding: 5px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--mut);
  cursor: pointer;
}
.tab.active {
  background: var(--card);
  color: var(--acc);
  font-weight: 600;
  box-shadow: 0 0 0 1px var(--line);
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
.btn.ghost { background: transparent; color: var(--text); }
.btn.tiny { padding: 3px 8px; font-size: 11px; }
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
.actions, .filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.field {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--mut);
}
.field.block {
  flex-direction: column;
  align-items: stretch;
  margin-bottom: 10px;
}
.field input, .field select, .field textarea {
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--bg);
  color: var(--text);
  font-size: 12px;
  font-family: inherit;
}
.field input { width: 120px; }
.field.block input, .field.block textarea { width: 100%; }
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
.muted { color: var(--mut); }
.warn { color: var(--warn); font-weight: 600; }
.preview {
  max-width: 320px;
  color: var(--mut);
  line-height: 1.45;
  word-break: break-word;
}
.ops { white-space: nowrap; display: flex; gap: 6px; }
.pager {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  font-size: 12px;
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  z-index: 80;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.modal {
  width: min(560px, 100%);
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.18);
}
.modal-title {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.upload-err {
  font-size: 12px;
  color: #c44;
  margin-top: 6px;
}
</style>
