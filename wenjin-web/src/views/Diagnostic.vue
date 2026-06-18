<template>
  <!-- Root carries class="wj-diag" so scoped CSS custom properties override the global dark theme
       only inside this component, leaving ColorMap's dark palette untouched. -->
  <div class="wj-diag">

    <!-- 工具栏 -->
    <div class="diag-topbar">
      <span v-show="width >= 560" class="diag-subtitle">入口诊断</span>
      <div style="margin-left:auto;display:flex;align-items:center;gap:10px;">
        <span v-show="width >= 480" class="diag-meta">已答 {{ answeredCount }} · 跳过 {{ skippedCount }}</span>
        <router-link to="/map" class="diag-exit-btn">保存并退出</router-link>
      </div>
    </div>

    <!-- 进度条（顶部细线） -->
    <div class="diag-progress-track">
      <div class="diag-progress-fill" :style="{ width: progressPct }"></div>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="diag-center-state">
      <span class="diag-loading-text">加载试题中…</span>
    </div>

    <!-- 加载失败 -->
    <div v-else-if="error" class="diag-center-state">
      <div class="diag-error-msg">加载失败：{{ error }}</div>
      <button class="diag-btn-acc" @click="load">重试</button>
    </div>

    <!-- 题库为空 -->
    <div v-else-if="questions.length === 0" class="diag-center-state">
      <div class="diag-empty-msg">题库为空，请先在<router-link to="/admin" class="diag-link">管理页</router-link>导入题库。</div>
    </div>

    <!-- 答题区 -->
    <div
      v-else-if="!done"
      class="diag-main"
      :style="{ padding: mainPad }"
    >
      <!-- 题号 + 章节标签 + 进度条 -->
      <div class="diag-q-meta">
        <span class="diag-q-num">第 {{ idx + 1 }} 题<span class="diag-q-of"> · 共 {{ total }} 题</span></span>
        <span class="diag-chapter-tag">{{ q.c }}</span>
      </div>

      <div class="diag-inner-progress-track">
        <div class="diag-inner-progress-fill" :style="{ width: progressPct }"></div>
      </div>

      <!-- 题干 -->
      <div class="diag-stem" :style="{ fontSize: qSize }">{{ q.q }}</div>

      <!-- 选项 -->
      <div class="diag-options">
        <div
          v-for="(opt, i) in q.o"
          :key="i"
          class="diag-opt"
          :class="{ selected: sel === i }"
          @click="pick(i)"
        >
          <span class="diag-opt-letter" :class="{ selected: sel === i }">{{ letters[i] }}</span>
          <span class="diag-opt-text">{{ opt }}</span>
        </div>
      </div>

      <!-- 导航按钮 -->
      <div class="diag-nav">
        <button
          class="diag-btn-ghost"
          @click="goPrev"
          :style="{ visibility: idx === 0 ? 'hidden' : 'visible' }"
        >上一题</button>
        <button class="diag-btn-skip" @click="skip">不确定，跳过</button>
        <button
          class="diag-btn-next"
          :class="{ active: hasSel }"
          @click="goNext"
        >{{ last ? '完成诊断' : '下一题' }}</button>
      </div>

      <div class="diag-hint">这不是考试——答错不扣分，跳过也是有用的信号。</div>
    </div>

    <!-- 提交中 -->
    <div v-else-if="submitting" class="diag-center-state">
      <span class="diag-loading-text">正在提交…</span>
    </div>

    <!-- 提交失败 -->
    <div v-else-if="submitError" class="diag-center-state">
      <div class="diag-error-msg">提交失败：{{ submitError }}</div>
      <button class="diag-btn-acc" @click="submitAnswers">重新提交</button>
    </div>

    <!-- 完成态：内联结果 -->
    <div v-else class="diag-done">
      <div class="diag-done-icon">✓</div>
      <div class="diag-done-title" :style="{ fontSize: doneTitleSize }">诊断完成</div>
      <div v-if="result" class="diag-done-score">
        答对 <strong>{{ result.correctCount }}</strong> / 共 <strong>{{ result.total }}</strong> 题
      </div>
      <div class="diag-done-sub">
        你答了 {{ answeredCount }} 题，跳过 {{ skippedCount }} 题。
        问津会沿知识图谱回溯每一处失分，找到真正的根因。
      </div>

      <!-- 逐题对错列表（可选，使用 result.grades） -->
      <div v-if="result && result.grades && result.grades.length" class="diag-grades">
        <div
          v-for="(g, i) in result.grades"
          :key="g.questionId"
          class="diag-grade-row"
          :class="{ correct: g.correct, wrong: !g.correct }"
        >
          <span class="diag-grade-num">第 {{ i + 1 }} 题</span>
          <span class="diag-grade-mark">{{ g.correct ? '✓ 正确' : '✗ 错误' }}</span>
          <span v-if="!g.correct" class="diag-grade-key">正确答案：{{ g.correctKey }}</span>
        </div>
      </div>

      <div class="diag-done-actions">
        <router-link to="/result" class="diag-btn-acc diag-btn-link">查看诊断结果</router-link>
        <router-link to="/map" class="diag-btn-restart-link">查看染色地图</router-link>
        <button class="diag-btn-restart" @click="restart">重新作答</button>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { fetchPaper, submitPaper } from '../api/diagnostic.js'
