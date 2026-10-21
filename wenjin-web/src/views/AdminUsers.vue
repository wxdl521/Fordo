<template>
  <div data-testid="admin-users-page" :style="pageStyle">
    <!-- 标题 -->
    <AdminPageHeader title="用户管理" subtitle="新建账号、启用 / 禁用、重置密码、调整教师与学生角色，或用 Excel 批量导入。">
      <template #actions>
        <button type="button" data-testid="admin-user-import" class="wj-admin-btn" :style="ghostBtnStyle" @click="openImport">批量导入</button>
        <button type="button" data-testid="admin-user-create" class="wj-admin-btn" :style="primaryBtnStyle" @click="openCreate">新建用户</button>
      </template>
    </AdminPageHeader>

    <!-- 筛选栏 -->
    <div data-testid="admin-users-filters" :style="filterBarStyle">
      <input
        v-model="draft.keyword"
        type="text"
        data-testid="admin-users-keyword"
        aria-label="按用户名或姓名搜索"
        placeholder="搜索用户名或姓名"
        class="wj-admin-field"
        :style="{ ...fieldStyle, width: '220px' }"
        @keydown.enter="onEnter($event, search)"
      />
      <select v-model="draft.role" data-testid="admin-users-role" aria-label="按角色筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '120px' }" @change="search">
        <option value="">全部角色</option>
        <option :value="1">教师</option>
        <option :value="2">学生</option>
        <option :value="3">管理员</option>
      </select>
      <select v-model="draft.status" data-testid="admin-users-status" aria-label="按状态筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '120px' }" @change="search">
        <option value="">全部状态</option>
        <option :value="1">启用</option>
        <option :value="0">禁用</option>
      </select>
      <button type="button" data-testid="admin-users-search" class="wj-admin-btn" :style="primaryBtnStyle" @click="search">搜索</button>
    </div>

    <!-- 操作反馈 -->
    <div v-if="notice" data-testid="admin-users-notice" role="status" :style="noticeStyle">{{ notice }}</div>
    <div v-if="actionError" data-testid="admin-users-action-error" role="alert" :style="errorStyle">{{ actionError }}</div>
    <div v-if="loadError" data-testid="admin-users-error" role="alert" :style="errorStyle">{{ loadError }}</div>

    <!-- 表格 -->
    <AdminTableCard testid-prefix="admin-users" :loading="loading" :empty="!loadError && records.length === 0" empty-text="没有符合条件的用户。">
      <table data-testid="admin-users-table" :style="usersTableStyle">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id" :data-testid="'admin-user-row-' + row.id" class="wj-admin-row">
            <td :style="tdStyle">{{ row.id }}</td>
            <td :style="{ ...tdStyle, fontWeight: 500 }">
              {{ row.username }}
              <span v-if="isSelf(row)" :style="selfTagStyle">你</span>
            </td>
            <td :style="tdStyle">{{ row.realName || '—' }}</td>
            <td :style="tdStyle">
              <span :data-testid="'admin-user-role-tag-' + row.id" :style="roleTagStyle(row.role)">{{ roleLabel(row.role) }}</span>
            </td>
            <td :style="tdStyle">
              <span :data-testid="'admin-user-status-tag-' + row.id" :style="statusTagStyle(row.status)">{{ row.status === 1 ? '启用' : '禁用' }}</span>
            </td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ formatDateTime(row.createdAt) }}</td>
            <td :style="tdStyle">
              <div :style="actionsStyle">
                <button type="button" :data-testid="'admin-user-edit-' + row.id" class="wj-admin-act" :style="actBtnStyle" :disabled="busyId !== null" @click="openEdit(row)">编辑姓名</button>
                <button
                  type="button"
                  :data-testid="'admin-user-toggle-' + row.id"
                  class="wj-admin-act"
                  :style="row.status === 1 ? actDangerStyle : actOkStyle"
                  :disabled="busyId !== null || (isSelf(row) && row.status === 1)"
                  :title="isSelf(row) && row.status === 1 ? '不能禁用自己' : ''"
                  @click="toggleStatus(row)"
                >{{ row.status === 1 ? '禁用' : '启用' }}</button>
                <button type="button" :data-testid="'admin-user-reset-' + row.id" class="wj-admin-act" :style="actBtnStyle" :disabled="busyId !== null" @click="openReset(row)">重置密码</button>
                <button
                  v-if="row.role === 1 || row.role === 2"
                  type="button"
                  :data-testid="'admin-user-role-' + row.id"
                  class="wj-admin-act"
                  :style="actBtnStyle"
                  :disabled="busyId !== null"
                  @click="changeRole(row)"
                >{{ row.role === 1 ? '改为学生' : '改为教师' }}</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </AdminTableCard>

    <!-- 分页 -->
    <AdminPager testid-prefix="admin-users" :total="total" :page="page" :page-size="PAGE_SIZE" :loading="loading" @change="goPage" />

    <!-- 弹窗：新建用户 -->
    <AdminModal v-if="modal === 'create'" title="新建用户" testid="admin-user-create-modal" @close="closeModal">
      <div :style="formStyle">
        <label for="admin-create-username" :style="labelStyle">用户名（3–64 个字符，登录用）</label>
        <input id="admin-create-username" v-model="createForm.username" data-testid="admin-user-create-username" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitCreate)" />
        <label for="admin-create-realname" :style="labelStyle">姓名</label>
        <input id="admin-create-realname" v-model="createForm.realName" data-testid="admin-user-create-realname" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitCreate)" />
        <label for="admin-create-password" :style="labelStyle">初始密码（8–72 个字符）</label>
        <input id="admin-create-password" v-model="createForm.password" data-testid="admin-user-create-password" type="password" autocomplete="new-password" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitCreate)" />
        <label for="admin-create-role" :style="labelStyle">角色</label>
        <select id="admin-create-role" v-model="createForm.role" data-testid="admin-user-create-role" class="wj-admin-field" :style="fieldStyle">
          <option :value="1">教师</option>
          <option :value="2">学生</option>
        </select>
        <div v-if="modalError" data-testid="admin-user-create-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">取消</button>
        <button type="button" data-testid="admin-user-create-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitCreate">{{ submitting ? '创建中…' : '创建' }}</button>
      </template>
    </AdminModal>

    <!-- 弹窗：编辑姓名 -->
    <AdminModal v-if="modal === 'edit' && target" title="编辑姓名" testid="admin-user-edit-modal" @close="closeModal">
      <div :style="formStyle">
        <div :style="hintLineStyle">用户名：{{ target.username }}</div>
        <label for="admin-edit-realname" :style="labelStyle">姓名</label>
        <input id="admin-edit-realname" v-model="editForm.realName" data-testid="admin-user-edit-realname" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitEdit)" />
        <div v-if="modalError" data-testid="admin-user-edit-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">取消</button>
        <button type="button" data-testid="admin-user-edit-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitEdit">{{ submitting ? '保存中…' : '保存' }}</button>
      </template>
    </AdminModal>

    <!-- 弹窗：重置密码 -->
    <AdminModal v-if="modal === 'reset' && target" title="重置密码" testid="admin-user-reset-modal" @close="closeModal">
      <!-- 已生成的新密码：仅显示这一次 -->
      <div v-if="generatedPassword" data-testid="admin-user-reset-result" :style="formStyle">
        <div :style="hintLineStyle">已重置「{{ target.username }}」的密码。新密码只显示这一次，请立即复制并转交给用户：</div>
        <div :style="passwordBoxStyle">
          <code data-testid="admin-user-reset-generated" :style="passwordCodeStyle">{{ generatedPassword }}</code>
          <button type="button" data-testid="admin-user-reset-copy" class="wj-admin-btn" :style="ghostBtnStyle" @click="copyGeneratedPassword">复制</button>
        </div>
        <div v-if="copyState" data-testid="admin-user-reset-copy-state" role="status" :style="{ ...hintLineStyle, color: copyState === 'ok' ? 'var(--ok)' : 'var(--acc)' }">{{ copyState === 'ok' ? '已复制到剪贴板' : '复制失败，请手动选中上方密码复制' }}</div>
      </div>
      <div v-else :style="formStyle">
        <div :style="hintLineStyle">目标用户：{{ target.username }}{{ target.realName ? `（${target.realName}）` : '' }}</div>
        <label for="admin-reset-password" :style="labelStyle">新密码（8–72 个字符；留空则由系统自动生成）</label>
        <input id="admin-reset-password" v-model="resetForm.newPassword" data-testid="admin-user-reset-password" type="password" autocomplete="new-password" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitReset)" />
        <div v-if="modalError" data-testid="admin-user-reset-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <template v-if="generatedPassword">
          <button type="button" data-testid="admin-user-reset-done" class="wj-admin-btn" :style="primaryBtnStyle" @click="closeModal">完成</button>
        </template>
        <template v-else>
          <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">取消</button>
          <button type="button" data-testid="admin-user-reset-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitReset">{{ submitting ? '重置中…' : '重置密码' }}</button>
        </template>
      </template>
    </AdminModal>

    <!-- 弹窗：批量导入 -->
    <AdminModal v-if="modal === 'import'" title="批量导入用户" testid="admin-user-import-modal" width="620px" @close="closeModal">
      <div :style="formStyle">
        <div :style="hintLineStyle">
          上传 .xlsx 文件（≤ 2MB，≤ 1000 行）。列：用户名、姓名、角色（教师 / 学生）、初始密码。
          全有或全无：任何一行有错，整份文件都不会导入。
        </div>
        <div>
          <button type="button" data-testid="admin-user-import-template" class="wj-admin-btn" :style="ghostBtnStyle" :disabled="templateLoading" @click="downloadTemplate">{{ templateLoading ? '下载中…' : '下载导入模板' }}</button>
        </div>
        <label for="admin-import-file" :style="labelStyle">选择文件</label>
        <input id="admin-import-file" ref="importInputRef" data-testid="admin-user-import-file" type="file" accept=".xlsx" class="wj-admin-file" :style="fileInputStyle" @change="onImportFileChange" />
        <div v-if="modalError" data-testid="admin-user-import-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>

        <!-- 导入结果 -->
        <div v-if="importResult" data-testid="admin-user-import-result">
          <div v-if="importResult.errors.length === 0" data-testid="admin-user-import-success" role="status" :style="noticeStyle">导入完成：成功创建 {{ importResult.created }} 个用户。</div>
          <template v-else>
            <div data-testid="admin-user-import-failed" role="alert" :style="errorStyle">导入失败：共 {{ importResult.errors.length }} 处错误，本次没有导入任何用户。请按下表修正后重新上传。</div>
            <div :style="errorTableWrapStyle">
              <table data-testid="admin-user-import-errors" :style="tableStyle">
                <thead>
                  <tr>
                    <th scope="col" :style="thStyle({ width: '70px' })">行号</th>
                    <th scope="col" :style="thStyle({ width: '140px' })">用户名</th>
                    <th scope="col" :style="thStyle({})">问题</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(err, idx) in importResult.errors" :key="idx" data-testid="admin-user-import-error-row">
                    <td :style="tdStyle">{{ err.row }}</td>
                    <td :style="tdStyle">{{ err.username || '—' }}</td>
                    <td :style="tdStyle">{{ err.message }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
      <template #footer>
        <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">{{ importResult && importResult.errors.length === 0 ? '完成' : '关闭' }}</button>
        <button type="button" data-testid="admin-user-import-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting || !importFile" @click="submitImport">{{ submitting ? '上传中…' : '上传并导入' }}</button>
      </template>
    </AdminModal>
  </div>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import AdminModal from '../components/AdminModal.vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import AdminPager from '../components/admin/AdminPager.vue'
import AdminTableCard from '../components/admin/AdminTableCard.vue'
import {
  errorStyle,
  fieldStyle,
  filterBarStyle,
  ghostBtnStyle,
  noticeStyle,
  pageStyle,
  primaryBtnStyle,
  tableStyle,
  tagBase,
  tdStyle,
  thStyle
} from '../components/admin/adminStyle.js'
import { formatDateTime, onEnter } from '../components/admin/adminUtils.js'
import { usePagedList } from '../components/admin/usePagedList.js'
import { useSessionStore } from '../store/session.js'
import {
  changeUserRole,
  createUser,
  downloadImportTemplate,
  importUsers,
  listUsers,
  resetUserPassword,
  setUserStatus,
  updateUser
} from '../api/systemAdmin.js'
import { saveBlob } from '../utils/downloadBlob.js'
import { ROLE_ADMIN, ROLE_STUDENT, ROLE_TEACHER, roleLabel } from '../utils/roles.js'

const session = useSessionStore()

const PAGE_SIZE = 20
const MAX_IMPORT_BYTES = 2 * 1024 * 1024

const columns = [
  { key: 'id', label: 'ID', width: '70px' },
  { key: 'username', label: '用户名' },
  { key: 'realName', label: '姓名' },
  { key: 'role', label: '角色', width: '90px' },
  { key: 'status', label: '状态', width: '80px' },
  { key: 'createdAt', label: '创建时间', width: '150px' },
  { key: 'actions', label: '操作' }
]

// ── 列表状态 ──
// draft 是输入框里正在编辑的筛选；applied 是上一次点「搜索」时生效的筛选（翻页沿用它）
const notice = ref('')
const actionError = ref('')
const busyId = ref(null)
let disposed = false

const { draft, page, loading, loadError, records, total, load, search: runSearch, goPage } = usePagedList({
  initialFilters: { keyword: '', role: '', status: '' },
  pageSize: PAGE_SIZE,
  errorMessage: '加载用户列表失败',
  fetchPage: listUsers,
  toQuery({ keyword, role, status }) {
    const query = {}
    const kw = String(keyword || '').trim()
    if (kw) query.keyword = kw
    if (role !== '' && role != null) query.role = Number(role)
    if (status !== '' && status != null) query.status = Number(status)
    return query
  }
})

// ── 弹窗状态 ──
const modal = ref(null) // 'create' | 'edit' | 'reset' | 'import' | null
const target = ref(null)
const submitting = ref(false)
const modalError = ref('')

const createForm = reactive({ username: '', realName: '', password: '', role: ROLE_STUDENT })
const editForm = reactive({ realName: '' })
const resetForm = reactive({ newPassword: '' })
const generatedPassword = ref('')
const copyState = ref('') // '' | 'ok' | 'fail'

const importInputRef = ref(null)
const importFile = ref(null)
const importResult = ref(null)
const templateLoading = ref(false)

// ── 工具 ──
function displayName(row) {
  return row.realName ? `${row.realName}（${row.username}）` : row.username
}

function isSelf(row) {
  return session.userId != null && row.id === session.userId
}

function clearFeedback() {
  notice.value = ''
  actionError.value = ''
}

// ── 加载 ──
function search() {
  clearFeedback()
  return runSearch()
}

// ── 行内操作 ──
async function runAction(row, task, successMessage) {
  if (busyId.value !== null) return
  busyId.value = row.id
  clearFeedback()
  try {
    await task()
    if (disposed) return
    notice.value = successMessage
    await load()
  } catch (err) {
    if (disposed) return
    actionError.value = err?.message || '操作失败'
  } finally {
    busyId.value = null
  }
}

async function toggleStatus(row) {
  const disabling = row.status === 1
  if (disabling && !window.confirm(`确定要禁用用户「${displayName(row)}」吗？该账号将立即无法登录，已登录的会话也会失效。`)) {
    return
  }
  await runAction(
    row,
    () => setUserStatus(row.id, disabling ? 0 : 1),
    disabling ? `已禁用「${displayName(row)}」` : `已启用「${displayName(row)}」`
  )
}

async function changeRole(row) {
  const nextRole = row.role === ROLE_TEACHER ? ROLE_STUDENT : ROLE_TEACHER
  if (!window.confirm(`确定将「${displayName(row)}」的角色由「${roleLabel(row.role)}」改为「${roleLabel(nextRole)}」吗？`)) {
    return
  }
  await runAction(
    row,
    () => changeUserRole(row.id, nextRole),
    `已将「${displayName(row)}」改为${roleLabel(nextRole)}`
  )
}

// ── 弹窗通用 ──
function closeModal() {
  if (submitting.value) return
  modal.value = null
  target.value = null
  modalError.value = ''
  // 明文新密码不在内存里多留
  generatedPassword.value = ''
  copyState.value = ''
  resetForm.newPassword = ''
  createForm.password = ''
  importFile.value = null
  importResult.value = null
}

function openModal(name, row = null) {
  clearFeedback()
  modalError.value = ''
  target.value = row
  modal.value = name
}

// ── 新建 ──
function openCreate() {
  createForm.username = ''
  createForm.realName = ''
  createForm.password = ''
  createForm.role = ROLE_STUDENT
  openModal('create')
}

function validateRealName(realName) {
  if (!realName) return '请填写姓名'
  if (realName.length > 64) return '姓名不能超过 64 个字符'
  return ''
}

function validatePassword(password) {
  if (password.length < 8 || password.length > 72) return '密码长度需为 8–72 个字符'
  return ''
}

async function submitCreate() {
  if (submitting.value) return
  const username = createForm.username.trim()
  const realName = createForm.realName.trim()
  const password = createForm.password
  let problem = ''
  if (username.length < 3 || username.length > 64) problem = '用户名长度需为 3–64 个字符'
  else problem = validateRealName(realName) || validatePassword(password)
  if (problem) {
    modalError.value = problem
    return
  }
  submitting.value = true
  modalError.value = ''
  try {
    await createUser({ username, password, realName, role: Number(createForm.role) })
    submitting.value = false
    closeModal()
    notice.value = `已创建${roleLabel(Number(createForm.role))}「${realName}（${username}）」`
    page.value = 1
    await load()
  } catch (err) {
    modalError.value = err?.message || '创建失败'
  } finally {
    submitting.value = false
  }
}

// ── 编辑姓名 ──
function openEdit(row) {
  editForm.realName = row.realName || ''
  openModal('edit', row)
}

async function submitEdit() {
  if (submitting.value || !target.value) return
  const realName = editForm.realName.trim()
  const problem = validateRealName(realName)
  if (problem) {
    modalError.value = problem
    return
  }
  const row = target.value
  submitting.value = true
  modalError.value = ''
  try {
    await updateUser(row.id, { realName })
    submitting.value = false
    closeModal()
    notice.value = `已更新「${row.username}」的姓名`
    await load()
  } catch (err) {
    modalError.value = err?.message || '保存失败'
  } finally {
    submitting.value = false
  }
}

// ── 重置密码 ──
function openReset(row) {
  resetForm.newPassword = ''
  generatedPassword.value = ''
  copyState.value = ''
  openModal('reset', row)
}

async function submitReset() {
  if (submitting.value || !target.value) return
  const newPassword = resetForm.newPassword
  if (newPassword) {
    const problem = validatePassword(newPassword)
    if (problem) {
      modalError.value = problem
      return
    }
  }
  const row = target.value
  submitting.value = true
  modalError.value = ''
  try {
    const res = await resetUserPassword(row.id, newPassword || undefined)
    notice.value = `已重置「${row.username}」的密码`
    if (res && res.password) {
      // 系统生成的新密码：留在弹窗里显示一次
      generatedPassword.value = res.password
      resetForm.newPassword = ''
    } else {
      submitting.value = false
      closeModal()
    }
  } catch (err) {
    modalError.value = err?.message || '重置失败'
  } finally {
    submitting.value = false
  }
}

async function copyGeneratedPassword() {
  const text = generatedPassword.value
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copyState.value = 'ok'
    return
  } catch {
    /* 无剪贴板权限 / 非安全上下文 → 走降级 */
  }
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    copyState.value = ok ? 'ok' : 'fail'
  } catch {
    copyState.value = 'fail'
  }
}

