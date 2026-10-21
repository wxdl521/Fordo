<template>
  <div data-testid="admin-clazzes-page" :style="pageStyle">
    <!-- 标题 -->
    <AdminPageHeader title="班级管理" subtitle="按班级批量加学生、批量选课。一个学生至多属于一个班级；班级只是批量选课的手段，学生仍可自行选课。">
      <template #actions>
        <button type="button" data-testid="admin-clazz-create" class="wj-admin-btn" :style="primaryBtnStyle" @click="openCreate">新建班级</button>
      </template>
    </AdminPageHeader>

    <!-- 筛选栏 -->
    <div data-testid="admin-clazzes-filters" :style="filterBarStyle">
      <input
        v-model="draft.keyword"
        type="text"
        data-testid="admin-clazzes-keyword"
        aria-label="按班级名称搜索"
        placeholder="搜索班级名称"
        class="wj-admin-field"
        :style="{ ...fieldStyle, width: '220px' }"
        @keydown.enter="onEnter($event, search)"
      />
      <button type="button" data-testid="admin-clazzes-search" class="wj-admin-btn" :style="primaryBtnStyle" @click="search">搜索</button>
      <button type="button" data-testid="admin-clazzes-reset" class="wj-admin-btn" :style="ghostBtnStyle" @click="reset">重置</button>
    </div>

    <!-- 操作反馈 -->
    <div v-if="notice" data-testid="admin-clazzes-notice" role="status" :style="noticeStyle">{{ notice }}</div>
    <div v-if="actionError" data-testid="admin-clazzes-action-error" role="alert" :style="errorStyle">{{ actionError }}</div>
    <div v-if="loadError" data-testid="admin-clazzes-error" role="alert" :style="errorStyle">{{ loadError }}</div>

    <!-- 表格 -->
    <AdminTableCard testid-prefix="admin-clazzes" :loading="loading" :empty="!loadError && records.length === 0" empty-text="还没有班级，点右上角「新建班级」开始。">
      <table data-testid="admin-clazzes-table" :style="clazzesTableStyle">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id" :data-testid="'admin-clazz-row-' + row.id" class="wj-admin-row">
            <td :style="{ ...tdStyle, fontWeight: 500 }">
              <router-link :to="'/admin/clazzes/' + row.id" :data-testid="'admin-clazz-link-' + row.id" :style="nameLinkStyle">{{ row.name }}</router-link>
            </td>
            <td :style="{ ...tdStyle, color: 'var(--mut)' }">{{ row.description || '—' }}</td>
            <td :style="numTdStyle">{{ row.studentCount ?? 0 }}</td>
            <td :style="numTdStyle">{{ row.courseCount ?? 0 }}</td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ formatDateTime(row.createdAt) }}</td>
            <td :style="tdStyle">
              <div :style="actionsStyle">
                <button type="button" :data-testid="'admin-clazz-open-' + row.id" class="wj-admin-act" :style="actBtnStyle" @click="openDetail(row)">管理</button>
                <button type="button" :data-testid="'admin-clazz-delete-' + row.id" class="wj-admin-act" :style="actDangerStyle" :disabled="busyId !== null" @click="remove(row)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </AdminTableCard>

    <!-- 分页 -->
    <AdminPager testid-prefix="admin-clazzes" :total="total" :page="page" :page-size="PAGE_SIZE" :loading="loading" @change="goPage" />

    <!-- 弹窗：新建班级 -->
    <AdminModal v-if="modal === 'create'" title="新建班级" testid="admin-clazz-create-modal" @close="closeModal">
      <div :style="formStyle">
        <label for="admin-clazz-create-name" :style="labelStyle">班级名称（最多 64 个字符，不能重名）</label>
        <input id="admin-clazz-create-name" v-model="createForm.name" data-testid="admin-clazz-create-name" type="text" autocomplete="off" placeholder="如 软件工程2201" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitCreate)" />
        <label for="admin-clazz-create-description" :style="labelStyle">说明（可选，最多 255 个字符）</label>
        <input id="admin-clazz-create-description" v-model="createForm.description" data-testid="admin-clazz-create-description" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitCreate)" />
        <div v-if="modalError" data-testid="admin-clazz-create-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">取消</button>
        <button type="button" data-testid="admin-clazz-create-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitCreate">{{ submitting ? '创建中…' : '创建' }}</button>
      </template>
    </AdminModal>
  </div>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AdminModal from '../components/AdminModal.vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import AdminPager from '../components/admin/AdminPager.vue'
