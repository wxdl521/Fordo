<template>
  <div v-if="total > 0" :data-testid="`${testidPrefix}-pagination`" :style="paginationStyle">
    <span :data-testid="`${testidPrefix}-total`" :style="pageInfoStyle">共 {{ total }} 条</span>
    <button
      type="button"
      :data-testid="`${testidPrefix}-prev`"
      class="wj-admin-btn"
      :style="pageBtnStyle(page <= 1 || loading)"
      :disabled="page <= 1 || loading"
      @click="emit('change', page - 1)"
    >上一页</button>
    <span :data-testid="`${testidPrefix}-page-info`" :style="pageInfoStyle">第 {{ page }} / {{ totalPages }} 页</span>
    <button
      type="button"
      :data-testid="`${testidPrefix}-next`"
      class="wj-admin-btn"
      :style="pageBtnStyle(page >= totalPages || loading)"
      :disabled="page >= totalPages || loading"
      @click="emit('change', page + 1)"
    >下一页</button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { pageBtnStyle, pageInfoStyle, paginationStyle } from './adminStyle.js'

// 列表分页条：data-testid 为 `${testidPrefix}-pagination / -total / -prev / -page-info / -next`。
const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  loading: { type: Boolean, default: false },
  testidPrefix: { type: String, required: true }
})
const emit = defineEmits(['change'])

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
</script>