import { useRoute } from 'vue-router'

// ─── 常量 ──────────────────────────────────────────────────────────────────
const route = useRoute()
const letters = ['A', 'B', 'C', 'D']

// 从 localStorage 读取当前登录用户
function readUser() {
  try { return JSON.parse(localStorage.getItem('wj_user')) } catch { return null }
}
const currentUser = readUser()
const DEMO_STUDENT_ID = currentUser?.id || 2
const DEMO_COURSE_ID = (() => {
  const q = Number(route.query.courseId)
  return q > 0 ? q : 1
})()

// ─── 响应式视口宽度（替代 useViewport） ──────────────────────────────────────
const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)

function onResize() {
  width.value = window.innerWidth
}

// ─── 状态 ──────────────────────────────────────────────────────────────────
/** 每道题的形状：{ questionId, c(chapter), q(stem), o(option texts), optionKeys } */
const questions = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

const idx = ref(0)        // 当前题目索引（0-based）
const answers = ref({})   // { [idx]: optionIndex }  —— 选的是索引
const skipped = ref({})   // { [idx]: true }

const done = ref(false)
const submitting = ref(false)
const submitError = ref('')
const result = ref(null)  // SubmitResult: { total, correctCount, grades }

// ─── 计算属性 ───────────────────────────────────────────────────────────────
const q = computed(() => questions.value[idx.value] || {})
const sel = computed(() => {
  const v = answers.value[idx.value]
  return typeof v === 'number' ? v : undefined
})
const hasSel = computed(() => typeof sel.value === 'number')
const last = computed(() => idx.value === total.value - 1)

const answeredCount = computed(() => Object.keys(answers.value).length)
const skippedCount = computed(() =>
  Object.keys(skipped.value).filter((k) => !(k in answers.value)).length
)
const progressPct = computed(() => {
  const t = total.value
  if (!t) return '0%'
  const progress = done.value ? t : idx.value + 1
  return Math.round((progress / t) * 100) + '%'
})

// 响应式排版
const narrow = computed(() => width.value < 640)
const mainPad = computed(() => (narrow.value ? '28px 18px 12px' : '48px 24px 16px'))
const qSize = computed(() => (narrow.value ? '17px' : '20px'))
const doneTitleSize = computed(() => (narrow.value ? '24px' : '30px'))

// ─── 数据加载 ────────────────────────────────────────────────────────────────
async function load() {
  loading.value = true
  error.value = ''
  try {
    const paper = await fetchPaper(DEMO_COURSE_ID)
    // 将后端 PaperQuestionVO 映射为组件内部格式，保留 questionId 和 optionKeys 用于提交
    questions.value = (paper.questions || []).map((pq) => ({
      questionId: pq.questionId,
      c: pq.chapter,
      q: pq.stem,
      o: (pq.options || []).map((opt) => opt.text),
      optionKeys: (pq.options || []).map((opt) => opt.key)
    }))
    total.value = paper.total ?? questions.value.length
  } catch (e) {
    error.value = e.message || '未知错误'
  } finally {
    loading.value = false
  }
}

// ─── 答题交互 ────────────────────────────────────────────────────────────────
function pick(i) {
  answers.value = { ...answers.value, [idx.value]: i }
}

function goNext() {
  if (!hasSel.value) return
  if (last.value) {
    finish()
  } else {
    idx.value++
  }
}

function goPrev() {
  if (idx.value > 0) idx.value--
}

function skip() {
  skipped.value = { ...skipped.value, [idx.value]: true }
  if (last.value) {
    finish()
  } else {
    idx.value++
  }
}

function finish() {
  done.value = true
  submitAnswers()
}

// ─── 提交 ────────────────────────────────────────────────────────────────────
async function submitAnswers() {
  if (submitting.value) return   // 防重入：末题双击"跳过/完成"不会触发两次 POST
  submitting.value = true
  submitError.value = ''
  result.value = null

  // 构建 answers 数组：仅含实际选了答案的题（跳过的不传）
  const payload = {
    studentId: DEMO_STUDENT_ID,
    courseId: DEMO_COURSE_ID,
    answers: Object.entries(answers.value).map(([idxStr, optIdx]) => {
      const q = questions.value[Number(idxStr)]
      return {
        questionId: q.questionId,
        optionKey: q.optionKeys[optIdx]
      }
    })
  }

  try {
    result.value = await submitPaper(payload)
  } catch (e) {
    submitError.value = e.message || '未知错误'
    done.value = false   // 回退到答题区，让用户可以重提
  } finally {
    submitting.value = false
  }
}

