<template>
  <div class="wj-knowledge">
    <div v-if="loading" class="kp-center">加载中…</div>
    <div v-else-if="error" class="kp-center">
      <div class="kp-err">加载失败：{{ error }}</div>
      <button class="kp-btn-acc" @click="load">重试</button>
    </div>

    <div v-else-if="!node" class="kp-center">
      <div class="kp-title">未找到该知识点</div>
      <div class="kp-sub">请检查链接是否正确，或返回染色地图重新选择。</div>
      <router-link to="/map" class="kp-btn-acc kp-btn-link">去染色地图</router-link>
    </div>

    <div v-else class="kp-wrap">
      <!-- 面包屑 -->
      <div class="kp-breadcrumb">
        <router-link to="/path" class="kp-link">学习路径</router-link>
        <span class="kp-sep">/</span>
        <span class="kp-current">知识点详情</span>
        <span class="kp-sep">/</span>
        <span class="kp-node-name">{{ node.name }}</span>
      </div>

      <!-- 知识点头部卡片 -->
      <div class="kp-header">
        <div class="kp-meta">
          <span class="kp-chapter">{{ node.chapter || '—' }}</span>
          <span v-if="node.isKey" class="kp-badge key">重点</span>
        </div>
        <h1 class="kp-h1">{{ node.name }}</h1>
        <p class="kp-desc">{{ node.description || '（暂无描述）' }}</p>
      </div>

      <!-- 掌握度卡片 -->
      <div class="kp-card">
        <h3 class="kp-card-title">掌握情况</h3>
        <div class="kp-mastery">
          <div class="kp-mastery-left">
            <div class="kp-score">{{ pct(node.masteryScore) }}</div>
            <div class="kp-level" :style="{ color: levelColor(levelOf(node)) }">
              {{ masteryText(node) }}
            </div>
          </div>
          <div class="kp-mastery-bar">
            <div class="kp-mastery-fill" :style="{ width: pct(node.masteryScore), background: levelColor(levelOf(node)) }"></div>
          </div>
        </div>
      </div>

      <!-- 前置知识点卡片 -->
      <div class="kp-card">
        <h3 class="kp-card-title">前置知识点</h3>
        <div v-if="prereqs.length" class="kp-prereqs">
          <div
            v-for="p in prereqs"
            :key="p.nodeCode"
            class="kp-prereq-item"
            @click="goNode(p.nodeCode)"
          >
            <div class="kp-prereq-head">
              <span class="kp-prereq-name">{{ p.name }}</span>
              <span class="kp-prereq-mastery" :style="{ color: levelColor(levelOf(p)) }">
                {{ masteryText(p) }}
              </span>
            </div>
            <div class="kp-prereq-bar">
              <div class="kp-prereq-fill" :style="{ width: pct(p.masteryScore), background: levelColor(levelOf(p)) }"></div>
            </div>
          </div>
        </div>
        <p v-else class="kp-empty">无（这是一个入门/根知识点）</p>
      </div>

      <!-- 向伴侣提问按钮 -->
      <button class="kp-btn-companion" @click="askCompanion">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
        向伴侣提问
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchGraph } from '../api/graph.js'

const DEMO_STUDENT_ID = 2
const DEMO_COURSE_ID = 1

const route = useRoute()
const router = useRouter()

const graph = ref(null)
const loading = ref(false)
const error = ref('')

// 从路由查询参数获取 nodeCode
const nodeCode = computed(() => route.query.nodeCode || '')

// 当前节点
const node = computed(() => {
  if (!graph.value || !nodeCode.value) return null
  return graph.value.nodes.find((n) => n.nodeCode === nodeCode.value) || null
})

// 前置知识点
const prereqs = computed(() => {
  if (!graph.value || !nodeCode.value) return []
  const edges = graph.value.edges || []
  const nodeMap = new Map()
  graph.value.nodes.forEach((n) => nodeMap.set(n.nodeCode, n))

  return edges
    .filter((e) => e.type === '前置' && e.target === nodeCode.value)
    .map((e) => nodeMap.get(e.source))
    .filter(Boolean)
})

// 掌握度级别（0=未学 / 1=薄弱 / 2=已掌握）
function levelOf(node) {
  if (!node || node.masteryScore == null) return 0
  const score = node.masteryScore
  if (score >= 75) return 2
  if (score >= 40) return 1
  return 0
}

// 级别颜色
function levelColor(level) {
  return level === 2 ? 'var(--ok)' : level === 1 ? 'var(--warn)' : 'var(--dim)'
}

// 百分比格式
function pct(score) {
  return score == null ? '未测' : Math.round(score) + '%'
}

// 掌握度文案
function masteryText(node) {
  const level = levelOf(node)
  const label = level === 2 ? '已掌握' : level === 1 ? '薄弱' : '未学'
  return node.masteryScore == null ? label : `${label} · ${Math.round(node.masteryScore)}`
}

// 向伴侣提问
function askCompanion() {
  router.push(`/companion?nodeCode=${nodeCode.value}`)
}

// 跳转到另一个知识点
function goNode(code) {
  router.push(`/knowledge?nodeCode=${code}`)
}

