<template>
  <div :style="headingRowStyle">
    <div :style="{ minWidth: 0 }">
      <h1 :style="h1Style">{{ title }}</h1>
      <div v-if="subtitle" :style="subStyle">{{ subtitle }}</div>
    </div>
    <div v-if="$slots.actions" :style="headingActionsStyle">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
import { h1Style, headingActionsStyle, headingRowStyle, subStyle } from './adminStyle.js'

// 页面标题行：左侧 h1 + 副标题，右侧 actions 插槽放主操作按钮。
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' }
})
</script>
