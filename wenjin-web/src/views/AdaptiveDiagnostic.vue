<template>
  <!-- 根节点带 wj-adaptive：复用诊断页暖白低焦虑配色（作用域内覆盖全局暗色变量） -->
  <div class="wj-adaptive">

    <!-- 顶栏 -->
    <div class="ad-topbar">
      <span v-show="width >= 560" class="ad-subtitle">快速诊断</span>
      <div class="ad-topbar-right">
        <button
          v-if="started && !done"
          class="ad-exit-btn"
          :disabled="abandoning || submitting"
          @click="onAbandon"
        >退出诊断</button>
        <router-link v-else :to="mapLink" class="ad-exit-btn">返回地图</router-link>
      </div>
    </div>

    <!-- 无课程守卫 -->
    <div v-if="noCourse" class="ad-center">
      <div class="ad-empty">
        请先从首页选择一门课程，再开始快速诊断。
        <router-link to="/" class="ad-link">返回选课</router-link>
      </div>
    </div>

    <!-- 开始中 -->
    <div v-else-if="loading" class="ad-center">
      <span class="ad-loading">正在开始诊断…</span>
    </div>

    <!-- 开始失败 -->
    <div v-else-if="error" class="ad-center">
      <div class="ad-error">开始失败：{{ error }}</div>
      <button class="ad-btn-acc" @click="load">重试</button>
    </div>

    <!-- 完成态 -->
    <div v-else-if="done" class="ad-center ad-done">
      <div class="ad-done-inner">
        <div class="ad-done-icon">✓</div>
        <div class="ad-done-title" :style="{ fontSize: doneTitleSize }">快速诊断完成</div>
        <div class="ad-done-sub">
          共用 <strong>{{ askedCount }}</strong> 题。问津已沿知识图谱推断你的掌握分布，
          比完整卷更快定位卡点。
        </div>

        <!-- 四类计数 -->
        <div v-if="summary" class="ad-stats">
          <div class="ad-stat">
            <span class="ad-stat-n ad-n-ok">{{ summary.tested }}</span>
            <span class="ad-stat-l">实测</span>
          </div>
          <div class="ad-stat">
            <span class="ad-stat-n ad-n-warn">{{ summary.inferredPass }}</span>
            <span class="ad-stat-l">推断掌握</span>
          </div>
          <div class="ad-stat">
            <span class="ad-stat-n ad-n-acc">{{ summary.inferredFail }}</span>
            <span class="ad-stat-l">推断薄弱</span>
          </div>
          <div class="ad-stat">
            <span class="ad-stat-n ad-n-dim">{{ summary.unknown }}</span>
            <span class="ad-stat-l">未知</span>
          </div>
        </div>

        <div class="ad-done-actions">
          <button class="ad-btn-acc" @click="goResult">查看诊断报告</button>
          <router-link :to="mapLink" class="ad-btn-ghost-link">查看染色地图</router-link>
        </div>
      </div>
    </div>

    <!-- 作答态（逐探针推进） -->
    <div v-else class="ad-main">
      <AdaptiveProgressRing :progress="progress" />

      <div class="ad-probe">
        <AdaptiveProbeCard
          v-for="(q, i) in questions"
          :key="q.questionId"
          :question="q"
          :index="i + 1"
          :total="questions.length"
          :selected="answers[i]"
          @pick="(opt) => pick(i, opt)"
        />
      </div>

      <div v-if="submitError" class="ad-error ad-submit-err">提交失败：{{ submitError }}</div>

      <button
        class="ad-btn-acc ad-submit"
        :class="{ active: canSubmit && !submitting && !abandoning }"
        :disabled="!canSubmit || submitting || abandoning"
        @click="submitAnswer"
      >{{ submitting ? '提交中…' : '提交本轮' }}</button>

      <div class="ad-hint">问津会根据你的作答动态选题——答错不扣分，越答越准。</div>
    </div>

  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { startAdaptive, answerAdaptive, abandonAdaptive } from '../api/diagnostic.js'
import { useStudentCourse } from '../composables/useStudentCourse.js'
import { useSessionStore } from '../store/session.js'
import { createLatestRequest } from '../utils/latestRequest.js'
import AdaptiveProgressRing from '../components/AdaptiveProgressRing.vue'
import AdaptiveProbeCard from '../components/AdaptiveProbeCard.vue'

