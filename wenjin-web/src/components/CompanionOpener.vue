<template>
  <div class="cp-opener">
    <template v-if="opener">
      <p v-if="opener.greeting" class="cp-opener-greeting" data-testid="opener-greeting">{{ opener.greeting }}</p>
      <div v-if="actions.length" class="cp-opener-actions">
        <div
          v-for="(action, i) in actions"
          :key="`${action.kind}-${action.nodeCode || i}`"
          class="cp-opener-action"
          data-testid="opener-action"
        >
          <div class="cp-opener-action-main">
            <div class="cp-opener-action-title">{{ action.title }}</div>
            <div v-if="action.reason" class="cp-opener-action-reason">{{ action.reason }}</div>
          </div>
          <button type="button" class="cp-opener-action-btn" data-testid="opener-action-btn" @click="onAction(action)">
            开始
          </button>
        </div>
      </div>
    </template>
    <div v-if="!actions.length" class="cp-starters">
      <button
        v-for="starter in starters"
        :key="starter"
        type="button"
        class="cp-starter"
        @click="emit('send', { text: starter })"
      >
        {{ starter }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** GET /companion/opener 的 data；null/undefined 表示回退到静态 starters */
  opener: { type: Object, default: null },
  starters: { type: Array, default: () => [] }
})
const emit = defineEmits(['send'])

const actions = computed(() => (Array.isArray(props.opener?.actions) ? props.opener.actions : []))

function onAction(action) {
  const cta = action.cta || {}
  const payload = { text: cta.prompt || action.title }
  if (cta.reviewPracticeSessionId) payload.reviewPracticeSessionId = cta.reviewPracticeSessionId
  if (action.nodeCode) payload.nodeCode = action.nodeCode
  emit('send', payload)
}
</script>

<style scoped>
.cp-opener {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}
.cp-opener-greeting {
  margin: 0 0 14px;
  color: var(--ink);
  font-size: 14px;
  text-align: center;
}
.cp-opener-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 14px;
  width: 100%;
  max-width: 520px;
}
.cp-opener-action {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.cp-opener-action-main { flex: 1; min-width: 0; text-align: left; }
.cp-opener-action-title { font-size: 14px; font-weight: 600; color: var(--ink); }
.cp-opener-action-reason { margin-top: 3px; font-size: 12px; color: var(--mut); }
.cp-opener-action-btn {
  flex: none;
  padding: 6px 14px;
  border: 1px solid var(--acc);
  border-radius: 999px;
  background: transparent;
  color: var(--acc);
  font-size: 12.5px;
  cursor: pointer;
  font-family: inherit;
}
.cp-opener-action-btn:hover { background: var(--hover); }
</style>