// ── 批量导入 ──
function openImport() {
  importFile.value = null
  importResult.value = null
  openModal('import')
}

async function downloadTemplate() {
  if (templateLoading.value) return
  templateLoading.value = true
  modalError.value = ''
  try {
    const blob = await downloadImportTemplate()
    saveBlob(blob, '用户导入模板.xlsx')
  } catch (err) {
    modalError.value = err?.message || '下载模板失败'
  } finally {
    templateLoading.value = false
  }
}

function onImportFileChange(event) {
  const file = event.target.files && event.target.files[0]
  importResult.value = null
  modalError.value = ''
  if (!file) {
    importFile.value = null
    return
  }
  if (!/\.xlsx$/i.test(file.name)) {
    importFile.value = null
    modalError.value = '仅支持 .xlsx 文件'
    return
  }
  if (file.size > MAX_IMPORT_BYTES) {
    importFile.value = null
    modalError.value = '文件不能超过 2MB'
    return
  }
  importFile.value = file
}

async function submitImport() {
  if (submitting.value || !importFile.value) return
  submitting.value = true
  modalError.value = ''
  importResult.value = null
  try {
    const res = await importUsers(importFile.value)
    if (disposed) return
    const errors = Array.isArray(res?.errors) ? res.errors : []
    importResult.value = { created: Number(res?.created) || 0, errors }
    if (errors.length === 0) {
      // 成功：清掉已用文件，刷新列表（弹窗保留，展示结果）
      importFile.value = null
      if (importInputRef.value) importInputRef.value.value = ''
      notice.value = `批量导入完成，创建 ${importResult.value.created} 个用户`
      page.value = 1
      await load()
    }
  } catch (err) {
    modalError.value = err?.message || '导入失败'
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
})

