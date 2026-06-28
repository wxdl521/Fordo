<template>
  <div class="wj-result">
    <div v-if="loading" class="rs-center">加载诊断结果中…</div>
    <div v-else-if="error" class="rs-center">
      <div class="rs-err">加载失败：{{ error }}</div>
      <button class="rs-btn-acc" @click="load">重试</button>
    </div>

    <div v-else-if="data && !data.hasWeakness" class="rs-center">
      <div class="rs-title">暂无明显卡点</div>
      <div class="rs-sub">当前没有检测到薄弱知识点。先去做一次入口诊断，或在地图上继续探索。</div>
      <router-link :to="diagnosticLink" class="rs-btn-acc rs-btn-link">去诊断</router-link>
    </div>

    <div v-else-if="data" class="rs-wrap">
      <div class="rs-meta">入口诊断 · 已作答 {{ data.questionsAnswered }} 题 · 覆盖
        {{ data.coverage.covered }} / {{ data.coverage.total }} 个知识点</div>

      <!-- 结论卡 -->
      <section class="rs-card">
        <div class="rs-label">诊断结论</div>
        <h2 class="rs-h2">你卡在「{{ data.stuckNode.name }}」</h2>
        <p class="rs-conclusion">{{ data.conclusionText }}</p>

        <div class="rs-chain">
          <template v-for="(c, i) in data.chain" :key="c.nodeCode">
            <div class="rs-chain-node" :class="{ root: c.role === 'root', stuck: c.role === 'stuck' }">
              <span v-if="c.role === 'root'" class="rs-tag-root">根因</span>
              <span v-else-if="c.role === 'stuck'" class="rs-tag-stuck">当前卡点</span>
              <div class="rs-chain-name">{{ c.name }}</div>
              <div class="rs-chain-score" :style="{ color: levelColor(c.masteryLevel) }">
                {{ pct(c.masteryScore) }} <span class="rs-chain-lv">{{ levelLabel(c.masteryLevel) }}</span>
              </div>
            </div>
            <span v-if="i < data.chain.length - 1" class="rs-arrow">→</span>
          </template>
        </div>

        <div class="rs-actions">
          <button class="rs-btn-acc" :disabled="generating" @click="goPath">
            {{ generating ? '生成中…' : '生成学习路径' }}
          </button>
          <router-link to="/map" class="rs-btn-ghost">在地图上查看</router-link>
        </div>
        <div v-if="genError" class="rs-err rs-genmsg">生成失败：{{ genError }}</div>
      </section>

      <div class="rs-grid">
        <!-- 判断依据 -->
        <section class="rs-card">
          <div class="rs-label">判断依据</div>
          <div v-for="(b, i) in data.bases" :key="i" class="rs-basis"
               :class="{ last: i === data.bases.length - 1 }">
            <span class="rs-basis-ord">{{ b.order }}</span>
            <div class="rs-basis-body">
              <div class="rs-basis-text">{{ b.text }}</div>
              <div class="rs-basis-sub">{{ b.sub }}</div>
            </div>
            <span class="rs-basis-pct" :style="{ color: levelColor(b.level) }">{{ pct(b.score) }}</span>
          </div>

          <template v-if="data.pendingVerification.length">
            <div class="rs-label rs-pending-label">待验证前置点</div>
            <div v-for="p in data.pendingVerification" :key="p.nodeCode" class="rs-pending">
              <span class="rs-pending-name">{{ p.name }}</span>
              <span class="rs-pending-meta">无作答数据 · 建议补测 {{ p.suggestedQuestionIds.length }} 题</span>
            </div>
          </template>
        </section>

        <!-- 本次诊断 / 分布 -->
        <section class="rs-card">
          <div class="rs-label">掌握度分布</div>
          <div v-for="d in distRows" :key="d.label" class="rs-dist">
            <div class="rs-dist-head"><span>{{ d.label }}</span><span class="rs-dist-cnt">{{ d.count }} 个</span></div>
            <div class="rs-dist-track"><div class="rs-dist-fill" :style="{ width: d.w, background: d.color }"></div></div>
          </div>
          <router-link :to="diagnosticLink" class="rs-btn-ghost rs-redo">重新诊断</router-link>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchResult } from '../api/diagnostic.js'
import { generatePath } from '../api/path.js'
import { useStudentCourse } from '../composables/useStudentCourse.js'

const { courseId } = useStudentCourse()

// 从 localStorage 读取当前登录用户
function readUser() {
  try { return JSON.parse(localStorage.getItem('wj_user')) } catch { return null }
}
const currentUser = readUser()
const DEMO_STUDENT_ID = currentUser?.id || 2
const router = useRouter()
const data = ref(null)
const loading = ref(false)
const error = ref('')
const generating = ref(false)
const genError = ref('')

const diagnosticLink = computed(() => ({
  path: '/diagnostic',
  query: courseId.value ? { courseId: courseId.value } : {}
}))

function levelColor(level) {
  return level === 2 ? 'var(--ok)' : level === 1 ? 'var(--warn)' : 'var(--dim)'
}
function levelLabel(level) {
  return level === 2 ? '已掌握' : level === 1 ? '薄弱' : '待突破'
}
function pct(score) {
  return score == null ? '—' : Math.round(score) + '%'
}

const distRows = computed(() => {
  const d = data.value ? data.value.distribution : { mastered: 0, weak: 0, unlearned: 0 }
  const total = Math.max(1, d.mastered + d.weak + d.unlearned)
  return [
    { label: '已掌握', count: d.mastered, w: Math.round((d.mastered / total) * 100) + '%', color: 'var(--ok)' },
    { label: '薄弱 · 待修', count: d.weak, w: Math.round((d.weak / total) * 100) + '%', color: 'var(--warn)' },
    { label: '未学', count: d.unlearned, w: Math.round((d.unlearned / total) * 100) + '%', color: 'var(--dim)' }
  ]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (!courseId.value) {
      error.value = '请先从首页选择一门课程'
      return
    }
    data.value = await fetchResult(DEMO_STUDENT_ID, courseId.value)
  } catch (e) {
    error.value = e.message || '未知错误'
  } finally {
    loading.value = false
  }
}

