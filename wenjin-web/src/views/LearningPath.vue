<template>
  <div class="wj-path">
    <div v-if="loading" class="lp-center">加载学习路径中…</div>
    <div v-else-if="error" class="lp-center">
      <div class="lp-err">加载失败：{{ error }}</div>
      <button class="lp-btn-acc" @click="load">重试</button>
    </div>

    <div v-else-if="!data || !data.steps.length" class="lp-center">
      <div class="lp-title">还没有学习路径</div>
      <div class="lp-sub">先在诊断结果页点「生成学习路径」，问津会按前置依赖为你排好顺序。</div>
      <router-link to="/result" class="lp-btn-acc lp-btn-link">去诊断结果</router-link>
    </div>

    <div v-else class="lp-wrap">
      <div class="lp-head">
        <div class="lp-meta">
          <router-link to="/result" class="lp-underline">查看诊断依据</router-link>
        </div>
        <h2 class="lp-h2">突破「{{ data.targetNode ? data.targetNode.name : '目标' }}」</h2>
        <p class="lp-sub2">{{ data.conclusionText }}</p>
        <div class="lp-progress">
          <div class="lp-progress-track">
            <div class="lp-progress-fill" :style="{ width: progressPct }"></div>
          </div>
          <span class="lp-progress-text">{{ data.progress.done }} / {{ data.progress.total }} 步已完成</span>
        </div>
      </div>

      <div class="lp-timeline">
        <div class="lp-line"></div>
        <div v-for="(s, i) in data.steps" :key="s.itemId" class="lp-step">
          <div class="lp-mark" :class="markClass(s, i)">
            <span v-if="s.status === 1">✓</span><span v-else>{{ s.stepOrder }}</span>
          </div>
          <div class="lp-card" :class="{ current: isCurrent(i), done: s.status === 1 }">
            <div class="lp-card-head">
              <span class="lp-node-name" :class="{ struck: s.status === 1 }">{{ s.name }}</span>
              <span class="lp-role">{{ roleLabel(s.role) }}{{ s.chapter ? ' · ' + s.chapter : '' }}</span>
              <span class="lp-state" :style="{ color: stateColor(s) }">{{ stateText(s) }}</span>
            </div>
            <div class="lp-reason"><span class="lp-reason-tag">为什么学这个</span> — {{ s.reason }}</div>
            <div class="lp-card-foot">
              <span class="lp-score" :style="{ color: levelColor(s.masteryLevel) }">掌握度 {{ pct(s.masteryScore) }}</span>
              <button v-if="s.status !== 1" class="lp-btn-done" :disabled="completingId === s.itemId"
                      @click="markDone(s.itemId)">
                {{ completingId === s.itemId ? '提交中…' : '标记完成' }}
              </button>
              <span v-else class="lp-done-at">已完成</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { fetchCurrentPath, completeItem } from '../api/path.js'
import { useStudentCourse } from '../composables/useStudentCourse.js'

const { courseId } = useStudentCourse()

// 从 localStorage 读取当前登录用户
function readUser() {
  try { return JSON.parse(localStorage.getItem('wj_user')) } catch { return null }
}
const currentUser = readUser()
const DEMO_STUDENT_ID = currentUser?.id || 2
const data = ref(null)
const loading = ref(false)
const error = ref('')
const completingId = ref(null)

function levelColor(level) {
  return level === 2 ? 'var(--ok)' : level === 1 ? 'var(--warn)' : 'var(--dim)'
}
function roleLabel(role) {
  return role === 'root' ? '根因' : role === 'stuck' ? '你的卡点' : '前置'
}
function pct(score) {
  return score == null ? '未测' : Math.round(score) + '%'
}
const progressPct = computed(() => {
  if (!data.value || !data.value.progress.total) return '0%'
  return Math.round((data.value.progress.done / data.value.progress.total) * 100) + '%'
})
// 当前步 = 第一个未完成步
const currentIdx = computed(() => {
  if (!data.value) return -1
  return data.value.steps.findIndex((s) => s.status !== 1)
})
function isCurrent(i) {
  return i === currentIdx.value
}
function markClass(s, i) {
  if (s.status === 1) return 'done'
  if (isCurrent(i)) return 'current'
  return 'pending'
}
function stateText(s) {
  if (s.status === 1) return '已完成'
  return data.value && currentIdx.value === data.value.steps.indexOf(s) ? '当前步骤' : '待开始'
}
function stateColor(s) {
  if (s.status === 1) return 'var(--ok)'
  return isCurrentStep(s) ? 'var(--acc)' : 'var(--mut)'
}
function isCurrentStep(s) {
  return data.value && data.value.steps.indexOf(s) === currentIdx.value
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (!courseId.value) {
      error.value = '请先从首页选择一门课程'
      return
    }
    data.value = await fetchCurrentPath(DEMO_STUDENT_ID, courseId.value)
  } catch (e) {
    error.value = e.message || '未知错误'
  } finally {
    loading.value = false
  }
}

