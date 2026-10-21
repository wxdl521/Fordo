<template>
  <div data-testid="admin-courses-page" :style="pageStyle">
    <!-- 标题 -->
    <AdminPageHeader title="课程管理" subtitle="查看全部课程（含已归档），指定任课教师建课、转让教师、发布 / 下架、归档 / 恢复。">
      <template #actions>
        <button type="button" data-testid="admin-course-create" class="wj-admin-btn" :style="primaryBtnStyle" @click="openCreate">新建课程</button>
      </template>
    </AdminPageHeader>

    <!-- 筛选栏 -->
    <div data-testid="admin-courses-filters" :style="filterBarStyle">
      <input
        v-model="draft.keyword"
        type="text"
        data-testid="admin-courses-keyword"
        aria-label="按课程名称或编码搜索"
        placeholder="搜索课程名称或编码"
        class="wj-admin-field"
        :style="{ ...fieldStyle, width: '220px' }"
        @keydown.enter="onEnter($event, search)"
      />
      <select v-model="draft.status" data-testid="admin-courses-status" aria-label="按发布状态筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '120px' }" @change="search">
        <option value="">全部状态</option>
        <option :value="1">已发布</option>
        <option :value="0">草稿</option>
      </select>
      <select v-model="draft.archived" data-testid="admin-courses-archived" aria-label="按归档状态筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '120px' }" @change="search">
        <option value="">含已归档</option>
        <option value="false">仅未归档</option>
        <option value="true">仅已归档</option>
      </select>
      <select v-model="draft.teacherId" data-testid="admin-courses-teacher" aria-label="按任课教师筛选" class="wj-admin-field" :style="{ ...fieldStyle, width: '170px' }" @change="search">
        <option value="">全部教师</option>
        <option v-for="t in teachers" :key="t.id" :value="t.id">{{ teacherLabel(t) }}</option>
      </select>
      <button type="button" data-testid="admin-courses-search" class="wj-admin-btn" :style="primaryBtnStyle" @click="search">搜索</button>
      <button type="button" data-testid="admin-courses-reset" class="wj-admin-btn" :style="ghostBtnStyle" @click="reset">重置</button>
    </div>

    <!-- 操作反馈 -->
    <div v-if="notice" data-testid="admin-courses-notice" role="status" :style="noticeStyle">{{ notice }}</div>
    <div v-if="actionError" data-testid="admin-courses-action-error" role="alert" :style="errorStyle">{{ actionError }}</div>
    <div v-if="loadError" data-testid="admin-courses-error" role="alert" :style="errorStyle">{{ loadError }}</div>

    <!-- 表格 -->
    <AdminTableCard testid-prefix="admin-courses" :loading="loading" :empty="!loadError && records.length === 0" empty-text="没有符合条件的课程。">
      <table data-testid="admin-courses-table" :style="coursesTableStyle">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id" :data-testid="'admin-course-row-' + row.id" class="wj-admin-row">
            <td :style="{ ...tdStyle, fontWeight: 500 }">{{ row.name }}</td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', fontFamily: monoFont, fontSize: '12px' }">{{ row.code || '—' }}</td>
            <td :style="tdStyle" :data-testid="'admin-course-teacher-' + row.id">{{ row.teacherName || (row.teacherId != null ? `教师 #${row.teacherId}` : '—') }}</td>
            <td :style="tdStyle">
              <span :data-testid="'admin-course-status-tag-' + row.id" :style="statusTag(row)">{{ statusText(row) }}</span>
            </td>
            <td :style="numTdStyle">{{ row.nodeCount ?? 0 }}</td>
            <td :style="numTdStyle">{{ row.studentCount ?? 0 }}</td>
            <td :style="numTdStyle">{{ row.clazzCount ?? 0 }}</td>
            <td :style="{ ...tdStyle, color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ formatDateTime(row.createdAt) }}</td>
            <td :style="tdStyle">
              <div :style="actionsStyle">
                <button
                  v-if="!row.archived"
                  type="button"
                  :data-testid="'admin-course-publish-' + row.id"
                  class="wj-admin-act"
                  :style="row.status === 1 ? actDangerStyle : actOkStyle"
                  :disabled="busyId !== null"
                  @click="togglePublished(row)"
                >{{ row.status === 1 ? '下架' : '发布' }}</button>
                <button type="button" :data-testid="'admin-course-transfer-' + row.id" class="wj-admin-act" :style="actBtnStyle" :disabled="busyId !== null" @click="openTransfer(row)">转让教师</button>
                <button
                  v-if="!row.archived"
                  type="button"
                  :data-testid="'admin-course-graph-' + row.id"
                  class="wj-admin-act"
                  :style="actBtnStyle"
                  title="切到教师端并定位到这门课的图谱审核台"
                  @click="openInTeacher(row)"
                >编辑图谱</button>
                <button
                  v-if="!row.archived"
                  type="button"
                  :data-testid="'admin-course-archive-' + row.id"
                  class="wj-admin-act"
                  :style="actDangerStyle"
                  :disabled="busyId !== null"
                  @click="archive(row)"
                >归档</button>
                <button
                  v-else
                  type="button"
                  :data-testid="'admin-course-restore-' + row.id"
                  class="wj-admin-act"
                  :style="actOkStyle"
                  :disabled="busyId !== null"
                  @click="restore(row)"
                >恢复</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </AdminTableCard>

    <!-- 分页 -->
    <AdminPager testid-prefix="admin-courses" :total="total" :page="page" :page-size="PAGE_SIZE" :loading="loading" @change="goPage" />

    <!-- 弹窗：新建课程 -->
    <AdminModal v-if="modal === 'create'" title="新建课程" testid="admin-course-create-modal" @close="closeModal">
      <div :style="formStyle">
        <div :style="hintLineStyle">课程会归在所选教师名下（该教师登录教师端即可看到并管理）。新课程默认为草稿，需发布后学生才能选课。</div>
        <label for="admin-course-create-name" :style="labelStyle">课程名称（最多 128 个字符）</label>
        <input id="admin-course-create-name" v-model="createForm.name" data-testid="admin-course-create-name" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, submitCreate)" />
        <label for="admin-course-create-teacher" :style="labelStyle">任课教师</label>
        <select id="admin-course-create-teacher" v-model="createForm.teacherId" data-testid="admin-course-create-teacher" class="wj-admin-field" :style="fieldStyle">
          <option value="">请选择教师</option>
          <option v-for="t in teachers" :key="t.id" :value="t.id">{{ teacherLabel(t) }}</option>
        </select>
        <div v-if="teachersError" data-testid="admin-course-create-teachers-error" role="alert" :style="modalErrorStyle">{{ teachersError }}</div>
        <div v-if="modalError" data-testid="admin-course-create-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">取消</button>
        <button type="button" data-testid="admin-course-create-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitCreate">{{ submitting ? '创建中…' : '创建' }}</button>
      </template>
    </AdminModal>

    <!-- 弹窗：转让教师 -->
    <AdminModal v-if="modal === 'transfer' && target" title="转让任课教师" testid="admin-course-transfer-modal" @close="closeModal">
      <div :style="formStyle">
        <div :style="hintLineStyle">
          课程：{{ target.name }}<br />
          当前任课教师：{{ target.teacherName || '—' }}
        </div>
        <div :style="hintLineStyle">转让后，原教师的教师端将不再显示这门课，新教师的教师端会出现它；学生的选课与学习数据不受影响。</div>
        <label for="admin-course-transfer-teacher" :style="labelStyle">转让给</label>
        <select id="admin-course-transfer-teacher" v-model="transferForm.teacherId" data-testid="admin-course-transfer-teacher" class="wj-admin-field" :style="fieldStyle">
          <option value="">请选择教师</option>
          <option v-for="t in teachers" :key="t.id" :value="t.id" :disabled="t.id === target.teacherId">{{ teacherLabel(t) }}{{ t.id === target.teacherId ? '（当前）' : '' }}</option>
        </select>
        <div v-if="teachersError" data-testid="admin-course-transfer-teachers-error" role="alert" :style="modalErrorStyle">{{ teachersError }}</div>
        <div v-if="modalError" data-testid="admin-course-transfer-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeModal">取消</button>
        <button type="button" data-testid="admin-course-transfer-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitTransfer">{{ submitting ? '转让中…' : '确认转让' }}</button>
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
  actOkStyle,
  actionsStyle,
  errorStyle,
  fieldStyle,
  filterBarStyle,
  formStyle,
  ghostBtnStyle,
  hintLineStyle,
  labelStyle,
  modalErrorStyle,
  noticeStyle,
  pageStyle,
  primaryBtnStyle,
  tableStyle,
  tagStyle,
  tdStyle,
  thStyle
} from '../components/admin/adminStyle.js'
import { formatDateTime, onEnter } from '../components/admin/adminUtils.js'
import { usePagedList } from '../components/admin/usePagedList.js'
import {
  archiveAdminCourse,
  createAdminCourse,
  listAdminCourses,
  listTeachers,
  restoreAdminCourse,
  setAdminCoursePublished,
  transferCourseOwner
} from '../api/systemAdmin.js'
import { useCourseStore } from '../store/course.js'

