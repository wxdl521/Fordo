<template>
  <div data-testid="admin-audit-page" :style="pageStyle">
    <AdminPageHeader title="操作日志" subtitle="后台关键操作（用户、课程、图谱、题目、语料）的留痕记录，只读。" />

    <!-- 筛选栏 -->
    <div data-testid="admin-audit-filters" :style="filterBarStyle">
      <input
        v-model="draft.operator"
        type="text"
        data-testid="admin-audit-operator"
        aria-label="按操作人用户名搜索"
        placeholder="操作人用户名"
        class="wj-admin-field"
        :style="{ ...fieldStyle, width: '170px' }"
        @keydown.enter="onEnter($event, search)"
      />
      <select v-model="draft.action" data-testid="admin-audit-action" aria-label="按操作类型筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '190px' }" @change="search">
        <option value="">全部操作</option>
        <option v-for="code in actionOptions" :key="code" :value="code">{{ auditActionLabel(code) }}</option>
      </select>
      <select v-model="draft.success" data-testid="admin-audit-success" aria-label="按结果筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '110px' }" @change="search">
        <option value="">全部结果</option>
        <option :value="1">成功</option>
        <option :value="0">失败</option>
      </select>
      <AdminDateRange v-model:from="draft.from" v-model:to="draft.to" testid-prefix="admin-audit" />
      <button type="button" data-testid="admin-audit-search" class="wj-admin-btn" :style="primaryBtnStyle" @click="search">搜索</button>
      <button type="button" data-testid="admin-audit-reset" class="wj-admin-btn" :style="ghostBtnStyle" @click="reset">重置</button>
    </div>

    <div v-if="rangeError" data-testid="admin-audit-range-error" role="alert" :style="errorStyle">{{ rangeError }}</div>
    <div v-if="loadError" data-testid="admin-audit-error" role="alert" :style="errorStyle">{{ loadError }}</div>

    <!-- 表格 -->
    <AdminTableCard testid-prefix="admin-audit" :loading="loading" :empty="!loadError && records.length === 0" empty-text="没有符合条件的操作记录。">
      <table data-testid="admin-audit-table" :style="auditTableStyle">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id" :data-testid="'admin-audit-row-' + row.id" class="wj-admin-row">
            <td :style="timeTdStyle">{{ formatDateTime(row.createdAt) }}</td>
            <td :style="{ ...tdStyle, fontWeight: 500 }" :title="row.operatorId != null ? `用户 ID：${row.operatorId}` : undefined">
              <template v-if="row.operatorName">{{ row.operatorName }}</template>
              <span v-else-if="row.operatorId != null" :style="mutedStyle">用户 #{{ row.operatorId }}</span>
              <span v-else :style="mutedStyle">匿名</span>
            </td>
            <td :style="tdStyle">{{ row.operatorRole == null ? '—' : roleLabel(row.operatorRole) }}</td>
            <td :style="tdStyle">
              <span :data-testid="'admin-audit-action-' + row.id" :title="row.action">{{ auditActionLabel(row.action) }}</span>
            </td>
            <td :style="tdStyle">{{ targetText(row) }}</td>
            <td :style="tdStyle">
              <code :style="requestCodeStyle"><span :style="{ fontWeight: 600 }">{{ row.httpMethod }}</span> {{ row.path }}</code>
            </td>
            <td :style="{ ...tdStyle, fontVariantNumeric: 'tabular-nums', color: row.httpStatus >= 400 ? 'var(--acc)' : 'var(--ink)' }">{{ row.httpStatus }}</td>
            <td :style="tdStyle">
              <span :data-testid="'admin-audit-result-' + row.id" :style="tagStyle(isSuccess(row) ? 'ok' : 'acc')">{{ isSuccess(row) ? '成功' : '失败' }}</span>
            </td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ row.ip || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </AdminTableCard>

    <!-- 分页 -->
    <AdminPager testid-prefix="admin-audit" :total="total" :page="page" :page-size="PAGE_SIZE" :loading="loading" @change="goPage" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import AdminDateRange from '../components/admin/AdminDateRange.vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import AdminPager from '../components/admin/AdminPager.vue'