async function markDone(itemId) {
  if (completingId.value) return
  completingId.value = itemId
  try {
    await completeItem(itemId)
    await load()
  } catch (e) {
    error.value = e.message || '未知错误'
  } finally {
    completingId.value = null
  }
}

watch(courseId, (id) => {
  if (id) load()
}, { immediate: true })

onMounted(() => {
  if (import.meta.env.DEV) {
    window.__wjPath = { data, loading, error, completingId, load, markDone }
  }
})
</script>

<style scoped>
.wj-path {
  --ok: #4a9e6d; --warn: #cc8a3c; --dim: #a89f90;
  flex: 1; min-height: 0;
  background: var(--bg); color: var(--ink); overflow-y: auto;
}
.lp-center {
  min-height: 60vh; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 14px; color: var(--mut); text-align: center; padding: 40px 24px;
}
.lp-title { font-size: 22px; font-weight: 600; color: var(--ink); }
.lp-sub { font-size: 14px; max-width: 420px; line-height: 1.7; }
.lp-err { color: var(--acc); font-size: 14px; }
.lp-btn-acc {
  height: 42px; padding: 0 26px; background: var(--acc); border: none; border-radius: 9px;
  color: #fffdf8; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit;
  display: inline-flex; align-items: center; text-decoration: none;
}
.lp-btn-link { box-sizing: border-box; }
.lp-wrap { max-width: 860px; margin: 0 auto; padding: 36px 24px 64px; }
.lp-head { margin-bottom: 30px; }
.lp-meta { font-size: 12.5px; color: var(--mut); margin-bottom: 12px; }
.lp-underline { color: var(--mut); text-decoration: underline; text-underline-offset: 3px; }
.lp-h2 { font-family: 'Noto Serif SC', serif; font-size: 28px; font-weight: 600; margin: 0 0 10px; }
.lp-sub2 { font-size: 14.5px; color: var(--mut); line-height: 1.8; max-width: 560px; margin: 0 0 22px; }
.lp-progress { display: flex; align-items: center; gap: 14px; }
.lp-progress-track {
  flex: 1; max-width: 320px; height: 6px; background: var(--card2);
  border: 1px solid var(--line); border-radius: 99px; overflow: hidden; box-sizing: border-box;
}
.lp-progress-fill { height: 100%; background: var(--ok); border-radius: 99px; transition: width 0.4s; }
.lp-progress-text { font-size: 12.5px; color: var(--mut); white-space: nowrap; }
.lp-timeline { position: relative; }
.lp-line { position: absolute; left: 17px; top: 18px; bottom: 24px; width: 2px; background: var(--line); }
.lp-step { position: relative; display: grid; grid-template-columns: 36px 1fr; gap: 18px; margin-bottom: 20px; }
.lp-mark {
  position: relative; z-index: 1; width: 36px; height: 36px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 600;
  box-sizing: border-box;
}
.lp-mark.done { background: var(--ok); color: #fffdf8; }
.lp-mark.current { background: var(--acc); color: #fffdf8; }
.lp-mark.pending { background: var(--bg); border: 2px solid var(--line); color: var(--mut); }
.lp-card { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 18px 22px; }
.lp-card.current { border: 1.5px solid var(--acc); }
.lp-card.done { opacity: 0.9; }
.lp-card-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.lp-node-name { font-size: 16px; font-weight: 600; }
.lp-node-name.struck { text-decoration: line-through; text-decoration-color: var(--mut); color: var(--mut); }
.lp-role { font-size: 11.5px; color: var(--mut); }
.lp-state { margin-left: auto; font-size: 12px; white-space: nowrap; }
.lp-reason { font-size: 13px; color: var(--mut); line-height: 1.7; margin-bottom: 14px; }
.lp-reason-tag { color: var(--acc); }
.lp-card-foot { display: flex; align-items: center; gap: 12px; }
.lp-score { font-size: 12.5px; }
.lp-btn-done {
  margin-left: auto; height: 34px; padding: 0 18px; background: var(--acc); border: none;
  border-radius: 8px; color: #fffdf8; font-size: 13px; cursor: pointer; font-family: inherit;
}
.lp-btn-done:disabled { opacity: 0.6; cursor: default; }
.lp-done-at { margin-left: auto; font-size: 12px; color: var(--ok); }
@media (max-width: 760px) { .lp-h2 { font-size: 23px; } }
</style>
