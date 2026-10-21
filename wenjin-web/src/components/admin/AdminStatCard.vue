<template>
  <div :data-testid="testid" :style="cardStyle">
    <div :style="labelStyle">{{ label }}</div>
    <div :data-testid="testid ? `${testid}-value` : undefined" :style="valueStyle">{{ value }}</div>
    <div v-if="hint" :style="hintStyle">{{ hint }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 数字卡：标签 + 大号数值 + 可选的补充说明。tone 决定数值颜色（失败 / 禁用类用 acc 或 warn 提示）。
const props = defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  hint: { type: String, default: '' },
  tone: { type: String, default: '' }, // '' | 'ok' | 'acc' | 'warn'
  testid: { type: String, default: '' }
})

const toneColor = computed(() => {
  if (props.tone === 'ok') return 'var(--ok)'
  if (props.tone === 'acc') return 'var(--acc)'
  if (props.tone === 'warn') return 'var(--warn)'
  return 'var(--ink)'
})

const cardStyle = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  padding: '14px 16px',
  minWidth: 0,
  transition: 'background-color 0.35s, border-color 0.35s'
}

const labelStyle = {
  fontSize: '12px',
  color: 'var(--mut)',
  marginBottom: '6px'
}

const valueStyle = computed(() => ({
  fontFamily: "'Noto Serif SC', serif",
  fontSize: '26px',
  fontWeight: 600,
  lineHeight: 1.2,
  color: toneColor.value,
  fontVariantNumeric: 'tabular-nums',
  overflowWrap: 'anywhere'
}))

const hintStyle = {
  marginTop: '4px',
  fontSize: '11.5px',
  color: 'var(--mut)'
}
</script>
