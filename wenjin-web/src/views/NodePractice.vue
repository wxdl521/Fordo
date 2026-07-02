<template>
  <div class="wj-practice">

    <!-- 顶栏 -->
    <div class="np-topbar">
      <span class="np-title">节点练习</span>
      <span v-if="nodeInfo" class="np-node-name">{{ nodeInfo.name }}</span>
      <div style="margin-left:auto;">
        <router-link :to="backLink" class="np-exit-btn">返回学习路径</router-link>
      </div>
    </div>

    <!-- 顶部进度条（答题时显示） -->
    <div class="np-progress-track">
      <div
        class="np-progress-fill"
        :style="{ width: phase === 'answering' ? topProgressPct : (phase === 'result' ? '100%' : '0%') }"
      ></div>
    </div>

    <!-- ─── 加载中 ─── -->
    <div v-if="phase === 'loading'" class="np-center">
      <span class="np-loading-text">正在组卷…</span>
    </div>

    <!-- ─── 加载失败 ─── -->
    <div v-else-if="phase === 'error'" class="np-center">
      <div class="np-error-msg">{{ errorMsg }}</div>
      <button class="np-btn-acc" @click="startSession">重试</button>
      <router-link :to="backLink" class="np-btn-ghost-link">返回学习路径</router-link>
    </div>

    <!-- ─── 无可用题目 ─── -->
    <div v-else-if="phase === 'empty'" class="np-center">
      <div class="np-empty-msg">该知识点暂无可用练习题，请等待教师审批题目后再来。</div>
      <router-link :to="backLink" class="np-btn-acc np-btn-link">返回学习路径</router-link>
    </div>

    <!-- ─── 答题区 ─── -->
    <div
      v-else-if="phase === 'answering'"
      class="np-main"
      :style="{ padding: mainPad }"
    >
      <QuestionAnswerCard
        :question="currentQ"
        :index="idx + 1"
        :total="total"
        :selected="currentSel"
        :selected-set="currentMultiSel"
        :text-value="currentText"
        :is-last="isLast"
        last-label="提交练习"
        :has-selection="hasSelection"
        @pick="onPick"
        @text-input="onTextInput"
        @prev="goPrev"
        @next="goNext"
        @skip="onSkip"
      />
    </div>

    <!-- ─── 提交中 ─── -->
    <div v-else-if="phase === 'submitting'" class="np-center">
      <span class="np-loading-text">正在评分…</span>
    </div>

    <!-- ─── 提交失败 ─── -->
    <div v-else-if="phase === 'submitError'" class="np-center">
      <div class="np-error-msg">提交失败：{{ errorMsg }}</div>
      <button class="np-btn-acc" @click="doSubmit">重新提交</button>
      <router-link :to="backLink" class="np-btn-ghost-link">返回学习路径</router-link>
    </div>

    <!-- ─── 练习结果 ─── -->
    <div v-else-if="phase === 'result'" class="np-result">
      <div class="np-result-inner">

        <!-- 分数总览 -->
        <div class="np-result-icon">{{ itemCompleted ? '✓' : '★' }}</div>
        <div class="np-result-title">练习完成</div>

        <!-- 掌握度变化动画（T10） -->
        <div v-if="submitResult" class="np-mastery-block">
          <div class="np-mastery-nums">
            <!-- Δ≠0：显示 before → animated-after + 增量徽章 -->
            <template v-if="masteryDelta !== 0">
              <span class="np-mastery-before-num">{{ fmtPct(submitResult.masteryBefore) }}</span>
              <span class="np-mastery-arr"> → </span>
              <span class="np-mastery-after-num" :style="{ color: masteryColor(submitResult.masteryLevel) }">
                {{ fmtPct(displayedMastery) }}
              </span>
              <span class="np-mastery-delta" :class="masteryDelta > 0 ? 'up' : 'down'">
                {{ masteryDelta > 0 ? '+' : '' }}{{ Math.round(masteryDelta) }}
              </span>
            </template>
            <!-- Δ=0（幂等重放）：只展示当前值，不显示「进步了 0」 -->
            <template v-else>
              <span class="np-mastery-after-num" :style="{ color: masteryColor(submitResult.masteryLevel) }">
                {{ fmtPct(submitResult.masteryAfter) }}
              </span>
            </template>
            <span
              class="np-mastery-level-badge"
              :style="{ color: masteryColor(submitResult.masteryLevel), borderColor: masteryColor(submitResult.masteryLevel) }"
            >{{ submitResult.masteryLevel || '未知' }}</span>
          </div>
          <!-- 进度条 -->
          <div class="np-mastery-track">
            <div
              class="np-mastery-fill"
              :style="{ width: barWidth, background: masteryColor(submitResult.masteryLevel) }"
            ></div>
          </div>
          <div class="np-mastery-bar-label">掌握度</div>
        </div>

        <!-- 路径步骤自动完成提示 -->
        <div v-if="itemCompleted" class="np-auto-done">
          该知识点掌握度已达标，学习路径步骤已自动标记完成。
        </div>

        <!-- 薄弱前置提示卡（T10） -->
        <div
          v-if="submitResult && submitResult.weakPrerequisites && submitResult.weakPrerequisites.length"
          class="np-weak-card"
        >
          <div class="np-weak-header">⚠ 发现薄弱前置</div>
          <div
            v-for="wp in submitResult.weakPrerequisites"
            :key="wp.nodeCode"
            class="np-weak-item"
          >
            错误多指向「{{ wp.name }}」<span v-if="wp.hitCount > 1" class="np-weak-count">×{{ wp.hitCount }}</span>
          </div>
          <div v-if="submitResult.pathRegenerated" class="np-weak-hint">已为你调整路径</div>
        </div>

        <!-- pathRegenerated 引导跳转（T10） -->
        <div v-if="submitResult && submitResult.pathRegenerated" class="np-regen-bar">
          <span class="np-regen-txt">学习路径已重新规划</span>
          <router-link :to="backLink" class="np-regen-link">查看新路径 →</router-link>
        </div>

        <!-- 逐题结果 -->
        <div v-if="submitResult && submitResult.graded && submitResult.graded.length" class="np-grades">
          <div class="np-grades-title">逐题详情</div>
          <div
            v-for="(g, i) in submitResult.graded"
            :key="g.questionId"
            class="np-grade-row"
            :class="g.correct === null ? 'short' : (g.correct ? 'correct' : 'wrong')"
          >
            <div class="np-grade-head">
              <span class="np-grade-num">第 {{ findQuestionIndex(g.questionId) + 1 }} 题</span>
              <span class="np-grade-mark">
                {{ g.correct === null ? '简答（参考）' : (g.correct ? '✓ 正确' : '✗ 错误') }}
              </span>
              <span v-if="g.correct === false" class="np-grade-ans">
                正确答案：{{ g.correctAnswer }}
              </span>
            </div>
            <div v-if="g.analysis" class="np-grade-analysis">{{ g.analysis }}</div>
          </div>
        </div>

        <!-- 操作按钮 -->
        <div class="np-result-actions">
          <router-link :to="backLink" class="np-btn-acc np-btn-link">返回学习路径</router-link>
          <button class="np-btn-restart" @click="restartPractice">再练一次</button>
        </div>

      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startPractice, submitPractice } from '../api/practice.js'
