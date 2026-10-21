<template>
  <div :style="tableCardStyle">
    <div :style="tableScrollStyle">
      <slot />
    </div>
    <div v-if="loading" :data-testid="`${testidPrefix}-loading`" :style="hintStyle">加载中…</div>
    <div v-else-if="empty" :data-testid="`${testidPrefix}-empty`" :style="hintStyle">{{ emptyText }}</div>
  </div>
</template>

<script setup>
import { hintStyle, tableCardStyle, tableScrollStyle } from './adminStyle.js'

// 表格卡片：圆角描边容器 + 横向滚动 + 加载中 / 空态提示。
// 表格本身（<table>）放默认插槽；窄屏由 table 自带的 minWidth 触发横向滚动。
defineProps({
  loading: { type: Boolean, default: false },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '没有符合条件的记录。' },
  testidPrefix: { type: String, required: true }
})
</script>