const { courseId } = useStudentCourse()
const router = useRouter()
const authSession = useSessionStore()
if (!authSession.hydrated) authSession.hydrate()
const { token: authToken, user: authUser } = storeToRefs(authSession)
const authUserId = computed(() => authUser.value?.id ?? null)

const courseQuery = computed(() => (courseId.value ? { courseId: courseId.value } : {}))
const mapLink = computed(() => ({ path: '/map', query: courseQuery.value }))

// 响应式视口宽度
const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
function onResize() { width.value = window.innerWidth }
const doneTitleSize = computed(() => (width.value < 640 ? '24px' : '30px'))

// ─── 状态 ──────────────────────────────────────────────────────────────────
const sessionId = ref(null)
const done = ref(false)
const started = ref(false)   // start 成功后为 true（控制顶栏「退出诊断」显隐）
const probe = ref(null)
const progress = ref({ tested: 0, inferred: 0, unknown: 0, budgetLeft: 0 })
const summary = ref(null)

/** 当前探针题目（映射后：{ questionId, c, q, o[], optionKeys[], type }） */
const questions = ref([])
/** 本探针作答：{ [题内下标]: 选项下标 } */
const answers = reactive({})
const askedCount = ref(0)    // 累计已用题数（跨探针）

const loading = ref(false)
const error = ref('')
const submitting = ref(false)
const submitError = ref('')
const abandoning = ref(false)
const noCourse = ref(false)
const startRequest = createLatestRequest()
const answerRequest = createLatestRequest()
const abandonRequest = createLatestRequest()
let activeStartRevision = 0
let activeOwner = null
let disposed = false

function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

function captureOwner(id = courseId.value) {
  return {
    token: authToken.value,
    userId: authUserId.value,
    courseId: Number(id)
  }
}

function isCurrentOwner(owner) {
  return !disposed
    && authToken.value === owner.token
    && authUserId.value === owner.userId
    && Number(courseId.value) === owner.courseId
}

function isCurrentStart(request, owner) {
  return request.isCurrent() && isCurrentOwner(owner)
}

function isCurrentAdaptive(owner, revision, adaptiveSessionId) {
  return isCurrentOwner(owner)
    && startRequest.revision === revision
    && activeStartRevision === revision
    && activeOwner === owner
    && sessionId.value === adaptiveSessionId
}

function resetAdaptiveState() {
  sessionId.value = null
  activeStartRevision = 0
  activeOwner = null
  done.value = false
  started.value = false
  probe.value = null
  progress.value = { tested: 0, inferred: 0, unknown: 0, budgetLeft: 0 }
  summary.value = null
  questions.value = []
  for (const key of Object.keys(answers)) delete answers[key]
  askedCount.value = 0
  submitting.value = false
  submitError.value = ''
  abandoning.value = false
}

// ─── 计算属性 ───────────────────────────────────────────────────────────────
// 只有当前探针每道题都已选答案时才可提交
const canSubmit = computed(() =>
  questions.value.length > 0 &&
  questions.value.every((_, i) => typeof answers[i] === 'number')
)

// ─── 数据映射 ───────────────────────────────────────────────────────────────
// 后端脱敏 PaperQuestionVO → 组件内部格式（写法同 Diagnostic.vue）
function mapQuestions(p) {
  return (p?.questions || []).map((pq) => ({
    questionId: pq.questionId,
    c: pq.chapter,
    q: pq.stem,
    o: (pq.options || []).map((opt) => opt.text),
    optionKeys: (pq.options || []).map((opt) => opt.key),
    type: pq.type
  }))
}

// 应用一次接口返回（start / answer 通用），并重置本探针作答
function applyResponse(resp) {
  done.value = !!resp.done
  if (resp.progress) progress.value = resp.progress
  summary.value = resp.summary || null
  probe.value = resp.probe || null
  questions.value = done.value ? [] : mapQuestions(resp.probe)
  for (const k of Object.keys(answers)) delete answers[k]
}

// ─── 作答交互 ────────────────────────────────────────────────────────────────
function pick(qIndex, optIndex) {
  answers[qIndex] = optIndex
}