import { useStudentCourse } from '../composables/useStudentCourse.js'
import QuestionAnswerCard from '../components/QuestionAnswerCard.vue'

// ─── 路由 & 用户 ─────────────────────────────────────────────────────────────
const route = useRoute()
const router = useRouter()
const { courseId } = useStudentCourse()

function readUser() {
  try { return JSON.parse(localStorage.getItem('wj_user')) } catch { return null }
}
const STUDENT_ID = readUser()?.id || 2

// ─── 路由参数 ─────────────────────────────────────────────────────────────────
const nodeId = computed(() => {
  const v = Number(route.query.nodeId)
  return v > 0 ? v : null
})
const pathItemId = computed(() => {
  const v = Number(route.query.pathItemId)
  return v > 0 ? v : null
})

const backLink = computed(() => {
  const q = {}
  if (courseId.value) q.courseId = courseId.value
  return { path: '/path', query: q }
})

// ─── 会话状态 ─────────────────────────────────────────────────────────────────
/** 'loading' | 'error' | 'empty' | 'answering' | 'submitting' | 'submitError' | 'result' */
const phase = ref('loading')
const errorMsg = ref('')

const sessionId = ref(null)
const nodeInfo = ref(null)   // { nodeId, nodeCode, name }

/**
 * 内部题目格式（与 Diagnostic.vue 对齐）：
 * { questionId, c(chapter), q(stem), o(option texts[]), optionKeys[], type }
 */
const questions = ref([])

