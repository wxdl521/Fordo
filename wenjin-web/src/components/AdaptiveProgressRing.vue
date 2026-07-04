<template>
  <!-- 三色进度环：实测(--ok 确定) / 推断(--warn 中间) / 未知(--dim 灰)，环心显剩余预算 -->
  <div class="apr">
    <div class="apr-ring" :style="{ width: size + 'px', height: size + 'px' }">
      <svg class="apr-svg" :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
        <!-- 底环 -->
        <circle
          class="apr-track"
          :cx="center" :cy="center" :r="radius"
          fill="none" :stroke-width="stroke"
        />
        <!-- 三色弧段：整体旋转 -90° 从顶部起画、顺时针 -->
        <g :transform="`rotate(-90 ${center} ${center})`">
          <circle
            v-for="seg in segments"
            :key="seg.key"
            :cx="center" :cy="center" :r="radius"
            fill="none"
            :stroke="colorFor(seg.key)"
            :stroke-width="stroke"
            stroke-linecap="butt"
            :stroke-dasharray="`${seg.length} ${circumference - seg.length}`"
            :stroke-dashoffset="-seg.offset"
          />
        </g>
      </svg>
      <!-- 环心：剩余预算 -->
      <div class="apr-center">
        <div class="apr-budget">{{ budgetLeft }}</div>
        <div class="apr-budget-label">剩余预算</div>
      </div>
    </div>

    <!-- 图例 -->
    <div class="apr-legend">
      <span class="apr-leg"><i class="apr-dot apr-dot-tested"></i>实测 {{ tested }}</span>
      <span class="apr-leg"><i class="apr-dot apr-dot-inferred"></i>推断 {{ inferred }}</span>
      <span class="apr-leg"><i class="apr-dot apr-dot-unknown"></i>未知 {{ unknown }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ringSegments } from '../utils/adaptiveProgress.js'

const props = defineProps({
  /** 进度：{ tested, inferred, unknown, budgetLeft }（全数字，budgetLeft 来自后端配置，前端不硬编码） */
  progress: {
    type: Object,
    default: () => ({ tested: 0, inferred: 0, unknown: 0, budgetLeft: 0 })
  },
  /** 环直径（px） */
  size: { type: Number, default: 168 },
  /** 环描边宽度（px） */
  stroke: { type: Number, default: 14 }
})

const center = computed(() => props.size / 2)
// 半径内缩半个描边宽 + 1px 余量，避免描边被 viewBox 裁切
const radius = computed(() => props.size / 2 - props.stroke / 2 - 1)
const circumference = computed(() => 2 * Math.PI * radius.value)

const tested = computed(() => props.progress?.tested || 0)
const inferred = computed(() => props.progress?.inferred || 0)
const unknown = computed(() => props.progress?.unknown || 0)
const budgetLeft = computed(() => props.progress?.budgetLeft ?? 0)

const segments = computed(() => ringSegments(props.progress, circumference.value))

const COLOR = {
  tested: 'var(--ok)',      // 实测=确定色（绿）
  inferred: 'var(--warn)',  // 推断=中间色（琥珀）
  unknown: 'var(--dim)'     // 未知=灰
}
function colorFor(key) {
  return COLOR[key]
}
</script>

<style scoped>
.apr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

/* 环 + 环心叠放 */
.apr-ring {
  position: relative;
  flex: none;
}

.apr-svg {
  display: block;
}

.apr-track {
  stroke: var(--card2);
}

/* 弧段过渡：数值变化时平滑扫过 */
.apr-svg circle {
  transition: stroke-dasharray 0.5s ease, stroke-dashoffset 0.5s ease;
}

.apr-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  pointer-events: none;
}

.apr-budget {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 34px;
  font-weight: 600;
  line-height: 1;
  color: var(--ink);
}

.apr-budget-label {
  font-size: 11.5px;
  color: var(--mut);
}

/* 图例 */
.apr-legend {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: center;
}

.apr-leg {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--mut);
}

.apr-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: none;
}

.apr-dot-tested {
  background: var(--ok);
}
.apr-dot-inferred {
  background: var(--warn);
}
.apr-dot-unknown {
  background: var(--dim);
}
</style>
