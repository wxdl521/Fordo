<template>
  <figure class="insight">
    <figcaption class="insight-head">
      <span class="insight-title">{{ card.title }}</span>
      <button type="button" class="copy-btn" data-testid="copy-csv" @click="copyCsv">
        {{ copied ? '已复制' : '复制为 CSV' }}
      </button>
    </figcaption>
    <div class="insight-scroll" tabindex="0" role="region" :aria-label="card.title || '数据表'">
      <table class="insight-table">
        <caption class="sr-only">{{ card.title }}</caption>
        <thead>
          <tr>
            <th v-for="(col, ci) in columns" :key="ci" scope="col">{{ col }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows" :key="ri">
            <td v-for="(cell, ci) in row" :key="ci">{{ cell }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="Math.max(columns.length, 1)" class="muted">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="card.note" class="insight-note">{{ card.note }}</p>
  </figure>
</template>

<script setup>
import { computed, ref } from 'vue'
import { toCsv } from '../utils/csv.js'

const props = defineProps({
  card: { type: Object, required: true }
})

const copied = ref(false)
const columns = computed(() => (Array.isArray(props.card?.columns) ? props.card.columns : []))
const rows = computed(() => (Array.isArray(props.card?.rows) ? props.card.rows : []))

async function copyCsv() {
  const text = toCsv(columns.value, rows.value)
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1500)
  } catch {
    copied.value = false
  }
}
</script>

<style scoped>
.insight { margin: 0; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); max-width: 100%; }
.insight-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--line); }
.insight-title { font-size: 13px; font-weight: 600; }
.copy-btn { font-size: 12px; padding: 3px 10px; border: 1px solid var(--line); border-radius: 6px; background: transparent; color: var(--text); cursor: pointer; }
.insight-scroll { overflow: auto; max-height: 320px; }
.insight-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.insight-table th, .insight-table td { padding: 6px 10px; border-bottom: 1px solid var(--line); text-align: left; white-space: nowrap; }
.insight-table th { position: sticky; top: 0; background: var(--bg); color: var(--mut); font-weight: 500; }
.muted { color: var(--mut); text-align: center; }
.insight-note { margin: 0; padding: 6px 12px; font-size: 12px; color: var(--mut); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
</style>