// ─── 答题状态 ─────────────────────────────────────────────────────────────────
const idx = ref(0)
/** SINGLE / TRUE_FALSE：{ [qIdx]: number } —— 已选选项下标 */
const singleAnswers = ref({})
/** MULTI：{ [qIdx]: number[] } —— 多选选项下标数组 */
const multiSelections = ref({})
/** SHORT_ANSWER：{ [qIdx]: string } */
const textAnswers = ref({})
/** { [qIdx]: true } —— 手动跳过的题（不影响已答题） */
const skippedSet = ref({})

// ─── 提交结果 ─────────────────────────────────────────────────────────────────
/** PracticeSubmitVO */
const submitResult = ref(null)
const itemCompleted = ref(false)

// ─── T10: 掌握度动画状态 ──────────────────────────────────────────────────────
/** 正在展示的掌握度数值（计数动画中间值） */
const displayedMastery = ref(0)
/** 进度条宽度（CSS transition 驱动） */
const barWidth = ref('0%')
/** masteryAfter - masteryBefore；0 表示幂等重放 */
const masteryDelta = computed(() => {
  if (!submitResult.value) return 0
  return (submitResult.value.masteryAfter ?? 0) - (submitResult.value.masteryBefore ?? 0)
})

/**
 * 当 phase 切换到 'result' 时触发：
 *   - 进度条：0% → masteryAfter%（CSS transition 0.7s）
 *   - 数字计数器：masteryBefore → masteryAfter（rAF 700ms，Δ=0 时跳过）
 */
