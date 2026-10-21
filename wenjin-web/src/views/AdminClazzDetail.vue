<template>
  <div data-testid="admin-clazz-detail-page" :style="pageStyle">
    <router-link to="/admin/clazzes" data-testid="admin-clazz-back" :style="backLinkStyle">← 班级列表</router-link>

    <AdminPageHeader :title="detail ? detail.name : '班级详情'" subtitle="维护班级信息、学生名单，以及班级关联的课程。" />

    <!-- 操作反馈 -->
    <div v-if="notice" data-testid="admin-clazz-notice" role="status" :style="noticeStyle">{{ notice }}</div>
    <div v-if="actionError" data-testid="admin-clazz-action-error" role="alert" :style="errorStyle">{{ actionError }}</div>
    <div v-if="loadError" data-testid="admin-clazz-error" role="alert" :style="errorStyle">{{ loadError }}</div>
    <div v-if="loading && !detail" data-testid="admin-clazz-loading" :style="hintStyle">加载中…</div>

    <template v-if="detail">
      <!-- 基本信息 -->
      <section aria-labelledby="admin-clazz-h-info" :style="sectionStyle">
        <h2 id="admin-clazz-h-info" :style="h2Style">基本信息</h2>
        <div :style="infoCardStyle">
          <div :style="infoFieldsStyle">
            <div :style="fieldColStyle">
              <label for="admin-clazz-name" :style="labelStyle">班级名称</label>
              <input id="admin-clazz-name" v-model="editForm.name" data-testid="admin-clazz-name" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, saveInfo)" />
            </div>
            <div :style="{ ...fieldColStyle, flex: '2 1 280px' }">
              <label for="admin-clazz-description" :style="labelStyle">说明（可选）</label>
              <input id="admin-clazz-description" v-model="editForm.description" data-testid="admin-clazz-description" type="text" autocomplete="off" class="wj-admin-field" :style="fieldStyle" @keydown.enter="onEnter($event, saveInfo)" />
            </div>
          </div>
          <div v-if="infoError" data-testid="admin-clazz-info-error" role="alert" :style="modalErrorStyle">{{ infoError }}</div>
          <div :style="infoFooterStyle">
            <span :style="metaStyle">学生 {{ students.length }} 人 · 课程 {{ linkedCourses.length }} 门 · 创建于 {{ formatDateTime(detail.createdAt) }}</span>
            <button type="button" data-testid="admin-clazz-save" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="busy" @click="saveInfo">{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </div>
      </section>

      <!-- 学生 -->
      <section aria-labelledby="admin-clazz-h-students" :style="sectionStyle">
        <div :style="sectionHeadStyle">
          <h2 id="admin-clazz-h-students" :style="{ ...h2Style, margin: 0 }">班级学生<span :style="h2SubStyle">{{ students.length }} 人</span></h2>
          <button type="button" data-testid="admin-clazz-add-students" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="busy" @click="openAddStudents">添加学生</button>
        </div>
        <div :style="hintLineStyle">一个学生至多属于一个班级；添加时若学生已在别的班，会自动转到本班。加入班级后会自动选上班级关联的全部课程。</div>
        <AdminTableCard testid-prefix="admin-clazz-students" :empty="students.length === 0" empty-text="班级里还没有学生。">
          <table data-testid="admin-clazz-students-table" :style="subTableStyle">
            <thead>
              <tr>
                <th v-for="col in studentColumns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in students" :key="s.id" :data-testid="'admin-clazz-student-row-' + s.id" class="wj-admin-row">
                <td :style="{ ...tdStyle, fontWeight: 500 }">{{ s.username }}</td>
                <td :style="tdStyle">{{ s.realName || '—' }}</td>
                <td :style="tdStyle">
                  <span :style="tagStyle(s.status === 1 ? 'ok' : 'mut')">{{ s.status === 1 ? '启用' : '禁用' }}</span>
                </td>
                <td :style="tdStyle">
                  <button type="button" :data-testid="'admin-clazz-student-remove-' + s.id" class="wj-admin-act" :style="actDangerStyle" :disabled="busy" @click="removeStudent(s)">移出班级</button>
                </td>
              </tr>
            </tbody>
          </table>
        </AdminTableCard>
      </section>

      <!-- 课程 -->
      <section aria-labelledby="admin-clazz-h-courses" :style="sectionStyle">
        <div :style="sectionHeadStyle">
          <h2 id="admin-clazz-h-courses" :style="{ ...h2Style, margin: 0 }">关联课程<span :style="h2SubStyle">{{ linkedCourses.length }} 门</span></h2>
          <div :style="pickerStyle">
            <select v-model="courseToAdd" data-testid="admin-clazz-course-select" aria-label="选择要关联的课程" class="wj-admin-field" :style="{ ...fieldStyle, width: '240px' }">
              <option value="">{{ availableCourses.length ? '选择要关联的课程' : '没有可关联的课程' }}</option>
              <option v-for="c in availableCourses" :key="c.id" :value="c.id">{{ c.name }}{{ c.published ? '' : '（草稿）' }}</option>
            </select>
            <button type="button" data-testid="admin-clazz-add-course" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="busy || courseToAdd === ''" @click="addCourse">关联课程</button>
          </div>
        </div>
        <div :style="hintLineStyle">关联后，班级内全部学生会自动选上这门课（已选的跳过），之后加入班级的学生也会自动选上。解除关联不会删除任何选课记录。</div>
        <div v-if="courseOptionsError" data-testid="admin-clazz-courses-error" role="alert" :style="errorStyle">{{ courseOptionsError }}</div>
        <AdminTableCard testid-prefix="admin-clazz-courses" :empty="linkedCourses.length === 0" empty-text="还没有关联课程。">
          <table data-testid="admin-clazz-courses-table" :style="subTableStyle">
            <thead>
              <tr>
                <th v-for="col in courseColumns" :key="col.key" scope="col" :style="thStyle(col)">{{ col.label }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in linkedCourses" :key="c.id" :data-testid="'admin-clazz-course-row-' + c.id" class="wj-admin-row">
                <td :style="{ ...tdStyle, fontWeight: 500 }">{{ c.name }}</td>
                <td :style="tdStyle">
                  <span :style="courseTag(c)">{{ courseStatusText(c) }}</span>
                </td>
                <td :style="tdStyle">
                  <button type="button" :data-testid="'admin-clazz-course-remove-' + c.id" class="wj-admin-act" :style="actDangerStyle" :disabled="busy" @click="removeCourse(c)">解除关联</button>
                </td>
              </tr>
            </tbody>
          </table>
        </AdminTableCard>
      </section>
    </template>

    <!-- 弹窗：添加学生 -->
    <AdminModal v-if="studentsModal" title="添加学生" testid="admin-clazz-students-modal" width="560px" @close="closeStudentsModal">
      <!-- 结果 -->
      <div v-if="studentsResult" data-testid="admin-clazz-students-result" :style="formStyle">
        <div data-testid="admin-clazz-students-summary" role="status" :style="noticeStyle">
          成功加入 {{ studentsResult.added }} 人；为他们新增选课记录 {{ studentsResult.enrolled }} 条。
        </div>
        <div v-if="studentsResult.movedFrom.length" data-testid="admin-clazz-students-moved" :style="resultBlockStyle">
          <div :style="resultTitleStyle">已从其他班级转入（{{ studentsResult.movedFrom.length }} 人）</div>
          <ul :style="resultListStyle">
            <li v-for="m in studentsResult.movedFrom" :key="m.studentId">{{ studentName(m.studentId) }}：原属「{{ m.fromClazzName }}」</li>
          </ul>
        </div>
        <div v-if="studentsResult.notFound.length" data-testid="admin-clazz-students-notfound" :style="{ ...resultBlockStyle, borderColor: 'var(--acc)' }">
          <div :style="{ ...resultTitleStyle, color: 'var(--acc)' }">未找到或不是学生（{{ studentsResult.notFound.length }} 个，未加入）</div>
          <div :style="resultNamesStyle">{{ studentsResult.notFound.join('、') }}</div>
        </div>
      </div>
      <!-- 输入 -->
      <div v-else :style="formStyle">
        <label for="admin-clazz-students-input" :style="labelStyle">学生用户名（每行一个，最多 {{ MAX_USERNAMES }} 个）</label>
        <textarea
          id="admin-clazz-students-input"
          v-model="studentsText"
          data-testid="admin-clazz-students-input"
          rows="9"
          placeholder="例如：&#10;stu2201001&#10;stu2201002"
          class="wj-admin-field"
          :style="textareaStyle"
        ></textarea>
        <div :style="hintLineStyle">只能添加学生账号；已在其他班级的学生会被转到本班；不存在或不是学生的用户名会在结果里列出，不影响其余学生。</div>
        <div v-if="modalError" data-testid="admin-clazz-students-error" role="alert" :style="modalErrorStyle">{{ modalError }}</div>
      </div>
      <template #footer>
        <template v-if="studentsResult">
          <button v-if="studentsResult.notFound.length" type="button" data-testid="admin-clazz-students-again" class="wj-admin-btn" :style="ghostBtnStyle" @click="addMoreStudents">修改后重试未找到的</button>
          <button type="button" data-testid="admin-clazz-students-done" class="wj-admin-btn" :style="primaryBtnStyle" @click="closeStudentsModal">完成</button>
        </template>
        <template v-else>
          <button type="button" class="wj-admin-btn" :style="ghostBtnStyle" @click="closeStudentsModal">取消</button>
          <button type="button" data-testid="admin-clazz-students-submit" class="wj-admin-btn" :style="primaryBtnStyle" :disabled="submitting" @click="submitStudents">{{ submitting ? '添加中…' : '添加' }}</button>
        </template>
      </template>
    </AdminModal>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AdminModal from '../components/AdminModal.vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import AdminTableCard from '../components/admin/AdminTableCard.vue'
import {
  actDangerStyle,
  errorStyle,
  fieldStyle,
  formStyle,
  ghostBtnStyle,
  hintLineStyle,
  hintStyle,
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
import { formatDateTime, isAbortError, onEnter } from '../components/admin/adminUtils.js'
import {
  addClazzCourse,
  addClazzStudents,
  getClazzDetail,
  removeClazzCourse,
  removeClazzStudent,
  updateClazz
} from '../api/systemAdmin.js'
import { fetchTeacherCourses } from '../api/teacher.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const route = useRoute()

const MAX_USERNAMES = 500
const NAME_MAX = 64
const DESCRIPTION_MAX = 255

const studentColumns = [
  { key: 'username', label: '用户名' },
  { key: 'realName', label: '姓名' },
  { key: 'status', label: '账号状态' },
  { key: 'actions', label: '操作' }
]
const courseColumns = [
  { key: 'name', label: '课程名称' },
  { key: 'status', label: '状态' },
  { key: 'actions', label: '操作' }
]

// ── 详情 ──
const clazzId = computed(() => Number(route.params.id))
const detail = ref(null)
const loading = ref(false)
const loadError = ref('')
const notice = ref('')
const actionError = ref('')
const busy = ref(false) // 任一写操作进行中：禁用全部操作按钮
const saving = ref(false)
const latest = createLatestRequest()
let disposed = false

const students = computed(() => detail.value?.students || [])
const linkedCourses = computed(() => detail.value?.courses || [])

const editForm = reactive({ name: '', description: '' })
const infoError = ref('')

function clearFeedback() {
  notice.value = ''
  actionError.value = ''
}

/** @param {{ syncForm?: boolean }} [opts] syncForm：用服务端数据覆盖基本信息表单（首次加载 / 保存后），其余刷新不动正在编辑的输入。 */
async function load({ syncForm = false } = {}) {
  if (disposed) return
  const id = clazzId.value
  if (!(id > 0)) {
    detail.value = null
    loadError.value = '班级不存在'
    return
  }
  const request = latest.next()
  loading.value = true
  loadError.value = ''
  try {
    const res = await getClazzDetail(id, { signal: request.signal })
    if (!request.isCurrent()) return
    detail.value = {
      ...res,
      students: Array.isArray(res?.students) ? res.students : [],
      courses: Array.isArray(res?.courses) ? res.courses : []
    }
    if (syncForm) {
      editForm.name = detail.value.name || ''
      editForm.description = detail.value.description || ''
    }
  } catch (err) {
    if (!request.isCurrent() || isAbortError(err)) return
    loadError.value = err?.message || '加载班级详情失败'
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

// ── 可关联的课程：全部未归档课程（管理员在教师端课程接口里看得到全部），去掉已关联的 ──
const courseOptions = ref([])
const courseOptionsError = ref('')
const courseToAdd = ref('')

async function loadCourseOptions() {
  try {
    const list = await fetchTeacherCourses()
    if (disposed) return
    courseOptions.value = Array.isArray(list) ? list : []
    courseOptionsError.value = ''
  } catch (err) {
    if (disposed) return
    courseOptionsError.value = err?.message || '加载课程列表失败，暂时无法关联课程'
  }
}

const availableCourses = computed(() => {
  const linked = new Set(linkedCourses.value.map((c) => c.id))
  return courseOptions.value.filter((c) => !linked.has(c.id))
})

function courseStatusText(c) {
  if (c.archived) return '已归档'
  return c.status === 1 ? '已发布' : '草稿'
}

function courseTag(c) {
  if (c.archived) return tagStyle('mut')
  return c.status === 1 ? tagStyle('ok') : tagStyle('warn')
}

/** 写操作的公共外壳：互斥、清反馈、成功后静默刷新详情。 */
async function runAction(task, onDone) {
  if (busy.value) return
  busy.value = true
  clearFeedback()
  try {
    const res = await task()
    if (disposed) return
    onDone(res)
    await load()
  } catch (err) {
    if (disposed) return
    actionError.value = err?.message || '操作失败'
  } finally {
    busy.value = false
  }
}

// ── 基本信息 ──
async function saveInfo() {
  if (busy.value) return
  const name = editForm.name.trim()
  const description = editForm.description.trim()
  let problem = ''
  if (!name) problem = '请填写班级名称'
  else if (name.length > NAME_MAX) problem = `班级名称不能超过 ${NAME_MAX} 个字符`
  else if (description.length > DESCRIPTION_MAX) problem = `说明不能超过 ${DESCRIPTION_MAX} 个字符`
  infoError.value = problem
  if (problem) return
  busy.value = true
  saving.value = true
  clearFeedback()
  try {
    await updateClazz(clazzId.value, { name, description })
    if (disposed) return
    notice.value = '班级信息已保存'
    await load({ syncForm: true })
  } catch (err) {
    if (disposed) return
    infoError.value = err?.message || '保存失败'
  } finally {
    saving.value = false
    busy.value = false
  }
}

// ── 学生 ──
async function removeStudent(s) {
  const who = s.realName ? `${s.realName}（${s.username}）` : s.username
  if (!window.confirm(`确定把「${who}」移出班级吗？该学生已有的选课记录保留，仍可继续学习已选课程。`)) return
  await runAction(
    () => removeClazzStudent(clazzId.value, s.id),
    () => {
      notice.value = `已将「${who}」移出班级（选课记录保留）`
    }
  )
}

const studentsModal = ref(false)
const studentsText = ref('')
const studentsResult = ref(null)
const submitting = ref(false)
const modalError = ref('')

function openAddStudents() {
  clearFeedback()
  studentsText.value = ''
  studentsResult.value = null
  modalError.value = ''
  studentsModal.value = true
}

function closeStudentsModal() {
  if (submitting.value) return
  studentsModal.value = false
  studentsResult.value = null
  modalError.value = ''
}

/** 每行一个用户名（也容忍逗号 / 分号 / 制表符分隔），去空白、去重并保持原顺序。 */
function parseUsernames(text) {
  const seen = new Set()
  const out = []
  for (const part of String(text || '').split(/[\r\n\t,，;；]+/)) {
    const name = part.trim()
    if (!name || seen.has(name)) continue
    seen.add(name)
    out.push(name)
  }
  return out
}

async function submitStudents() {
  if (submitting.value) return
  const usernames = parseUsernames(studentsText.value)
  if (usernames.length === 0) {
    modalError.value = '请至少填写一个学生用户名'
    return
  }
  if (usernames.length > MAX_USERNAMES) {
    modalError.value = `一次最多添加 ${MAX_USERNAMES} 个用户名（当前 ${usernames.length} 个），请分批添加`
    return
  }
  submitting.value = true
  modalError.value = ''
  try {
    const res = await addClazzStudents(clazzId.value, { usernames })
    if (disposed) return
    studentsResult.value = {
      added: Number(res?.added) || 0,
      enrolled: Number(res?.enrolled) || 0,
      movedFrom: Array.isArray(res?.movedFrom) ? res.movedFrom : [],
      notFound: Array.isArray(res?.notFound) ? res.notFound : []
    }
    submitting.value = false
    await load() // 刷新学生表；结果面板已经可操作，不必等它
  } catch (err) {
    if (disposed) return
    modalError.value = err?.message || '添加失败'
  } finally {
    submitting.value = false
  }
}

/** 结果页 → 回到输入页，输入框预填未找到的用户名，方便改完重试。 */
function addMoreStudents() {
  const retry = studentsResult.value ? studentsResult.value.notFound : []
  studentsText.value = retry.join('\n')
  studentsResult.value = null
  modalError.value = ''
}

/** 转班结果只带学生 id：列表刷新后能解析出用户名就显示用户名。 */
function studentName(studentId) {
  const s = students.value.find((x) => x.id === studentId)
  if (!s) return `学生 #${studentId}`
  return s.realName ? `${s.realName}（${s.username}）` : s.username
}

// ── 课程 ──
async function addCourse() {
  const course = courseOptions.value.find((c) => c.id === Number(courseToAdd.value))
  if (!course) return
  await runAction(
    () => addClazzCourse(clazzId.value, course.id),
    (res) => {
      courseToAdd.value = ''
      const enrolled = Number(res?.enrolled) || 0
      notice.value = `已关联「${course.name}」，为班级学生新增选课记录 ${enrolled} 条`
    }
  )
}

async function removeCourse(c) {
  if (!window.confirm(`确定解除班级与课程「${c.name}」的关联吗？班级学生已有的选课记录保留，仍可继续学习该课程。`)) return
  await runAction(
    () => removeClazzCourse(clazzId.value, c.id),
    () => {
      notice.value = `已解除与「${c.name}」的关联（选课记录保留）`
    }
  )
}

watch(clazzId, (id) => {
  // 离开本页时路由参数会先变成 undefined，此时不重载
  if (!(id > 0)) return
  detail.value = null
  clearFeedback()
  void load({ syncForm: true })
})

onBeforeUnmount(() => {
  disposed = true
  latest.cancel()
})

void load({ syncForm: true })
void loadCourseOptions()

// ── 样式（共用部分见 components/admin/adminStyle.js） ──
// 学生 / 课程子表：列宽自适应，360px 手机宽度下也能把「操作」列放进卡片、不必横向滚动
const subTableStyle = { ...tableStyle, minWidth: '330px' }

const backLinkStyle = {
  display: 'inline-block',
  marginBottom: '12px',
  fontSize: '12.5px',
  color: 'var(--mut)',
  textDecoration: 'none'
}

const sectionStyle = { marginBottom: '28px' }

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

const sectionHeadStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: '12px',
  flexWrap: 'wrap',
  marginBottom: '8px'
}

const pickerStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  flexWrap: 'wrap',
  maxWidth: '100%'
}

const infoCardStyle = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  padding: '16px 18px',
  display: 'flex',
  flexDirection: 'column',
  gap: '10px',
  transition: 'background-color 0.35s, border-color 0.35s'
}

const infoFieldsStyle = {
  display: 'flex',
  gap: '14px',
  flexWrap: 'wrap'
}

const fieldColStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  flex: '1 1 200px',
  minWidth: 0
}

const infoFooterStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: '12px',
  flexWrap: 'wrap'
}

const metaStyle = { fontSize: '12.5px', color: 'var(--mut)' }

const textareaStyle = {
  ...fieldStyle,
  height: 'auto',
  padding: '8px 10px',
  lineHeight: 1.6,
  resize: 'vertical',
  fontFamily: "'Consolas', 'Fira Mono', monospace"
}

const resultBlockStyle = {
  border: '1px solid var(--line)',
  borderRadius: '8px',
  padding: '10px 12px',
  background: 'var(--card2)'
}

const resultTitleStyle = { fontSize: '12.5px', fontWeight: 600, marginBottom: '6px' }

const resultListStyle = { margin: 0, paddingLeft: '18px', fontSize: '12.5px', lineHeight: 1.8 }

const resultNamesStyle = { fontSize: '12.5px', lineHeight: 1.8, wordBreak: 'break-all' }
</script>