import AdminTableCard from '../components/admin/AdminTableCard.vue'
import {
  actBtnStyle,
  actDangerStyle,
  actionsStyle,
  errorStyle,
  fieldStyle,
  filterBarStyle,
  formStyle,
  ghostBtnStyle,
  labelStyle,
  modalErrorStyle,
  noticeStyle,
  pageStyle,
  primaryBtnStyle,
  tableStyle,
  tdStyle,
  thStyle
} from '../components/admin/adminStyle.js'
import { formatDateTime, onEnter } from '../components/admin/adminUtils.js'
import { usePagedList } from '../components/admin/usePagedList.js'
import { createClazz, deleteClazz, listClazzes } from '../api/systemAdmin.js'

const router = useRouter()

const PAGE_SIZE = 20
const NAME_MAX = 64
const DESCRIPTION_MAX = 255

const columns = [
  { key: 'name', label: '班级名称', width: '200px' },
  { key: 'description', label: '说明' },
  { key: 'students', label: '学生数', width: '80px' },
  { key: 'courses', label: '课程数', width: '80px' },
  { key: 'createdAt', label: '创建时间', width: '150px' },
  { key: 'actions', label: '操作', width: '130px' }
]

const notice = ref('')
const actionError = ref('')
const busyId = ref(null)
let disposed = false

const { draft, page, loading, loadError, records, total, load, search: runSearch, reset: runReset, goPage } = usePagedList({
  initialFilters: { keyword: '' },
  pageSize: PAGE_SIZE,
  errorMessage: '加载班级列表失败',
  fetchPage: listClazzes,
  toQuery({ keyword }) {
    const query = {}
    const kw = String(keyword || '').trim()
    if (kw) query.keyword = kw
    return query
  }
})

function clearFeedback() {
  notice.value = ''
  actionError.value = ''
}

function search() {
  clearFeedback()
  return runSearch()
}

function reset() {
  clearFeedback()
  return runReset()
}

function openDetail(row) {
  router.push('/admin/clazzes/' + row.id)
}

// ── 删除 ──
async function remove(row) {
  if (busyId.value !== null) return
  if (!window.confirm(`确定删除班级「${row.name}」吗？只会删除班级本身及其「成员 / 课程」关联；已有选课记录保留，学生仍可继续学习已选课程。`)) {
    return
  }
  busyId.value = row.id
  clearFeedback()
  try {
    await deleteClazz(row.id)
    if (disposed) return
    notice.value = `已删除班级「${row.name}」`
    await load()
  } catch (err) {
    if (disposed) return
    actionError.value = err?.message || '删除失败'
  } finally {
    busyId.value = null
  }
}

// ── 新建 ──
const modal = ref(null) // 'create' | null
const submitting = ref(false)
const modalError = ref('')
const createForm = reactive({ name: '', description: '' })

function openCreate() {
  clearFeedback()
  createForm.name = ''
  createForm.description = ''
  modalError.value = ''
  modal.value = 'create'
}

function closeModal() {
  if (submitting.value) return
  modal.value = null
  modalError.value = ''
}

async function submitCreate() {
  if (submitting.value) return
  const name = createForm.name.trim()
  const description = createForm.description.trim()
  let problem = ''
  if (!name) problem = '请填写班级名称'
  else if (name.length > NAME_MAX) problem = `班级名称不能超过 ${NAME_MAX} 个字符`
  else if (description.length > DESCRIPTION_MAX) problem = `说明不能超过 ${DESCRIPTION_MAX} 个字符`
  if (problem) {
    modalError.value = problem
    return
  }
  submitting.value = true
  modalError.value = ''
  try {
    await createClazz({ name, description })
    submitting.value = false
    closeModal()
    notice.value = `已创建班级「${name}」，点班级名称进入详情添加学生与课程`
    page.value = 1
    await load()
  } catch (err) {
    modalError.value = err?.message || '创建失败'
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
})

void load()

// ── 样式（共用部分见 components/admin/adminStyle.js） ──
const clazzesTableStyle = { ...tableStyle, minWidth: '760px' }
const numTdStyle = { ...tdStyle, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }
const nameLinkStyle = { color: 'var(--ink)', textDecoration: 'underline', textDecorationColor: 'var(--line)', textUnderlineOffset: '3px' }
</script>
