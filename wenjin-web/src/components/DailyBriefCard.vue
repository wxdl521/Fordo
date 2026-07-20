<template>
  <div v-if="visible" class="brief-card" :class="{ alert: brief?.backlogAlert || brief?.atRisk }">
    <div class="brief-head">
      <span class="brief-title">今日简报</span>
      <button type="button" class="brief-close" title="关闭" aria-label="关闭简报" @click="dismissed = true">×</button>
    </div>
    <div v-if="loading" class="brief-muted">加载中…</div>
    <div v-else-if="error" class="brief-err">{{ error }}</div>
    <template v-else-if="brief">
      <div class="brief-row">
        <span class="label">今日任务</span>
        <strong>{{ taskCount }}</strong>
      </div>
      <div class="brief-row" :class="{ warn: brief.backlogAlert }">
        <span class="label">复习积压</span>
        <strong>{{ brief.reviewBacklogCount ?? 0 }}</strong>
        <span v-if="brief.backlogAlert" class="tag">需优先</span>
      </div>
      <div v-if="weakDrops.length" class="brief-row">
        <span class="label">薄弱下滑</span>
        <span class="weak-list">
          <span v-for="(w, i) in weakDrops.slice(0, 3)" :key="w.nodeCode || i" class="chip">
            {{ w.name || w.nodeCode }}
          </span>
          <span v-if="weakDrops.length > 3" class="brief-muted">+{{ weakDrops.length - 3 }}</span>
        </span>
      </div>
      <div class="brief-links">
        <router-link class="link" to="/path">学习路径</router-link>
        <router-link class="link" to="/companion">AI 伴侣</router-link>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { fetchDailyBrief } from '../api/student.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const props = defineProps({
  courseId: { type: [Number, String], default: null },
  enabled: { type: Boolean, default: true }
})

const brief = ref(null)
const loading = ref(false)
const error = ref('')
const dismissed = ref(false)
const requestGate = createLatestRequest()
let disposed = false

const visible = computed(() => props.enabled && props.courseId && !dismissed.value)
const taskCount = computed(() => {
  const t = brief.value?.todayPlanTasks
  return Array.isArray(t) ? t.length : 0
})
const weakDrops = computed(() => {
  const w = brief.value?.weakDrops
  return Array.isArray(w) ? w : []
})

function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

async function load() {
  if (!props.enabled || !props.courseId) {
    brief.value = null
    return
  }
  loading.value = true
  error.value = ''
  const request = requestGate.next()
  try {
    const data = await fetchDailyBrief(props.courseId, { signal: request.signal })
    if (!request.isCurrent() || disposed) return
    brief.value = data || null
  } catch (e) {
    if (isAbortError(e) || !request.isCurrent() || disposed) return
    // 404 / feature off：静默隐藏
    if (e?.httpStatus === 404 || e?.code === 404) {
      brief.value = null
      dismissed.value = true
      return
    }
    error.value = e?.message || '简报加载失败'
    brief.value = null
  } finally {
    if (request.isCurrent() && !disposed) loading.value = false
  }
}

watch(() => [props.courseId, props.enabled], () => {
  dismissed.value = false
  load()
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  requestGate.cancel()
})
</script>

<style scoped>
.brief-card {
  position: absolute;
  left: 20px;
  top: 20px;
  width: min(280px, calc(100% - 40px));
  z-index: 18;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  font-size: 12.5px;
  color: var(--ink);
}
.brief-card.alert {
  border-color: color-mix(in srgb, var(--warn) 55%, var(--line));
  box-shadow: 0 8px 24px color-mix(in srgb, var(--warn) 12%, transparent);
}
.brief-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.brief-title {
  font-size: 11px;
  letter-spacing: 1.5px;
  color: var(--mut);
  font-weight: 600;
}
.brief-close {
  border: none;
  background: transparent;
  color: var(--mut);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
}
.brief-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.brief-row .label { color: var(--mut); min-width: 56px; }
.brief-row.warn strong { color: var(--warn); }
.tag {
  font-size: 10.5px;
  color: var(--warn);
  background: var(--warnSoft);
  border-radius: 999px;
  padding: 1px 7px;
}
.weak-list { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.chip {
  font-size: 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 7px;
  color: var(--ink);
}
.brief-links {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}
.link {
  color: var(--acc);
  text-decoration: none;
  font-weight: 500;
}
.link:hover { text-decoration: underline; }
.brief-muted { color: var(--mut); font-size: 12px; }
.brief-err { color: #c44; font-size: 12px; }
</style>