function restart() {
  idx.value = 0
  answers.value = {}
  skipped.value = {}
  done.value = false
  submitting.value = false
  submitError.value = ''
  error.value = ''
  result.value = null
}

// ─── 生命周期 ────────────────────────────────────────────────────────────────
onMounted(async () => {
  window.addEventListener('resize', onResize)
  await load()

  /**
   * DEV 测试钩子（Playwright / T10 用）：
   * window.__wjDiag 暴露的 shape：
   * {
   *   // 状态 refs（读取 .value）
   *   loading, error, idx, total, done, result, questions, answers, skipped,
   *   submitting, submitError,
   *   // 计算属性 refs
   *   answeredCount, skippedCount, hasSel, last,
   *   // 操作函数
   *   pick(optionIndex),   // 选择当前题的第 i 个选项
   *   goNext(),            // 下一题 / 触发 finish（同 UI 按钮，需 hasSel）
   *   goPrev(),            // 上一题
   *   skip(),              // 跳过当前题
   *   finish(),            // 直接进入完成态并提交（bypass hasSel 检查）
   *   submitAnswers(),     // 重新提交（供 submitError 状态下重试）
   *   restart(),           // 重置并重新开始
   *   load(),              // 重新加载试卷
   * }
   */
  if (import.meta.env.DEV) {
    window.__wjDiag = {
      loading, error, idx, total, done, result,
      questions, answers, skipped, submitting, submitError,
      answeredCount, skippedCount, hasSel, last,
      pick, goNext, goPrev, skip, finish, submitAnswers, restart, load
    }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
/*
 * Token localization: 诊断页使用暖白低焦虑配色，通过 .wj-diag 覆盖全局暗色变量。
 * ColorMap 的深色主题不受影响，因为 CSS 自定义属性只在此元素子树内继承。
 */
.wj-diag {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--ink);
}

/* ── 顶栏 ─────────────────────────────────────────────────────────── */
.diag-topbar {
  height: 48px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
}

.diag-subtitle {
  font-size: 13.5px;
  font-weight: 500;
  white-space: nowrap;
  flex: none;
  color: var(--ink);
}

.diag-meta {
  font-size: 12px;
  color: var(--mut);
  white-space: nowrap;
}

.diag-exit-btn {
  font-size: 12.5px;
  color: var(--mut);
  text-decoration: none;
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  white-space: nowrap;
  background: transparent;
  transition: border-color 0.15s, color 0.15s;
}
.diag-exit-btn:hover {
  border-color: var(--mut);
  color: var(--ink);
}

/* ── 顶部进度条 ───────────────────────────────────────────────────── */
.diag-progress-track {
  height: 2px;
  flex: none;
  background: var(--card2);
}

.diag-progress-fill {
  height: 100%;
  background: var(--acc);
  transition: width 0.35s ease;
}

/* ── 居中状态（加载/错误/空） ──────────────────────────────────────── */
.diag-center-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 40px 24px;
  text-align: center;
}

.diag-loading-text {
  font-size: 15px;
  color: var(--mut);
}

.diag-error-msg {
  font-size: 15px;
  color: var(--acc);
}

.diag-empty-msg {
  font-size: 15px;
  color: var(--mut);
  line-height: 1.7;
}

.diag-link {
  color: var(--acc);
}

/* ── 答题主区 ────────────────────────────────────────────────────── */
.diag-main {
  flex: 1;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

/* 题号行 */
.diag-q-meta {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}

.diag-q-num {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 15px;
  color: var(--mut);
}

.diag-q-of {
  opacity: 0.55;
}

.diag-chapter-tag {
  font-size: 12px;
  color: var(--mut);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 10px;
}

/* 题内进度条 */
.diag-inner-progress-track {
  height: 4px;
  background: var(--card2);
  border-radius: 99px;
  overflow: hidden;
  margin-bottom: 26px;
}

.diag-inner-progress-fill {
  height: 100%;
  background: var(--acc);
  border-radius: 99px;
  transition: width 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 题干 */
.diag-stem {
  font-weight: 500;
  line-height: 1.65;
  margin-bottom: 28px;
}

/* 选项列表 */
.diag-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 32px;
}

.diag-opt {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 52px;
  box-sizing: border-box;
  padding: 13px 16px;
  background: var(--card);
  border: 1.5px solid var(--line);
  border-radius: 11px;
  cursor: pointer;
  transition: background-color 0.18s, border-color 0.18s;
}

.diag-opt:hover {
  border-color: var(--mut);
}

.diag-opt.selected {
  background: var(--accSoft);
  border-color: var(--acc);
}

.diag-opt-letter {
  width: 26px;
  height: 26px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid var(--line);
  border-radius: 50%;
  font-size: 12.5px;
  color: var(--mut);
  background: transparent;
  transition: background-color 0.18s, border-color 0.18s, color 0.18s;
}

.diag-opt-letter.selected {
  background: var(--acc);
  border-color: var(--acc);
  color: #FFFDF8;
}

.diag-opt-text {
  font-size: 14.5px;
  line-height: 1.55;
  color: var(--ink);
}

/* 导航按钮行 */
.diag-nav {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 8px;
}

.diag-btn-ghost {
  height: 42px;
  padding: 0 18px;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 9px;
  color: var(--mut);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  font-family: inherit;
  transition: border-color 0.15s, color 0.15s;
}
.diag-btn-ghost:hover {
  border-color: var(--mut);
  color: var(--ink);
}

.diag-btn-skip {
  margin-left: auto;
  height: 42px;
  padding: 0 16px;
  background: transparent;
  border: none;
  color: var(--mut);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  border-radius: 9px;
  font-family: inherit;
  transition: color 0.15s;
}
.diag-btn-skip:hover {
  color: var(--ink);
}

.diag-btn-next {
  height: 42px;
  padding: 0 30px;
  background: var(--card2);
  border: none;
  border-radius: 9px;
  color: var(--mut);
  font-size: 14px;
  font-weight: 500;
  cursor: not-allowed;
  white-space: nowrap;
  font-family: inherit;
  transition: background-color 0.2s, color 0.2s;
}

.diag-btn-next.active {
  background: var(--acc);
  color: #FFFDF8;
  cursor: pointer;
}

/* 提示文字 */
.diag-hint {
  text-align: center;
  font-size: 12px;
  color: var(--mut);
  opacity: 0.75;
  padding-bottom: 18px;
}

/* ── 完成态 ──────────────────────────────────────────────────────── */
.diag-done {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 24px 60px;
  text-align: center;
  animation: wjFadeUp 0.5s ease both;
}

@keyframes wjFadeUp {
  from { opacity: 0; transform: translateY(16px); }
  to   { opacity: 1; transform: translateY(0); }
}

.diag-done-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--acc);
  color: #FFFDF8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  margin-bottom: 28px;
}

