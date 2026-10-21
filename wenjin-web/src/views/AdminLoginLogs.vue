<template>
  <div data-testid="admin-login-page" :style="pageStyle">
    <AdminPageHeader title="登录日志" subtitle="所有账号的登录尝试（成功与失败），失败记录不含密码，只读。" />

    <!-- 筛选栏 -->
    <div data-testid="admin-login-filters" :style="filterBarStyle">
      <input
        v-model="draft.username"
        type="text"
        data-testid="admin-login-username"
        aria-label="按登录用户名搜索"
        placeholder="登录用户名"
        class="wj-admin-field"
        :style="{ ...fieldStyle, width: '170px' }"
        @keydown.enter="onEnter($event, search)"
      />
      <select v-model="draft.success" data-testid="admin-login-success" aria-label="按结果筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '110px' }" @change="search">
        <option value="">全部结果</option>
        <option :value="1">成功</option>
        <option :value="0">失败</option>
      </select>
      <AdminDateRange v-model:from="draft.from" v-model:to="draft.to" testid-prefix="admin-login" />
      <button type="button" data-testid="admin-login-search" class="wj-admin-btn" :style="primaryBtnStyle" @click="search">搜索</button>
      <button type="button" data-testid="admin-login-reset" class="wj-admin-btn" :style="ghostBtnStyle" @click="reset">重置</button>
    </div>

    <div v-if="rangeError" data-testid="admin-login-range-error" role="alert" :style="errorStyle">{{ rangeError }}</div>
    <div v-if="loadError" data-testid="admin-login-error" role="alert" :style="errorStyle">{{ loadError }}</div>

    <!-- 表格 -->
    <AdminTableCard testid-prefix="admin-login" :loading="loading" :empty="!loadError && records.length === 0" empty-text="没有符合条件的登录记录。">
      <table data-testid="admin-login-table" :style="loginTableStyle">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id" :data-testid="'admin-login-row-' + row.id" class="wj-admin-row">
            <td :style="{ ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ formatDateTime(row.createdAt) }}</td>
            <td :style="{ ...tdStyle, fontWeight: 500, overflowWrap: 'anywhere' }" :title="row.userId != null ? `用户 ID：${row.userId}` : '账号不存在'">{{ row.username }}</td>
            <td :style="tdStyle">
              <span :data-testid="'admin-login-result-' + row.id" :style="tagStyle(isSuccess(row) ? 'ok' : 'acc')">{{ isSuccess(row) ? '成功' : '失败' }}</span>
            </td>
            <td :style="tdStyle">
              <span :data-testid="'admin-login-reason-' + row.id" :title="row.failReason || undefined">{{ isSuccess(row) ? '—' : loginFailReasonLabel(row.failReason) }}</span>
            </td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ row.ip || '—' }}</td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', fontSize: '12px' }">
              <span :data-testid="'admin-login-ua-' + row.id" :title="row.userAgent || undefined">{{ row.userAgent ? truncateText(row.userAgent, UA_MAX) : '—' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </AdminTableCard>

    <!-- 分页 -->
    <AdminPager testid-prefix="admin-login" :total="total" :page="page" :page-size="PAGE_SIZE" :loading="loading" @change="goPage" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
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
import { formatDateTime, onEnter, truncateText } from '../components/admin/adminUtils.js'
import { usePagedList } from '../components/admin/usePagedList.js'
import { listLoginLogs } from '../api/systemAdmin.js'
import { loginFailReasonLabel } from '../utils/auditActions.js'

const PAGE_SIZE = 20
const UA_MAX = 48

const columns = [
  { key: 'time', label: '时间', width: '140px' },
  { key: 'username', label: '用户名', width: '150px' },
  { key: 'result', label: '结果', width: '70px' },
  { key: 'reason', label: '失败原因', width: '140px' },
  { key: 'ip', label: 'IP', width: '130px' },
  { key: 'ua', label: '浏览器 / 设备' }
]

const rangeError = ref('')

const { draft, page, loading, loadError, records, total, load, search: runSearch, reset: runReset, goPage } = usePagedList({
  initialFilters: { username: '', success: '', from: '', to: '' },
  pageSize: PAGE_SIZE,
  errorMessage: '加载登录日志失败',
  fetchPage: listLoginLogs,
  toQuery({ username, success, from, to }) {
    const query = {}
    const name = String(username || '').trim()
    if (name) query.username = name
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

function isSuccess(row) {
  return row.success === true || row.success === 1
}

void load()

// ── 样式（通用部分见 components/admin/adminStyle.js） ──
const loginTableStyle = { ...tableStyle, minWidth: '860px' }
</script>
