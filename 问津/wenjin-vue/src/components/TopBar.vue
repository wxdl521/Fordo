<template>
  <div
    :style="{
      height: compact ? '56px' : '60px',
      flex: 'none',
      display: 'flex',
      alignItems: 'center',
      gap: '14px',
      padding: compact ? '0 20px' : '0 24px',
      borderBottom: '1px solid var(--line)',
      transition: 'border-color 0.35s'
    }"
  >
    <span :style="{ fontFamily: serif, fontSize: compact ? '20px' : '22px', fontWeight: 600, letterSpacing: '3px', whiteSpace: 'nowrap', flex: 'none' }">问津</span>
    <span
      v-if="teacher"
      :style="{ fontSize: '11px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '2px 9px', flex: 'none' }"
    >教师端</span>
    <div :style="{ width: '1px', height: compact ? '16px' : '18px', background: 'var(--line)', flex: 'none' }"></div>
    <span :style="{ fontSize: compact ? '13.5px' : '14px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none', display: subtitleHidden ? 'none' : 'inline' }">{{ subtitle }}</span>
    <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '10px' }">
      <slot />
      <ThemeToggle :small="compact" />
    </div>
  </div>
</template>

<script setup>
import ThemeToggle from './ThemeToggle.vue'

const serif = "'Noto Serif SC', serif"

defineProps({
  subtitle: { type: String, default: '' },
  teacher: { type: Boolean, default: false },
  compact: { type: Boolean, default: false },
  subtitleHidden: { type: Boolean, default: false }
})
</script>
