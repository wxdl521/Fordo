<template>
  <div data-testid="admin-maintenance-page" :style="pageStyle">
    <AdminPageHeader
      title="数据维护"
      subtitle="清空某门课程下全部学生的学习数据，用于演示复位或重新测试。此操作不可撤销。"
    />

    <div v-if="error" data-testid="admin-maint-error" role="alert" :style="errorStyle">{{ error }}</div>

    <section :style="cardStyle">
      <!-- 选课程 -->
      <div :style="rowStyle">
        <label for="admin-maint-course-select" :style="labelStyle">选择课程</label>
        <select
          id="admin-maint-course-select"
          v-model="courseId"
          data-testid="admin-maint-course"
          class="wj-admin-field"
          :style="inputStyle(360)"
          :disabled="coursesLoading || submitting"
        >
          <option value="">{{ coursesLoading ? '加载课程中…' : '请选择课程' }}</option>
          <option v-for="c in courses" :key="c.id" :value="String(c.id)">{{ c.name }}</option>
        </select>
        <div v-if="courseTruncated" :style="hintLineStyle">课程较多，下拉只列出前 {{ courses.length }} 门未归档课程。</div>
      </div>

      <!-- 影响范围 -->
      <div :style="infoBoxStyle">
        <div :style="infoTitleStyle">将被清空</div>
        <ul :style="listStyle">
          <li>学生的答题记录</li>
          <li>学生掌握度与掌握度快照</li>
          <li>入口诊断、快速诊断与练习会话</li>
          <li>学习路径（含路径条目；学生当前路径指针置空）</li>
          <li>AI 学习伴侣的对话与学生记忆</li>
          <li>教师 AI 助教的会话（其运行轨迹保留计数，但步骤明细被清空）</li>
        </ul>
        <div :style="infoTitleStyle">保留</div>
        <div :style="keepTextStyle">选课关系、班级、知识图谱、题库、账号。清空后学生在该课程上回到「未诊断」状态，可重新诊断。</div>
      </div>

      <!-- 确认 -->
      <div :style="rowStyle">
        <label for="admin-maint-confirm" :style="labelStyle">{{ selectedCourse ? `输入课程名「${selectedCourse.name}」以确认` : '请先选择课程，再输入课程名以确认' }}</label>
        <input
          id="admin-maint-confirm"
          v-model="confirmText"
          type="text"
          autocomplete="off"
          data-testid="admin-maint-confirm"
          class="wj-admin-field"
          :style="inputStyle(360)"
          placeholder="请输入课程名"
          :disabled="!selectedCourse || submitting"
          @keydown.enter="onEnter($event, submit)"
        />
        <div v-if="selectedCourse && confirmText && !confirmMatches" :style="hintLineStyle">需与课程名完全一致（含标点与空格）。</div>
      </div>

      <div :style="footerStyle">
        <button
          type="button"
          data-testid="admin-maint-submit"
          class="wj-admin-btn"
          :style="dangerBtnStyle"
          :disabled="!canSubmit"
          @click="submit"
        >{{ submitting ? '清空中…' : '清空学习数据' }}</button>
      </div>
    </section>

    <!-- 结果：每张表删除的行数 -->
    <section v-if="result" data-testid="admin-maint-result" :style="cardStyle">
      <div role="status" :style="resultHeadStyle">
        已清空「{{ result.courseName }}」的学习数据，共删除 {{ result.total }} 行。其他课程与账号不受影响。
      </div>
      <div :style="tableCardStyle">
        <div :style="tableScrollStyle">
          <table :style="resultTableStyle">
            <thead>
              <tr>
                <th scope="col" :style="thStyle({})">数据表</th>
                <th scope="col" :style="thStyle({ width: '120px' })">删除行数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in result.rows" :key="row.table" :data-testid="'admin-maint-result-row-' + row.table">
                <td :style="tdStyle">
                  <div>{{ row.label }}</div>
                  <div :style="tableNameStyle">{{ row.table }}</div>
                </td>
                <td :style="{ ...tdStyle, fontVariantNumeric: 'tabular-nums' }">{{ row.count }}</td>
              </tr>
              <tr v-if="result.rows.length === 0">
                <td :style="tdStyle" colspan="2">该课程下没有需要清空的学习数据。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import {
  errorStyle,
  fieldStyle,
  hintLineStyle,
  labelStyle,
  pageStyle,
  tableCardStyle,
  tableScrollStyle,
  tdStyle,
  thStyle
} from '../components/admin/adminStyle.js'
import { onEnter } from '../components/admin/adminUtils.js'
import { listAdminCourses, resetLearningData } from '../api/systemAdmin.js'