// 加载图谱数据
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await fetchGraph(DEMO_COURSE_ID, DEMO_STUDENT_ID)
    graph.value = data
  } catch (e) {
    error.value = e.message || '未知错误'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  // DEV hook for e2e testing
  if (import.meta.env.DEV) {
    window.__wjKnowledge = { graph, loading, error, node, prereqs, nodeCode, load }
  }
})
</script>

<style scoped>
.wj-knowledge {
  --bg: #FAF7F0; --ink: #2b2b2b; --card: #ffffff; --card2: #f0ece3;
  --line: #e3ddd0; --mut: #8a8276; --acc: #c2683f;
  --ok: #4a9e6d; --warn: #cc8a3c; --dim: #a89f90;
  min-height: 100%; background: var(--bg); color: var(--ink); overflow-y: auto;
}

.kp-center {
  min-height: 60vh; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 14px; color: var(--mut); text-align: center; padding: 40px 24px;
}
.kp-title { font-size: 22px; font-weight: 600; color: var(--ink); }
.kp-sub { font-size: 14px; max-width: 420px; line-height: 1.7; }
.kp-err { color: var(--acc); font-size: 14px; }
.kp-btn-acc {
  height: 42px; padding: 0 26px; background: var(--acc); border: none; border-radius: 9px;
  color: #fffdf8; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit;
  display: inline-flex; align-items: center; text-decoration: none;
}
.kp-btn-link { box-sizing: border-box; }

.kp-wrap { max-width: 860px; margin: 0 auto; padding: 24px 24px 64px; }

/* 面包屑 */
.kp-breadcrumb {
  display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--mut);
  margin-bottom: 20px; flex-wrap: wrap;
}
.kp-link { color: var(--mut); text-decoration: none; }
.kp-link:hover { color: var(--acc); text-decoration: underline; }
.kp-sep { color: var(--line); }
.kp-current { color: var(--mut); }
.kp-node-name { color: var(--ink); font-weight: 500; }

/* 头部卡片 */
.kp-header {
  background: var(--card); border: 1px solid var(--line); border-radius: 12px;
  padding: 24px 28px; margin-bottom: 20px;
}
.kp-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.kp-chapter { font-size: 13px; color: var(--mut); }
.kp-badge {
  display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 12px;
  font-weight: 500; background: rgba(216, 103, 74, 0.16); color: var(--acc);
  border: 1px solid var(--acc);
}
.kp-h1 {
  font-family: 'Noto Serif SC', serif; font-size: 26px; font-weight: 600;
  margin: 0 0 14px; line-height: 1.4;
}
.kp-desc { font-size: 14.5px; color: var(--mut); line-height: 1.8; margin: 0; }

/* 通用卡片 */
.kp-card {
  background: var(--card); border: 1px solid var(--line); border-radius: 12px;
  padding: 22px 28px; margin-bottom: 20px;
}
.kp-card-title {
  font-size: 16px; font-weight: 600; margin: 0 0 16px; color: var(--ink);
}

/* 掌握度卡片 */
.kp-mastery { display: flex; align-items: center; gap: 24px; }
.kp-mastery-left { display: flex; flex-direction: column; gap: 6px; min-width: 80px; }
.kp-score { font-size: 32px; font-weight: 700; color: var(--ink); }
.kp-level { font-size: 14px; font-weight: 500; }
.kp-mastery-bar {
  flex: 1; height: 10px; background: var(--card2); border: 1px solid var(--line);
  border-radius: 99px; overflow: hidden; box-sizing: border-box;
}
.kp-mastery-fill { height: 100%; border-radius: 99px; transition: width 0.4s; }

/* 前置知识点列表 */
.kp-prereqs { display: flex; flex-direction: column; gap: 14px; }
.kp-prereq-item {
  background: var(--card2); border: 1px solid var(--line); border-radius: 10px;
  padding: 14px 18px; cursor: pointer; transition: all 0.2s;
}
.kp-prereq-item:hover { border-color: var(--acc); }
.kp-prereq-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 8px; }
.kp-prereq-name { font-size: 14px; font-weight: 500; color: var(--ink); }
.kp-prereq-mastery { font-size: 12.5px; font-weight: 500; }
.kp-prereq-bar {
  height: 6px; background: #fff; border: 1px solid var(--line); border-radius: 99px;
  overflow: hidden; box-sizing: border-box;
}
.kp-prereq-fill { height: 100%; border-radius: 99px; transition: width 0.4s; }
.kp-empty { margin: 0; font-size: 13.5px; color: var(--mut); line-height: 1.7; }

/* 向伴侣提问按钮 */
.kp-btn-companion {
  width: 100%; height: 50px; background: var(--acc); border: none; border-radius: 10px;
  color: #fffdf8; font-size: 15px; font-weight: 500; cursor: pointer; font-family: inherit;
  display: flex; align-items: center; justify-content: center; gap: 10px;
  transition: opacity 0.2s;
}
.kp-btn-companion:hover { opacity: 0.9; }
.kp-btn-companion svg { flex-shrink: 0; }

@media (max-width: 760px) {
  .kp-h1 { font-size: 22px; }
  .kp-mastery { flex-direction: column; align-items: stretch; }
  .kp-mastery-left { flex-direction: row; align-items: center; justify-content: space-between; }
}
</style>