async function goPath() {
  if (generating.value) return
  generating.value = true
  genError.value = ''
  try {
    await generatePath({ studentId: DEMO_STUDENT_ID, courseId: courseId.value })
    router.push({ path: '/path', query: { courseId: courseId.value } })
  } catch (e) {
    genError.value = e.message || '未知错误'
  } finally {
    generating.value = false
  }
}

watch(courseId, (id) => {
  if (id) load()
}, { immediate: true })

onMounted(() => {
  if (import.meta.env.DEV) {
    window.__wjResult = { data, loading, error, generating, genError, load, goPath }
  }
})
</script>

<style scoped>
.wj-result {
  --ok: #4a9e6d; --warn: #cc8a3c; --dim: #a89f90;
  flex: 1; min-height: 0;
  background: var(--bg); color: var(--ink);
  overflow-y: auto;
}
.rs-center {
  min-height: 60vh; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 14px; color: var(--mut); text-align: center; padding: 40px 24px;
}
.rs-title { font-size: 22px; font-weight: 600; color: var(--ink); }
.rs-sub { font-size: 14px; max-width: 420px; line-height: 1.7; }
.rs-err { color: var(--acc); font-size: 14px; }
.rs-wrap { max-width: 1040px; margin: 0 auto; padding: 32px 24px 60px; }
.rs-meta { font-size: 12.5px; color: var(--mut); margin-bottom: 16px; }
.rs-card {
  background: var(--card); border: 1px solid var(--line); border-radius: 14px;
  padding: 28px 32px; margin-bottom: 22px;
}
.rs-label { font-size: 11.5px; letter-spacing: 3px; color: var(--mut); margin-bottom: 14px; }
.rs-h2 { font-family: 'Noto Serif SC', serif; font-size: 28px; font-weight: 600; margin: 0 0 10px; line-height: 1.4; }
.rs-conclusion { font-size: 15px; color: var(--mut); line-height: 1.8; max-width: 640px; margin: 0 0 26px; }
.rs-chain { display: flex; align-items: stretch; flex-wrap: wrap; gap: 10px; margin-bottom: 26px; }
.rs-chain-node {
  position: relative; background: var(--card2); border: 1px solid var(--line);
  border-radius: 10px; padding: 14px 18px; min-width: 140px;
}
.rs-chain-node.root { border: 1.5px solid var(--acc); }
.rs-chain-node.stuck { border: 1px solid var(--acc); }
.rs-tag-root, .rs-tag-stuck {
  position: absolute; top: -9px; left: 12px; font-size: 10.5px; letter-spacing: 1px;
  border-radius: 5px; padding: 2px 7px;
}
.rs-tag-root { background: var(--acc); color: #fffdf8; }
.rs-tag-stuck { background: var(--card); border: 1px solid var(--acc); color: var(--acc); }
.rs-chain-name { font-size: 13.5px; font-weight: 500; margin-bottom: 6px; }
.rs-chain-score { font-size: 19px; font-weight: 600; }
.rs-chain-lv { font-size: 11.5px; color: var(--mut); font-weight: 400; }
.rs-arrow { display: flex; align-items: center; color: var(--mut); }
.rs-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.rs-btn-acc {
  height: 42px; padding: 0 26px; background: var(--acc); border: none; border-radius: 9px;
  color: #fffdf8; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit;
  display: inline-flex; align-items: center; text-decoration: none;
}
.rs-btn-acc:disabled { opacity: 0.6; cursor: default; }
.rs-btn-link { box-sizing: border-box; }
.rs-btn-ghost {
  height: 42px; padding: 0 18px; border: 1px solid var(--line); border-radius: 9px;
  color: var(--ink); font-size: 13.5px; text-decoration: none; display: inline-flex; align-items: center;
  background: transparent; cursor: pointer; font-family: inherit;
}
.rs-genmsg { margin-top: 12px; }
.rs-grid { display: grid; grid-template-columns: 1fr 360px; gap: 22px; align-items: start; }
.rs-basis { display: flex; gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.rs-basis.last { border-bottom: none; }
.rs-basis-ord { font-family: 'Noto Serif SC', serif; font-size: 15px; color: var(--mut); flex: none; }
.rs-basis-body { flex: 1; }
.rs-basis-text { font-size: 14px; line-height: 1.65; margin-bottom: 4px; }
.rs-basis-sub { font-size: 12.5px; color: var(--mut); }
.rs-basis-pct { font-size: 19px; font-weight: 600; flex: none; }
.rs-pending-label { margin-top: 22px; }
.rs-pending { display: flex; justify-content: space-between; align-items: baseline; padding: 8px 0; font-size: 13px; }
.rs-pending-name { font-weight: 500; }
.rs-pending-meta { font-size: 12px; color: var(--mut); }
.rs-dist { margin-bottom: 14px; }
.rs-dist-head { display: flex; justify-content: space-between; font-size: 12.5px; margin-bottom: 5px; }
.rs-dist-cnt { color: var(--mut); }
.rs-dist-track { height: 6px; background: var(--card2); border-radius: 99px; overflow: hidden; }
.rs-dist-fill { height: 100%; border-radius: 99px; }
.rs-redo { margin-top: 18px; width: 100%; justify-content: center; }
@media (max-width: 760px) {
  .rs-grid { grid-template-columns: 1fr; }
  .rs-h2 { font-size: 23px; }
}
</style>