// 数据重置涉及的表 → 中文名（未知表名原样显示）
const TABLE_LABELS = {
  answer_record: '学生答题记录',
  student_mastery: '学生掌握度',
  mastery_snapshot: '掌握度快照',
  diagnostic_session: '入口诊断会话',
  adaptive_diag_session: '快速诊断会话',
  practice_session: '练习会话',
  learning_path: '学习路径',
  learning_path_item: '学习路径条目',
  companion_conversation: 'AI 伴侣对话',
  companion_message: 'AI 伴侣消息',
  student_memory: '学生记忆',
  agent_plan: '学习计划',
  agent_plan_task: '学习计划任务',
  teacher_conversation: 'AI 助教会话',
  teacher_message: 'AI 助教消息'
}

const courses = ref([])
const courseTruncated = ref(false)
const coursesLoading = ref(false)
const courseId = ref('')
const confirmText = ref('')
const submitting = ref(false)
const error = ref('')
const result = ref(null)
let disposed = false

const selectedCourse = computed(() => courses.value.find((c) => String(c.id) === courseId.value) || null)
const confirmMatches = computed(() => !!selectedCourse.value && confirmText.value === selectedCourse.value.name)
const canSubmit = computed(() => confirmMatches.value && !submitting.value)

// 换课程：清掉上一门的确认文本、结果与错误，避免误触
watch(courseId, () => {
  confirmText.value = ''
  result.value = null
  error.value = ''
})

async function loadCourses() {
  coursesLoading.value = true
  try {
    const page = await listAdminCourses({ page: 1, size: 100, archived: false })
    if (disposed) return
    const records = Array.isArray(page?.records) ? page.records : []
    courses.value = records
    courseTruncated.value = Number(page?.total) > records.length
  } catch (err) {
    if (disposed) return
    error.value = err?.message || '加载课程列表失败'
  } finally {
    coursesLoading.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
  const course = selectedCourse.value
  if (!window.confirm(`最后确认：清空「${course.name}」下全部学生的学习数据？此操作不可撤销。`)) return
  submitting.value = true
  error.value = ''
  result.value = null
  try {
    const res = await resetLearningData(course.id, confirmText.value)
    if (disposed) return
    const deleted = res?.deleted && typeof res.deleted === 'object' ? res.deleted : {}
    const rows = Object.entries(deleted).map(([table, count]) => ({
      table,
      label: TABLE_LABELS[table] || table,
      count: Number(count) || 0
    }))
    result.value = {
      courseName: course.name,
      rows,
      total: rows.reduce((sum, r) => sum + r.count, 0)
    }
    confirmText.value = ''
  } catch (err) {
    if (disposed) return
    // 生产环境禁用等情况：直接展示后端 message
    error.value = err?.message || '清空失败'
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
})

void loadCourses()

// ── 样式（共用部分见 components/admin/adminStyle.js） ──
function inputStyle(width) {
  return { ...fieldStyle, width: `${width}px`, maxWidth: '100%' }
}

const cardStyle = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  padding: '16px 18px',
  marginBottom: '16px',
  transition: 'background-color 0.35s, border-color 0.35s'
}

const rowStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  marginBottom: '14px'
}

const infoBoxStyle = {
  background: 'var(--card2)',
  border: '1px solid var(--line)',
  borderRadius: '10px',
  padding: '12px 14px',
  fontSize: '13px',
  lineHeight: 1.8,
  color: 'var(--ink)',
  marginBottom: '14px'
}

const infoTitleStyle = {
  fontSize: '12.5px',
  fontWeight: 600,
  color: 'var(--ink)'
}

const listStyle = {
  margin: '2px 0 8px',
  paddingLeft: '18px',
  color: 'var(--ink)'
}

const keepTextStyle = {
  color: 'var(--mut)',
  fontSize: '12.5px'
}

const footerStyle = {
  display: 'flex',
  justifyContent: 'flex-end'
}

// 危险操作：用主题的红褐色 --acc（亮 / 暗主题都有对应色值）
const dangerBtnStyle = {
  height: '36px',
  padding: '0 20px',
  background: 'var(--acc)',
  border: 'none',
  borderRadius: '8px',
  color: '#FFFDF8',
  fontSize: '13.5px',
  fontWeight: 600,
  cursor: 'pointer',
  whiteSpace: 'nowrap'
}

const resultHeadStyle = {
  fontSize: '13px',
  color: 'var(--ok)',
  background: 'var(--okSoft)',
  border: '1px solid var(--ok)',
  borderRadius: '8px',
  padding: '8px 12px',
  marginBottom: '14px',
  lineHeight: 1.6
}

const resultTableStyle = {
  width: '100%',
  minWidth: '320px',
  borderCollapse: 'collapse',
  fontSize: '13px'
}

const tableNameStyle = {
  fontFamily: "'Consolas', 'Fira Mono', monospace",
  fontSize: '11.5px',
  color: 'var(--mut)',
  marginTop: '2px'
}
</script>