load()

// ── 样式（共用部分见 components/admin/adminStyle.js） ──
// 主表格有最小宽度：窄屏下由外层 overflow-x 横向滚动，而不是把姓名列挤成竖排
const usersTableStyle = { ...tableStyle, minWidth: '880px' }

const actionsStyle = {
  display: 'flex',
  gap: '6px',
  flexWrap: 'wrap'
}

const actBtnStyle = {
  height: '26px',
  padding: '0 9px',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '6px',
  color: 'var(--ink)',
  fontSize: '12px',
  cursor: 'pointer',
  whiteSpace: 'nowrap'
}

const actDangerStyle = { ...actBtnStyle, color: 'var(--acc)' }
const actOkStyle = { ...actBtnStyle, color: 'var(--ok)' }

const selfTagStyle = {
  marginLeft: '6px',
  fontSize: '11px',
  fontWeight: 400,
  color: 'var(--mut)',
  border: '1px solid var(--line)',
  borderRadius: '999px',
  padding: '0 7px'
}

function roleTagStyle(role) {
  if (role === ROLE_ADMIN) return { ...tagBase(), color: 'var(--acc)', background: 'var(--accSoft)' }
  if (role === ROLE_TEACHER) return { ...tagBase(), color: 'var(--ok)', background: 'var(--okSoft)' }
  return { ...tagBase(), color: 'var(--mut)', background: 'var(--card2)' }
}