// ─── 开始 ────────────────────────────────────────────────────────────────────
async function load() {
  if (disposed) return
  const id = Number(courseId.value)
  const owner = captureOwner(id)
  const request = startRequest.next()
  answerRequest.cancel()
  abandonRequest.cancel()
  resetAdaptiveState()
  // 无条件调用、守卫落在此处（不静默回落课程1）
  if (!(id > 0)) {
    noCourse.value = true
    loading.value = false
    return
  }
  noCourse.value = false
  loading.value = true
  error.value = ''
  submitError.value = ''
  try {
    const resp = await startAdaptive({ courseId: id }, { signal: request.signal })
    if (!isCurrentStart(request, owner)) return
    sessionId.value = resp.sessionId
    activeStartRevision = request.revision
    activeOwner = owner
    started.value = true
    applyResponse(resp)
  } catch (e) {
    if (!isCurrentStart(request, owner) || isAbortError(e)) return
    error.value = e.message || '未知错误'
  } finally {
    if (isCurrentStart(request, owner)) loading.value = false
  }
}

// ─── 逐探针提交 ──────────────────────────────────────────────────────────────
async function submitAnswer() {
  const owner = activeOwner
  const revision = activeStartRevision
  const adaptiveSessionId = sessionId.value
  if (!owner || adaptiveSessionId == null || !isCurrentAdaptive(owner, revision, adaptiveSessionId)) {
    submitError.value = '\u8bca\u65ad\u4f1a\u8bdd\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u5f00\u59cb'
    return
  }
  if (submitting.value || abandoning.value || !canSubmit.value) return
  submitting.value = true
  submitError.value = ''
  const request = answerRequest.next()
  const count = questions.value.length
  const payload = {
    answers: questions.value.map((q, i) => ({
      questionId: q.questionId,
      studentAnswer: q.optionKeys[answers[i]]   // 与固定卷 optionKey 同格式（如 'A'）
    }))
  }
  try {
    const resp = await answerAdaptive(adaptiveSessionId, payload, { signal: request.signal })
    if (!request.isCurrent() || !isCurrentAdaptive(owner, revision, adaptiveSessionId)) return
    askedCount.value += count           // 仅成功时累计，重试不重复计
    applyResponse(resp)
  } catch (e) {
    if (!request.isCurrent() || !isCurrentAdaptive(owner, revision, adaptiveSessionId) || isAbortError(e)) return
    submitError.value = e.message || '未知错误'
  } finally {
    if (request.isCurrent() && isCurrentAdaptive(owner, revision, adaptiveSessionId)) submitting.value = false
  }
}

// ─── 退出（放弃会话） ─────────────────────────────────────────────────────────
async function onAbandon() {
  if (abandoning.value || submitting.value) return
  if (typeof window !== 'undefined' &&
      !window.confirm('确定退出本次快速诊断吗？已答部分不再继续。')) return
  const owner = activeOwner
  const revision = activeStartRevision
  const adaptiveSessionId = sessionId.value
  if (!owner || adaptiveSessionId == null || !isCurrentAdaptive(owner, revision, adaptiveSessionId)) return
  const request = abandonRequest.next()
  abandoning.value = true
  try {
    await abandonAdaptive(adaptiveSessionId, {}, { signal: request.signal })
  } catch {
    // abandon 失败不阻塞离开（会话悬挂无害，M2 无续答）
  } finally {
    if (request.isCurrent() && isCurrentAdaptive(owner, revision, adaptiveSessionId)) {
      abandoning.value = false
      router.push({ path: '/map', query: { courseId: owner.courseId } })
    }
  }
}

// ─── 结束跳转 ────────────────────────────────────────────────────────────────
// 不逐字信任 resultUrl（后端 /diagnostic/result 与前端真实路由 /result 有接缝，T8 修）
function goResult() {
  router.push({ path: '/result', query: courseQuery.value })
}

// 无条件 load：courseId 为空时由 load 内守卫给出选课引导
watch([courseId, authToken, authUserId], () => load(), { immediate: true })

// ─── 生命周期 ────────────────────────────────────────────────────────────────
onMounted(() => {
  window.addEventListener('resize', onResize)

  /**
   * DEV 测试钩子（Playwright 真机 e2e 用）：window.__wjAdaptive
   * 状态 refs（读 .value）：sessionId, done, started, probe, progress, summary,
   *   questions, answers, askedCount, loading, error, submitting, submitError,
   *   abandoning, noCourse, canSubmit
   * 操作：pick(qIndex, optIndex) 选答、submitAnswer() 提交本轮、
   *   abandon() 退出（含 confirm，e2e 需 accept dialog）、goResult() 去结果页、load() 重开
   */
  if (import.meta.env.DEV) {
    window.__wjAdaptive = {
      sessionId, done, started, probe, progress, summary,
      questions, answers, askedCount,
      loading, error, submitting, submitError, abandoning, noCourse,
      canSubmit,
      pick, submitAnswer, abandon: onAbandon, goResult, load
    }
  }
})