watch(phase, async (p) => {
  if (p !== 'result' || !submitResult.value) return
  const before = submitResult.value.masteryBefore ?? 0
  const after  = submitResult.value.masteryAfter  ?? 0

  // 初始化：数字从 before 开始，进度条先清零
  displayedMastery.value = before
  barWidth.value = '0%'

  // 等 DOM 渲染完初始状态后再启动动画
  await nextTick()
  setTimeout(() => {
    // CSS transition 驱动进度条填充
    barWidth.value = Math.max(0, Math.min(100, after)) + '%'

    if (before === after) {
      // Δ=0：数字直接显示当前值，不计数
      displayedMastery.value = after
      return
    }

    // rAF 数字计数动画
    const duration = 700
    const startTs = Date.now()
    function tick() {
      const elapsed = Date.now() - startTs
      const t = Math.min(elapsed / duration, 1)
      const eased = 1 - Math.pow(1 - t, 3) // ease-out cubic
      displayedMastery.value = Math.round(before + (after - before) * eased)
      if (t < 1) requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  }, 50)
})

// ─── 响应式布局 ───────────────────────────────────────────────────────────────
const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
function onResize() { width.value = window.innerWidth }
const narrow = computed(() => width.value < 640)
const mainPad = computed(() => (narrow.value ? '28px 18px 12px' : '48px 24px 16px'))

// ─── 当前题目计算 ─────────────────────────────────────────────────────────────
const total = computed(() => questions.value.length)
const currentQ = computed(() => questions.value[idx.value] || {})
const isLast = computed(() => idx.value === total.value - 1)

const isCurrentMulti = computed(() => currentQ.value?.type === 2)
const isCurrentShort = computed(() => currentQ.value?.type === 4)

const currentSel = computed(() => {
  if (isCurrentMulti.value || isCurrentShort.value) return undefined
  return singleAnswers.value[idx.value]
})
const currentMultiSel = computed(() => multiSelections.value[idx.value] || [])
const currentText = computed(() => textAnswers.value[idx.value] || '')

const hasSelection = computed(() => {
  // SHORT_ANSWER：总是允许前进（有无文字均可提交）
  if (isCurrentShort.value) return true
  if (isCurrentMulti.value) return (multiSelections.value[idx.value] || []).length > 0
  return typeof singleAnswers.value[idx.value] === 'number'
})

const topProgressPct = computed(() => {
  if (!total.value) return '0%'
  return Math.round(((idx.value + 1) / total.value) * 100) + '%'
})

// ─── 会话启动 ─────────────────────────────────────────────────────────────────
async function startSession() {
  if (!nodeId.value) {
    errorMsg.value = '缺少知识点 ID，请从学习路径页进入练习。'
    phase.value = 'error'
    return
  }
  if (!courseId.value) {
    errorMsg.value = '请先选择一门课程。'
    phase.value = 'error'
    return
  }
  phase.value = 'loading'
  errorMsg.value = ''
  try {
    const vo = await startPractice({
      studentId: STUDENT_ID,
      courseId: courseId.value,
      nodeId: nodeId.value
    })
    if (!vo || !vo.questions || vo.questions.length === 0) {
      phase.value = 'empty'
      return
    }
    sessionId.value = vo.sessionId
    nodeInfo.value = vo.node
    questions.value = (vo.questions || []).map((pq) => ({
      questionId: pq.questionId,
      c: pq.chapter,
      q: pq.stem,
      o: (pq.options || []).map((opt) => opt.text),
      optionKeys: (pq.options || []).map((opt) => opt.key),
      type: pq.type || 1
    }))
    // 重置答题状态
    idx.value = 0
    singleAnswers.value = {}
    multiSelections.value = {}
    textAnswers.value = {}
    skippedSet.value = {}
    submitResult.value = null
    itemCompleted.value = false
    phase.value = 'answering'
  } catch (e) {
    errorMsg.value = e.message || '组卷失败，请稍后重试。'
    phase.value = 'error'
  }
}

// ─── 答题交互 ─────────────────────────────────────────────────────────────────
function onPick(i) {
  if (isCurrentMulti.value) {
    const curr = [...(multiSelections.value[idx.value] || [])]
    const pos = curr.indexOf(i)
    if (pos >= 0) curr.splice(pos, 1)
    else curr.push(i)
    multiSelections.value = { ...multiSelections.value, [idx.value]: curr }
  } else {
    singleAnswers.value = { ...singleAnswers.value, [idx.value]: i }
  }
}

function onTextInput(text) {
  textAnswers.value = { ...textAnswers.value, [idx.value]: text }
}

function goNext() {
  // 对于单选/判断，必须先选择才能前进（对齐 Diagnostic 行为）
  if (!isCurrentShort.value && !hasSelection.value) return
  if (isLast.value) {
    buildAndSubmit()
  } else {
    idx.value++
  }
}

function goPrev() {
  if (idx.value > 0) idx.value--
}

function onSkip() {
  skippedSet.value = { ...skippedSet.value, [idx.value]: true }
  if (isLast.value) {
    buildAndSubmit()
  } else {
    idx.value++
  }
}

// ─── 构建提交 & 提交 ──────────────────────────────────────────────────────────
function buildAnswerList() {
  const list = []
  questions.value.forEach((q, i) => {
    let studentAnswer = null
    if (q.type === 2) {
      // 多选：按选项键排序，逗号分隔
      const sel = [...(multiSelections.value[i] || [])].sort((a, b) => a - b)
      if (sel.length > 0) {
        studentAnswer = sel.map((si) => q.optionKeys[si]).join(',')
      }
    } else if (q.type === 4) {
      // 简答：任意文本（可为空）
      studentAnswer = textAnswers.value[i] !== undefined ? textAnswers.value[i] : ''
    } else {
      // 单选 / 判断
      const optIdx = singleAnswers.value[i]
      if (typeof optIdx === 'number' && q.optionKeys[optIdx] !== undefined) {
        studentAnswer = q.optionKeys[optIdx]
      }
    }
    if (studentAnswer !== null) {
      list.push({ questionId: q.questionId, studentAnswer })
    }
  })
  return list
}

async function buildAndSubmit() {
  const answers = buildAnswerList()
  if (answers.length === 0) {
    // 全部跳过：提示用户
    errorMsg.value = '请至少回答一道题再提交。'
    phase.value = 'submitError'
    return
  }
  await doSubmit(answers)
}

async function doSubmit(answers) {
  // 支持从 submitError 状态重试（传入 undefined 则重新构建）
  const list = Array.isArray(answers) ? answers : buildAnswerList()
  if (!list.length) {
    errorMsg.value = '没有可提交的答案，请至少回答一道题。'
    phase.value = 'submitError'
    return
  }
  phase.value = 'submitting'
  errorMsg.value = ''
  try {
    const vo = await submitPractice(sessionId.value, {
      studentId: STUDENT_ID,
      answers: list
    })
    submitResult.value = vo
    itemCompleted.value = !!vo.itemCompleted
    phase.value = 'result'
  } catch (e) {
    errorMsg.value = e.message || '提交失败，请稍后重试。'
    phase.value = 'submitError'
  }
}

// ─── 重新练习 ──────────────────────────────────────────────────────────────────
function restartPractice() {
  startSession()
}

// ─── 辅助函数 ──────────────────────────────────────────────────────────────────
function findQuestionIndex(questionId) {
  return questions.value.findIndex((q) => q.questionId === questionId)
}

function fmtPct(score) {
  if (score == null) return '未测'
  return Math.round(score) + '%'
}

function masteryColor(level) {
  // level: "已掌握" / "薄弱" / "未学"（后端 masteryLevel 是文字，非数字）
  if (level === '已掌握') return 'var(--ok)'
  if (level === '薄弱') return 'var(--warn)'
  return 'var(--dim)'
}

// ─── 生命周期 ────────────────────────────────────────────────────────────────
watch(
  [nodeId, courseId],
  ([nid, cid]) => {
    if (nid && cid) startSession()
  },
  { immediate: true }
)

onMounted(() => {
  window.addEventListener('resize', onResize)

  if (import.meta.env.DEV) {
    /**
     * DEV 测试钩子（Playwright / T10 用）：
     * window.__wjPractice 暴露的 shape：
     * {
     *   // 状态 refs（读取 .value）
     *   phase, errorMsg, sessionId, nodeInfo, questions,
     *   idx, singleAnswers, multiSelections, textAnswers, skippedSet,
     *   submitResult, itemCompleted,
     *   // 计算属性 refs
     *   total, currentQ, isLast, hasSelection, currentSel, currentMultiSel, currentText,
     *   // 操作函数
     *   startSession(),
     *   onPick(optionIndex),
     *   onTextInput(text),
     *   goNext(), goPrev(), onSkip(),
     *   restartPractice(),
     * }
     */
    window.__wjPractice = {
      phase, errorMsg, sessionId, nodeInfo, questions,
      idx, singleAnswers, multiSelections, textAnswers, skippedSet,
      submitResult, itemCompleted,
      total, currentQ, isLast, hasSelection, currentSel, currentMultiSel, currentText,
      // T10 动画状态
      displayedMastery, barWidth, masteryDelta,
      startSession, onPick, onTextInput, goNext, goPrev, onSkip, restartPractice
    }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.wj-practice {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--ink);
}

/* ── 顶栏 ─────────────────────────────────────────────────────────── */
.np-topbar {
  height: 48px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
}

.np-title {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ink);
  white-space: nowrap;
  flex: none;
}

.np-node-name {
  font-size: 13px;
  color: var(--mut);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 240px;
}

.np-exit-btn {
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
.np-exit-btn:hover {
  border-color: var(--mut);
  color: var(--ink);
}

/* ── 顶部进度条 ───────────────────────────────────────────────────── */
.np-progress-track {
  height: 2px;
  flex: none;
  background: var(--card2);
}

.np-progress-fill {
  height: 100%;
  background: var(--acc);
  transition: width 0.35s ease;
}

/* ── 居中状态（加载/错误/空） ──────────────────────────────────────── */
.np-center {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 40px 24px;
  text-align: center;
}

.np-loading-text {
  font-size: 15px;
  color: var(--mut);
}

.np-error-msg {
  font-size: 15px;
  color: var(--acc);
  max-width: 480px;
  line-height: 1.6;
}

.np-empty-msg {
  font-size: 15px;
  color: var(--mut);
  line-height: 1.7;
  max-width: 420px;
}

/* ── 按钮 ──────────────────────────────────────────────────────────── */
.np-btn-acc {
  height: 46px;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  padding: 0 32px;
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
.np-btn-acc:hover { opacity: 0.88; }
.np-btn-link { display: flex; }

.np-btn-ghost-link {
  font-size: 13px;
  color: var(--mut);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}
.np-btn-ghost-link:hover { color: var(--ink); }

/* ── 答题主区 ────────────────────────────────────────────────────── */
.np-main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

/* ── 结果区 ─────────────────────────────────────────────────────── */
.np-result {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.np-result-inner {
  min-height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 24px 60px;
  text-align: center;
  max-width: 680px;
  margin: 0 auto;
  animation: npFadeUp 0.4s ease both;
}

@keyframes npFadeUp {
  from { opacity: 0; transform: translateY(14px); }
  to   { opacity: 1; transform: translateY(0); }
}

.np-result-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--acc);
  color: #FFFDF8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  margin-bottom: 24px;
  flex: none;
}

.np-result-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 26px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 20px;
}

/* ── 掌握度动画块（T10） ─────────────────────────────────────────── */
.np-mastery-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
  width: 100%;
  max-width: 320px;
}

.np-mastery-nums {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: center;
}

.np-mastery-before-num {
  font-size: 15px;
  color: var(--mut);
}

.np-mastery-arr {
  font-size: 14px;
  color: var(--mut);
}

.np-mastery-after-num {
  font-size: 26px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.5px;
}

.np-mastery-delta {
  font-size: 13px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 20px;
}
.np-mastery-delta.up {
  color: var(--ok);
  background: rgba(61, 122, 94, 0.10);
}
.np-mastery-delta.down {
  color: var(--acc);
  background: rgba(180, 66, 46, 0.10);
}

.np-mastery-level-badge {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 20px;
  border: 1px solid currentColor;
  opacity: 0.85;
}

.np-mastery-track {
  width: 100%;
  height: 6px;
  background: var(--card2);
  border-radius: 3px;
  overflow: hidden;
}

.np-mastery-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.7s cubic-bezier(0.25, 0.46, 0.45, 0.94);
}