function statusTagStyle(status) {
  if (status === 1) return { ...tagBase(), color: 'var(--ok)', background: 'var(--okSoft)' }
  return { ...tagBase(), color: 'var(--mut)', background: 'var(--card2)', border: '1px solid var(--line)' }
}

// ── 弹窗内样式 ──
const formStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: '8px'
}

const labelStyle = {
  fontSize: '12px',
  color: 'var(--mut)',
  marginTop: '4px'
}

const hintLineStyle = {
  fontSize: '12.5px',
  color: 'var(--mut)',
  lineHeight: 1.7
}

const modalErrorStyle = {
  fontSize: '12.5px',
  color: 'var(--acc)',
  background: 'var(--accSoft)',
  borderRadius: '8px',
  padding: '8px 12px',
  marginTop: '4px'
}

const fileInputStyle = {
  fontSize: '12.5px',
  color: 'var(--ink)'
}

const passwordBoxStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  background: 'var(--card2)',
  border: '1px dashed var(--acc)',
  borderRadius: '8px',
  padding: '10px 12px'
}

const passwordCodeStyle = {
  flex: 1,
  minWidth: 0,
  fontFamily: "'Consolas', 'Fira Mono', monospace",
  fontSize: '16px',
  letterSpacing: '1px',
  color: 'var(--ink)',
  userSelect: 'all',
  wordBreak: 'break-all'
}

const errorTableWrapStyle = {
  maxHeight: '240px',
  overflow: 'auto',
  border: '1px solid var(--line)',
  borderRadius: '8px'
}
</script>