.diag-done-title {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-weight: 600;
  line-height: 1.5;
  margin-bottom: 10px;
  color: var(--ink);
}

.diag-done-score {
  font-size: 22px;
  font-weight: 600;
  color: var(--acc);
  margin-bottom: 12px;
}

.diag-done-sub {
  font-size: 14px;
  color: var(--mut);
  line-height: 1.8;
  max-width: 420px;
  margin-bottom: 28px;
}

/* 逐题对错列表 */
.diag-grades {
  width: 100%;
  max-width: 520px;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  margin-bottom: 32px;
  text-align: left;
}

.diag-grade-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 14px;
  font-size: 13px;
  border-bottom: 1px solid var(--line);
}

.diag-grade-row:last-child {
  border-bottom: none;
}

.diag-grade-num {
  color: var(--mut);
  min-width: 60px;
  flex: none;
}

.diag-grade-mark {
  font-weight: 500;
}

.diag-grade-row.correct .diag-grade-mark {
  color: #4a9e6d;
}

.diag-grade-row.wrong .diag-grade-mark {
  color: var(--acc);
}

.diag-grade-key {
  font-size: 12px;
  color: var(--mut);
}

/* 完成态操作按钮 */
.diag-done-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.diag-btn-acc {
  height: 46px;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  padding: 0 34px;
  background: var(--acc);
  border-radius: 10px;
  color: #FFFDF8;
  font-size: 14.5px;
  font-weight: 500;
  border: none;
  cursor: pointer;
  font-family: inherit;
  text-decoration: none;
  transition: opacity 0.15s;
}

.diag-btn-acc:hover {
  opacity: 0.88;
}

.diag-btn-link {
  /* router-link 用 */
  display: flex;
}

.diag-btn-restart {
  background: transparent;
  border: none;
  color: var(--mut);
  font-size: 12.5px;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
  font-family: inherit;
  transition: color 0.15s;
}

.diag-btn-restart:hover {
  color: var(--ink);
}

.diag-btn-restart-link {
  background: transparent; border: none; color: var(--mut);
  font-size: 12.5px; text-decoration: underline; text-underline-offset: 3px;
}
.diag-btn-restart-link:hover { color: var(--ink); }
</style>
