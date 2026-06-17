<template>
  <div class="seg" :style="{ padding: '3px', gap: '2px', display: 'flex', alignItems: 'center', border: '1px solid var(--line)', borderRadius: '999px', transition: 'border-color 0.35s' }">
    <button
      @click="setInk"
      :style="segStyle(theme === 'ink')"
    >墨夜</button>
    <button
      @click="setPaper"
      :style="segStyle(theme === 'paper')"
    >暖纸</button>
  </div>
</template>

<script setup>
import { useTheme } from '../composables/useTheme.js'

const props = defineProps({
  small: { type: Boolean, default: false }
})

const { theme, setInk, setPaper } = useTheme()

function segStyle(active) {
  return {
    height: props.small ? '24px' : '26px',
    padding: props.small ? '0 11px' : '0 12px',
    border: 'none',
    borderRadius: '999px',
    fontSize: props.small ? '11.5px' : '12px',
    cursor: 'pointer',
    background: active ? 'var(--card2)' : 'transparent',
    color: active ? 'var(--ink)' : 'var(--mut)',
    transition: 'background-color 0.25s, color 0.25s'
  }
}
</script>
