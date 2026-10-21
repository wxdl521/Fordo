<template>
  <span :style="wrapStyle">
    <input
      :value="from"
      type="date"
      :data-testid="`${testidPrefix}-from`"
      aria-label="起始日期"
      class="wj-admin-field"
      :style="{ ...fieldStyle, width: '138px' }"
      @input="emit('update:from', $event.target.value)"
    />
    <span :style="sepStyle" aria-hidden="true">至</span>
    <input
      :value="to"
      type="date"
      :data-testid="`${testidPrefix}-to`"
      aria-label="结束日期"
      class="wj-admin-field"
      :style="{ ...fieldStyle, width: '138px' }"
      @input="emit('update:to', $event.target.value)"
    />
  </span>
</template>

<script setup>
import { fieldStyle } from './adminStyle.js'

// 日期区间（yyyy-MM-dd，含当日）：v-model:from / v-model:to。
defineProps({
  from: { type: String, default: '' },
  to: { type: String, default: '' },
  testidPrefix: { type: String, required: true }
})
const emit = defineEmits(['update:from', 'update:to'])

const wrapStyle = { display: 'inline-flex', alignItems: 'center', gap: '6px' }
const sepStyle = { fontSize: '12.5px', color: 'var(--mut)' }
</script>
