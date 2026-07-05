<template>
  <div class="wj-review-panel">
    <div class="wj-review-head">
      <div class="wj-review-title">
        <span class="wj-review-dot"></span>
        今日复习
        <span class="wj-review-count">{{ displayCount }} 项</span>
        <span v-if="truncatedHint" class="wj-review-hint">{{ truncatedHint }}</span>
      </div>
      <div class="wj-review-actions">
        <button type="button" class="wj-review-refresh" :disabled="loading" @click="$emit('refresh')">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button type="button" class="wj-review-close" @click="$emit('close')">×</button>
      </div>
    </div>

    <div v-if="error" class="wj-review-error">
      {{ error }}
      <button type="button" class="wj-review-retry" @click="$emit('refresh')">重试</button>
    </div>

    <div v-else-if="loading && !items.length" class="wj-review-empty">加载复习队列…</div>

    <div v-else-if="!items.length" class="wj-review-empty">
      暂无需要复习的知识点，继续保持！
    </div>

    <div v-else class="wj-review-list">
      <div v-for="item in items" :key="item.nodeId" class="wj-review-item">
        <div class="wj-review-item-main">
          <div class="wj-review-item-name">{{ item.name }}</div>
          <div class="wj-review-item-meta">
            <span v-if="item.chapter">{{ item.chapter }}</span>
            <span v-if="item.chapter && item.nodeCode"> · </span>
            <span v-if="item.nodeCode">{{ item.nodeCode }}</span>
          </div>
          <div class="wj-review-scores">
            <span class="wj-review-score-raw">{{ fmtScore(item.rawScore) }}</span>
            <span class="wj-review-score-arr">→</span>
            <span class="wj-review-score-eff" :style="{ color: levelColor(item.effectiveLevel) }">
              {{ fmtScore(item.effectiveScore) }}
            </span>
            <span class="wj-review-level" :style="{ color: levelColor(item.effectiveLevel) }">
              {{ levelLabel(item.effectiveLevel) }}
            </span>
          </div>
          <div class="wj-review-days">上次证据 {{ fmtDays(item.daysSinceEvidence) }}</div>
        </div>
        <div class="wj-review-item-foot">
          <span v-if="!item.availableQuestions" class="wj-review-no-q">暂无练习题</span>
          <button
            type="button"
            class="wj-review-btn"
            :disabled="!item.availableQuestions"
            @click="goPractice(item)"
          >复习</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  items: { type: Array, default: () => [] },
  totalDecayingCount: { type: Number, default: 0 },
  courseId: { type: Number, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

defineEmits(['refresh', 'close'])

const router = useRouter()

const displayCount = computed(() => {
  const total = props.totalDecayingCount
  if (total > 0) return total
  return props.items.length
})

const truncatedHint = computed(() => {
  const total = props.totalDecayingCount
  if (total > props.items.length && props.items.length > 0) {
    return `（展示前 ${props.items.length} 项）`
  }
  return ''
})

function fmtScore(v) {
  return v == null ? '—' : `${Math.round(v)}%`
}

function levelLabel(level) {
  if (level === 2) return '已掌握'
  if (level === 1) return '薄弱'
  return '未学'
}

function levelColor(level) {
  if (level === 2) return 'var(--ok, #3D7A5E)'
  if (level === 1) return 'var(--warn, #C8862A)'
  return 'var(--mut)'
}

function fmtDays(d) {
  if (d == null) return '—'
  if (d < 1) return '不足 1 天'
  if (d < 2) return '约 1 天前'
  return `约 ${Math.round(d)} 天前`
}

function goPractice(item) {
  if (!item.availableQuestions) return
  const q = { nodeId: item.nodeId }
  if (item.nodeCode) q.nodeCode = item.nodeCode
  if (props.courseId) q.courseId = props.courseId
  router.push({ path: '/practice', query: q })
}
</script>

<style scoped>
.wj-review-panel {
  flex: none;
  border-bottom: 1px solid var(--line);
  background: var(--card);
  transition: background-color 0.35s, border-color 0.35s;
}

.wj-review-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--line);
}

.wj-review-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
}

.wj-review-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--acc);
  flex: none;
}

.wj-review-count {
  color: var(--acc);
}

.wj-review-hint {
  font-size: 11.5px;
  color: var(--mut);
  font-weight: 400;
}

.wj-review-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
}

.wj-review-refresh,
.wj-review-close {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: transparent;
  color: var(--mut);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: background-color 0.2s, border-color 0.2s;
}

.wj-review-close {
  width: 28px;
  padding: 0;
  font-size: 16px;
  line-height: 1;
}

.wj-review-refresh:hover:not(:disabled),
.wj-review-close:hover {
  background: var(--card2, var(--line));
  color: var(--ink);
}

.wj-review-refresh:disabled {
  opacity: 0.6;
  cursor: default;
}

.wj-review-error,
.wj-review-empty {
  padding: 16px 20px;
  font-size: 13px;
  color: var(--mut);
}

.wj-review-error {
  color: var(--acc);
  display: flex;
  align-items: center;
  gap: 10px;
}

.wj-review-retry {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: transparent;
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}

.wj-review-list {
  max-height: min(280px, 38vh);
  overflow-y: auto;
  padding: 8px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.wj-review-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  transition: border-color 0.2s;
}

.wj-review-item:hover {
  border-color: var(--mut);
}

.wj-review-item-main {
  flex: 1;
  min-width: 0;
}

.wj-review-item-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.4;
}

.wj-review-item-meta {
  font-size: 11.5px;
  color: var(--mut);
  margin-top: 2px;
}

.wj-review-scores {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12.5px;
}

.wj-review-score-raw {
  color: var(--mut);
  text-decoration: line-through;
  text-decoration-color: var(--mut);
}

.wj-review-score-arr {
  color: var(--mut);
}

.wj-review-score-eff {
  font-weight: 600;
}

.wj-review-level {
  font-size: 11px;
  margin-left: 4px;
}

.wj-review-days {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--mut);
}

.wj-review-item-foot {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.wj-review-no-q {
  font-size: 11px;
  color: var(--mut);
  white-space: nowrap;
}

.wj-review-btn {
  height: 34px;
  padding: 0 16px;
  background: var(--acc);
  border: none;
  border-radius: 8px;
  color: #fffdf8;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  transition: opacity 0.15s;
}

.wj-review-btn:hover:not(:disabled) {
  opacity: 0.88;
}

.wj-review-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

@media (max-width: 640px) {
  .wj-review-item {
    flex-direction: column;
    align-items: stretch;
  }

  .wj-review-item-foot {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
  }
}
</style>