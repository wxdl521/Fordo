<template>
  <div v-if="visible.length" class="cp-hintbar" role="status">
    <div v-for="hint in visible" :key="hint.hintKey" class="cp-hint" data-testid="companion-hint">
      <div class="cp-hint-main">
        <span class="cp-hint-title">{{ hint.title }}</span>
        <span v-if="hint.reason" class="cp-hint-reason">{{ hint.reason }}</span>
      </div>
      <button
        v-if="hint.cta && hint.cta.prompt"
        type="button"
        class="cp-hint-go"
        data-testid="companion-hint-go"
        @click="onGo(hint)"
      >去看看</button>
      <button
        type="button"
        class="cp-hint-close"
        data-testid="companion-hint-close"
        title="关闭提示"
        aria-label="关闭提示"
        @click="onDismiss(hint)"
      >×</button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { dismissHint } from '../api/companion.js'

const props = defineProps({
  hints: { type: Array, default: () => [] },
  courseId: { type: [Number, String], default: null }
})
const emit = defineEmits(['send'])

const dismissed = ref(new Set())
watch(() => props.courseId, () => { dismissed.value = new Set() })

const visible = computed(() =>
  (props.hints || []).filter((h) => h && h.hintKey && !dismissed.value.has(h.hintKey))
)

function onGo(hint) {
  const payload = { text: hint.cta.prompt }
  if (hint.cta.reviewPracticeSessionId) payload.reviewPracticeSessionId = hint.cta.reviewPracticeSessionId
  emit('send', payload)
}

async function onDismiss(hint) {
  dismissed.value = new Set([...dismissed.value, hint.hintKey])
  try {
    await dismissHint(props.courseId, hint.hintKey)
  } catch {
    // 静默：提示已在本地隐藏，下次进入可能再出现
  }
}
</script>

<style scoped>
.cp-hintbar {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 24px 0;
}
.cp-hint {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--acc);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--ink);
}
.cp-hint-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.cp-hint-title { font-weight: 600; }
.cp-hint-reason { color: var(--mut); }
.cp-hint-go, .cp-hint-close {
  flex: none;
  background: transparent;
  border: none;
  cursor: pointer;
  font-family: inherit;
}
.cp-hint-go { color: var(--acc); font-size: 12.5px; }
.cp-hint-close { color: var(--mut); font-size: 16px; line-height: 1; }
</style>