.np-mastery-bar-label {
  font-size: 11px;
  color: var(--mut);
  opacity: 0.7;
  align-self: flex-end;
}

/* ── 薄弱前置提示卡（T10） ───────────────────────────────────────── */
.np-weak-card {
  width: 100%;
  max-width: 460px;
  background: rgba(200, 134, 42, 0.10);
  border: 1px solid var(--warn);
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 14px;
  text-align: left;
}

.np-weak-header {
  font-size: 13px;
  font-weight: 600;
  color: var(--warn);
  margin-bottom: 8px;
}

.np-weak-item {
  font-size: 13px;
  color: var(--ink);
  line-height: 1.75;
}

.np-weak-count {
  font-size: 11.5px;
  color: var(--mut);
  margin-left: 4px;
}

.np-weak-hint {
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--warn);
  opacity: 0.85;
}

/* ── 路径重算引导（T10） ──────────────────────────────────────────── */
.np-regen-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: rgba(61, 122, 94, 0.09);
  border: 1px solid var(--ok);
  border-radius: 10px;
  margin-bottom: 14px;
  width: 100%;
  max-width: 460px;
  flex-wrap: wrap;
}

.np-regen-txt {
  font-size: 13px;
  color: var(--ok);
  flex: 1;
  min-width: 140px;
}