const router = useRouter()
const courseStore = useCourseStore()

const PAGE_SIZE = 20
const NAME_MAX = 128

const columns = [
  { key: 'name', label: '课程名称' },
  { key: 'code', label: '编码', width: '110px' },
  { key: 'teacher', label: '任课教师', width: '130px' },
  { key: 'status', label: '状态', width: '80px' },
  { key: 'nodes', label: '节点数', width: '74px' },
  { key: 'students', label: '学生数', width: '74px' },
  { key: 'clazzes', label: '班级数', width: '74px' },
  { key: 'createdAt', label: '创建时间', width: '150px' },
  { key: 'actions', label: '操作' }
]

const notice = ref('')
const actionError = ref('')
const busyId = ref(null)
let disposed = false

// ── 列表 ──
const { draft, page, loading, loadError, records, total, load, search: runSearch, reset: runReset, goPage } = usePagedList({
  initialFilters: { keyword: '', status: '', archived: '', teacherId: '' },
  pageSize: PAGE_SIZE,
  errorMessage: '加载课程列表失败',
  fetchPage: listAdminCourses,
  toQuery({ keyword, status, archived, teacherId }) {
    const query = {}
    const kw = String(keyword || '').trim()
    if (kw) query.keyword = kw
    if (status !== '' && status != null) query.status = Number(status)
    if (archived !== '' && archived != null) query.archived = archived === true || archived === 'true'
    if (teacherId !== '' && teacherId != null) query.teacherId = Number(teacherId)
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

// ── 教师下拉（筛选 / 建课 / 转让共用） ──
const teachers = ref([])
const teachersError = ref('')

function teacherLabel(t) {
  const name = t.realName || t.username
  return t.realName && t.username ? `${t.realName}（${t.username}）` : name
}

async function loadTeachers() {
  try {
    const list = await listTeachers()
    if (disposed) return
    teachers.value = Array.isArray(list) ? list : []
    teachersError.value = ''
  } catch (err) {
    if (disposed) return
    teachersError.value = err?.message || '加载教师列表失败'
  }
}

// ── 展示 ──
function statusText(row) {
  if (row.archived) return '已归档'
  return row.status === 1 ? '已发布' : '草稿'
}

function statusTag(row) {
  if (row.archived) return tagStyle('mut')
  return row.status === 1 ? tagStyle('ok') : tagStyle('warn')
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

async function togglePublished(row) {
  const publishing = row.status !== 1
  if (!publishing && !window.confirm(`确定下架课程「${row.name}」吗？下架后学生端将无法再进入该课程（学生的选课记录与学习数据保留），可随时重新发布。`)) {
    return
  }
  await runAction(
    row,
    () => setAdminCoursePublished(row.id, publishing),
    publishing ? `已发布「${row.name}」` : `已下架「${row.name}」`
  )
}

async function archive(row) {
  if (!window.confirm(`确定归档课程「${row.name}」吗？归档后教师端与学生端都不再显示它（图谱、题库与学习数据保留），可在本页随时恢复。`)) {
    return
  }
  await runAction(row, () => archiveAdminCourse(row.id), `已归档「${row.name}」`)
}

async function restore(row) {
  await runAction(row, () => restoreAdminCourse(row.id), `已恢复「${row.name}」`)
}

/**
 * 进入教师端编辑这门课的图谱：教师端各页共享 course store 的 teacherCourseId（持久化在 localStorage），
 * 图谱审核台加载课程列表后优先选中它，所以这里先写入再跳转即可。
 */
function openInTeacher(row) {
  courseStore.setTeacherCourseId(row.id)
  router.push('/teacher/graph')
}

// ── 弹窗通用 ──
const modal = ref(null) // 'create' | 'transfer' | null
const target = ref(null)
const submitting = ref(false)
const modalError = ref('')

const createForm = reactive({ name: '', teacherId: '' })
const transferForm = reactive({ teacherId: '' })

function closeModal() {
  if (submitting.value) return
  modal.value = null
  target.value = null
  modalError.value = ''
}

function openModal(name, row = null) {
  clearFeedback()
  modalError.value = ''
  target.value = row
  modal.value = name
  // 教师列表加载失败过 → 打开弹窗时再试一次
  if (teachersError.value) void loadTeachers()
}

// ── 新建 ──
function openCreate() {
  createForm.name = ''
  createForm.teacherId = ''
  openModal('create')
}

async function submitCreate() {
  if (submitting.value) return
  const name = createForm.name.trim()
  const teacherId = createForm.teacherId
  let problem = ''
  if (!name) problem = '请填写课程名称'
  else if (name.length > NAME_MAX) problem = `课程名称不能超过 ${NAME_MAX} 个字符`
  else if (teacherId === '' || teacherId == null) problem = '请选择任课教师'
  if (problem) {
    modalError.value = problem
    return
  }
  const teacher = teachers.value.find((t) => t.id === Number(teacherId))
  submitting.value = true
  modalError.value = ''
  try {
    await createAdminCourse({ name, teacherId: Number(teacherId) })
    submitting.value = false
    closeModal()
    notice.value = `已为${teacher ? `「${teacherLabel(teacher)}」` : '所选教师'}创建课程「${name}」（草稿，发布后学生可选课）`
    page.value = 1
    await load()
  } catch (err) {
    modalError.value = err?.message || '创建失败'
  } finally {
    submitting.value = false
  }
}

// ── 转让教师 ──
function openTransfer(row) {
  transferForm.teacherId = ''
  openModal('transfer', row)
}

async function submitTransfer() {
  if (submitting.value || !target.value) return
  const row = target.value
  const teacherId = transferForm.teacherId
  if (teacherId === '' || teacherId == null) {
    modalError.value = '请选择要转让给的教师'
    return
  }
  if (Number(teacherId) === row.teacherId) {
    modalError.value = '该教师已是这门课的任课教师'
    return
  }
  const teacher = teachers.value.find((t) => t.id === Number(teacherId))
  submitting.value = true
  modalError.value = ''
  try {
    await transferCourseOwner(row.id, Number(teacherId))
    submitting.value = false
    closeModal()
    notice.value = `已将「${row.name}」转让给${teacher ? `「${teacherLabel(teacher)}」` : '所选教师'}`
    await load()
  } catch (err) {
    modalError.value = err?.message || '转让失败'
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
})

void loadTeachers()
void load()

// ── 样式（共用部分见 components/admin/adminStyle.js） ──
// 主表格有最小宽度：窄屏下由外层 overflow-x 横向滚动，而不是把课程名挤成竖排
const coursesTableStyle = { ...tableStyle, minWidth: '1040px' }
const numTdStyle = { ...tdStyle, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }
const monoFont = "'Consolas', 'Fira Mono', monospace"
</script>