import AdminTableCard from '../components/admin/AdminTableCard.vue'
import {
  errorStyle,
  fieldStyle,
  filterBarStyle,
  ghostBtnStyle,
  pageStyle,
  primaryBtnStyle,
  tableStyle,
  tagStyle,
  tdStyle,
  thStyle
} from '../components/admin/adminStyle.js'
import { formatDateTime, onEnter } from '../components/admin/adminUtils.js'
import { usePagedList } from '../components/admin/usePagedList.js'
import { listAuditActions, listAuditLogs } from '../api/systemAdmin.js'
import { AUDIT_ACTION_LABELS, auditActionLabel, auditTargetLabel } from '../utils/auditActions.js'
import { roleLabel } from '../utils/roles.js'

const PAGE_SIZE = 20

const columns = [
  { key: 'time', label: '时间', width: '140px' },
  { key: 'operator', label: '操作人', width: '120px' },
  { key: 'role', label: '角色', width: '70px' },
  { key: 'action', label: '动作', width: '150px' },
  { key: 'target', label: '对象', width: '110px' },
  { key: 'request', label: '请求' },
  { key: 'status', label: '状态码', width: '70px' },
  { key: 'result', label: '结果', width: '70px' },
  { key: 'ip', label: 'IP', width: '120px' }
]

const rangeError = ref('')

const { draft, page, loading, loadError, records, total, load, search: runSearch, reset: runReset, goPage } = usePagedList({
  initialFilters: { operator: '', action: '', success: '', from: '', to: '' },
  pageSize: PAGE_SIZE,
  errorMessage: '加载操作日志失败',
  fetchPage: listAuditLogs,
  toQuery({ operator, action, success, from, to }) {
    const query = {}
    const name = String(operator || '').trim()
    if (name) query.operator = name
    if (action) query.action = action
    if (success !== '' && success != null) query.success = Number(success)
    if (from) query.from = from
    if (to) query.to = to
    return query
  }
})

function search() {
  if (draft.from && draft.to && draft.from > draft.to) {
    rangeError.value = '起始日期不能晚于结束日期'
    return undefined
  }
  rangeError.value = ''
  return runSearch()
}

function reset() {
  rangeError.value = ''
  return runReset()
}

// ── 动作下拉：后端给出库里出现过的 action；已知的按固定顺序在前，未知的按字母序在后 ──
const serverActions = ref(null) // null = 尚未取到 / 取失败 → 退回全部已知动作
let disposed = false

const actionOptions = computed(() => {
  const known = Object.keys(AUDIT_ACTION_LABELS)
  if (!serverActions.value) return known
  const present = new Set(serverActions.value)
  const knownPresent = known.filter((code) => present.has(code))
  const unknown = serverActions.value.filter((code) => !Object.prototype.hasOwnProperty.call(AUDIT_ACTION_LABELS, code)).sort()
  return [...knownPresent, ...unknown]
})

async function loadActions() {
  try {
    const list = await listAuditActions()
    if (disposed) return
    serverActions.value = Array.isArray(list) ? list.filter((code) => typeof code === 'string' && code) : null
  } catch {
    /* 下拉取不到不影响列表：退回已知动作清单 */
  }
}

function isSuccess(row) {
  return row.success === true || row.success === 1
}

function targetText(row) {
  const type = row.targetType ? auditTargetLabel(row.targetType) : ''
  const id = row.targetId != null && row.targetId !== '' ? `#${row.targetId}` : ''
  return [type, id].filter(Boolean).join(' ') || '—'
}

onBeforeUnmount(() => {
  disposed = true
})

void loadActions()
void load()

// ── 样式（通用部分见 components/admin/adminStyle.js） ──
const auditTableStyle = { ...tableStyle, minWidth: '1080px' }
const timeTdStyle = { ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }
const mutedStyle = { color: 'var(--mut)', fontWeight: 400 }
const requestCodeStyle = {
  fontFamily: "'Consolas', 'Fira Mono', monospace",
  fontSize: '12px',
  overflowWrap: 'anywhere'
}
</script>