onBeforeUnmount(() => {
  disposed = true
  startRequest.cancel()
  answerRequest.cancel()
  abandonRequest.cancel()
  window.removeEventListener('resize', onResize)
  if (window.__wjAdaptive?.load === load) delete window.__wjAdaptive
})
</script>

<style scoped>
/* 作用域内覆盖全局暗色变量为暖白配色；墨夜主题下 :root.dark 仍生效（变量按主题切换） */
.wj-adaptive {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--ink);
}

/* ── 顶栏 ─────────────────────────────────────────────────────────── */
.ad-topbar {
  height: 48px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
}

.ad-subtitle {
  font-size: 13.5px;
  font-weight: 500;
  white-space: nowrap;
  color: var(--ink);
}

.ad-topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.ad-exit-btn {
  font-size: 12.5px;
  color: var(--mut);
  text-decoration: none;
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  white-space: nowrap;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.15s, color 0.15s;
}
.ad-exit-btn:hover {
  border-color: var(--mut);
  color: var(--ink);
}
.ad-exit-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

/* ── 居中状态（无课程/加载/错误/完成） ─────────────────────────────── */
.ad-center {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 40px 24px;
  text-align: center;
}

.ad-loading {
  font-size: 15px;
  color: var(--mut);
}

.ad-error {
  font-size: 15px;
  color: var(--acc);
}

.ad-empty {
  font-size: 15px;
  color: var(--mut);
  line-height: 1.7;
}

.ad-link {
  color: var(--acc);
}

/* ── 完成态 ──────────────────────────────────────────────────────── */
.ad-done-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px 0 40px;
  animation: adFadeUp 0.5s ease both;
}

@keyframes adFadeUp {
  from { opacity: 0; transform: translateY(16px); }
  to   { opacity: 1; transform: translateY(0); }
}

.ad-done-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--acc);
  color: #FFFDF8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  margin-bottom: 24px;
}

.ad-done-title {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-weight: 600;
  line-height: 1.5;
  margin-bottom: 12px;
  color: var(--ink);
}

.ad-done-sub {
  font-size: 14px;
  color: var(--mut);
  line-height: 1.8;
  max-width: 440px;
  margin-bottom: 28px;
}

/* 四类计数 */
.ad-stats {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 32px;
}

.ad-stat {
  min-width: 76px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
}

.ad-stat-n {
  font-size: 26px;
  font-weight: 600;
  line-height: 1;
}
.ad-n-ok { color: var(--ok); }
.ad-n-warn { color: var(--warn); }
.ad-n-acc { color: var(--acc); }
.ad-n-dim { color: var(--dim); }

.ad-stat-l {
  font-size: 12px;
  color: var(--mut);
}

.ad-done-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.ad-btn-ghost-link {
  background: transparent;
  border: none;
  color: var(--mut);
  font-size: 12.5px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.ad-btn-ghost-link:hover {
  color: var(--ink);
}

/* ── 作答主区 ────────────────────────────────────────────────────── */
.ad-main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
  box-sizing: border-box;
  padding: 28px 24px 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.ad-probe {
  width: 100%;
  margin-top: 26px;
}

.ad-submit-err {
  width: 100%;
  text-align: center;
  margin: 16px 0 0;
}

/* ── 通用主按钮 ──────────────────────────────────────────────────── */
.ad-btn-acc {
  height: 46px;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
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
.ad-btn-acc:hover {
  opacity: 0.88;
}

/* 提交按钮：未答满时置灰不可点（同 QuestionAnswerCard 的 next 语义） */
.ad-submit {
  margin-top: 24px;
  width: 100%;
  max-width: 320px;
  background: var(--card2);
  color: var(--mut);
  cursor: not-allowed;
}
.ad-submit.active {
  background: var(--acc);
  color: #FFFDF8;
  cursor: pointer;
}
.ad-submit:disabled {
  opacity: 0.9;
}

.ad-hint {
  text-align: center;
  font-size: 12px;
  color: var(--mut);
  opacity: 0.75;
  margin-top: 18px;
}
</style>