.np-regen-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--ok);
  text-decoration: none;
  padding: 4px 12px;
  border: 1px solid var(--ok);
  border-radius: 8px;
  white-space: nowrap;
  transition: background-color 0.15s, color 0.15s;
}
.np-regen-link:hover {
  background: var(--ok);
  color: #fff;
}

/* 自动完成提示 */
.np-auto-done {
  font-size: 13.5px;
  color: #4a9e6d;
  background: rgba(74, 158, 109, 0.08);
  border: 1px solid rgba(74, 158, 109, 0.25);
  border-radius: 8px;
  padding: 10px 16px;
  margin-bottom: 24px;
  max-width: 460px;
}

/* 逐题结果 */
.np-grades {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
  margin: 20px 0 28px;
  text-align: left;
}

.np-grades-title {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--mut);
  padding: 10px 16px;
  background: var(--card2);
  border-bottom: 1px solid var(--line);
}

.np-grade-row {
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
}

.np-grade-row:last-child { border-bottom: none; }

.np-grade-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.np-grade-num {
  font-size: 12.5px;
  color: var(--mut);
  min-width: 56px;
  flex: none;
}

.np-grade-mark {
  font-size: 13.5px;
  font-weight: 500;
}

.np-grade-row.correct .np-grade-mark { color: var(--ok); }
.np-grade-row.wrong .np-grade-mark { color: var(--acc); }
.np-grade-row.short .np-grade-mark { color: var(--mut); }

.np-grade-ans {
  font-size: 12px;
  color: var(--mut);
}

.np-grade-analysis {
  font-size: 12.5px;
  color: var(--mut);
  line-height: 1.65;
  margin-top: 4px;
  padding-left: 66px;
}

/* 操作按钮 */
.np-result-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.np-btn-restart {
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
.np-btn-restart:hover { color: var(--ink); }

/* ── 辅助色变量（对齐 LearningPath） ────────────────────────────── */
.wj-practice {
  --ok: #4a9e6d;
  --warn: #cc8a3c;
  --dim: #a89f90;
}
</style>
