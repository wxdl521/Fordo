<template>
  <teleport to="body">
    <div
      v-if="open && currentStep"
      class="st-root"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="'st-title'"
    >
      <div
        v-if="!hole"
        class="st-mask"
        @click.prevent
      />
      <template v-else>
        <div class="st-bar" :style="{ top: 0, left: 0, right: 0, height: hole.y + 'px' }" @click.prevent />
        <div class="st-bar" :style="{ top: hole.y + 'px', left: 0, width: hole.x + 'px', height: hole.h + 'px' }" @click.prevent />
        <div class="st-bar" :style="{ top: hole.y + 'px', left: (hole.x + hole.w) + 'px', right: 0, height: hole.h + 'px' }" @click.prevent />
        <div class="st-bar" :style="{ top: (hole.y + hole.h) + 'px', left: 0, right: 0, bottom: 0 }" @click.prevent />
        <div class="st-hole" :style="holeStyle">
          <div v-if="!isTeacherTour" class="st-hole-block" @click.prevent />
        </div>
      </template>
      <div
        class="st-card"
        :style="cardStyle"
      >
        <div class="st-kicker">{{ isTeacherTour ? '教师导览' : '学生导览' }} · {{ stepIndex + 1 }}/{{ total }}</div>
        <h2 id="st-title" class="st-title">{{ currentStep.title }}</h2>
        <p class="st-body">{{ currentStep.body }}</p>
        <div class="st-actions">
          <button v-if="isTeacherTour" type="button" class="st-skip" @click="skipAll">跳过</button>
          <button v-if="stepIndex > 0" type="button" class="st-prev" @click="prev">上一步</button>
          <button type="button" class="st-next" @click="next">{{ nextLabel }}</button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useUiTour } from '../onboarding/useUiTour.js'

const {
  open,
  stepIndex,
  currentStep,
  total,
  isTeacherTour,
  next,
  prev,
  skipAll,
  finishMode,
  isLast,
} = useUiTour()

const nextLabel = computed(() => {
  if (isLast.value && finishMode.value === 'diagnostic') return '开始入口诊断'
  return currentStep.value?.next || '下一步'
})

const hole = ref(null)
const viewport = ref({ w: 0, h: 0 })

function measure() {
  viewport.value = {
    w: window.innerWidth,
    h: window.innerHeight,
  }
  const id = currentStep.value?.id
  if (!id) {
    hole.value = null
    return
  }
  const el = document.querySelector(`[data-tour="${id}"]`)
  if (!el) {
    hole.value = null
    return
  }
  const r = el.getBoundingClientRect()
  const pad = 8
  hole.value = {
    x: Math.max(8, r.left - pad),
    y: Math.max(8, r.top - pad),
    w: Math.max(36, r.width + pad * 2),
    h: Math.max(32, r.height + pad * 2),
  }
}

const holeStyle = computed(() => {
  if (!hole.value) return {}
  return {
    left: `${hole.value.x}px`,
    top: `${hole.value.y}px`,
    width: `${hole.value.w}px`,
    height: `${hole.value.h}px`,
  }
})

const cardStyle = computed(() => {
  const vw = viewport.value.w || 360
  const vh = viewport.value.h || 640
  const cardW = Math.min(320, vw - 32)
  let left = 16
  let top = 80
  if (hole.value) {
    const below = hole.value.y + hole.value.h + 14
    const above = hole.value.y - 14
    const preferBelow = below + 200 < vh
    top = preferBelow ? below : Math.max(16, above - 180)
    left = Math.min(Math.max(16, hole.value.x), vw - cardW - 16)
  }
  return {
    width: `${cardW}px`,
    left: `${left}px`,
    top: `${top}px`,
  }
})

let ro
function bind() {
  measure()
  window.addEventListener('resize', measure)
  window.addEventListener('scroll', measure, true)
}

function unbind() {
  window.removeEventListener('resize', measure)
  window.removeEventListener('scroll', measure, true)
}

watch([open, currentStep], async () => {
  if (!open.value) return
  await nextTick()
  measure()
  requestAnimationFrame(measure)
})

onMounted(() => {
  bind()
  ro = new MutationObserver(() => {
    if (open.value) measure()
  })
  ro.observe(document.body, { childList: true, subtree: true, attributes: true })
})
onUnmounted(() => {
  unbind()
  ro?.disconnect()
})
</script>

<style scoped>
.st-root {
  position: fixed;
  inset: 0;
  z-index: 500;
  pointer-events: none;
}
.st-mask {
  position: absolute;
  inset: 0;
  background: rgba(20, 16, 12, 0.46);
  pointer-events: auto;
}
.st-bar {
  position: absolute;
  background: rgba(20, 16, 12, 0.46);
  pointer-events: auto;
}
.st-hole {
  position: absolute;
  border-radius: 10px;
  outline: 2px solid var(--acc);
  pointer-events: none;
  z-index: 1;
  box-sizing: border-box;
}
.st-hole-block {
  position: absolute;
  inset: 0;
  pointer-events: auto;
}
.st-card {
  position: absolute;
  z-index: 2;
  pointer-events: auto;
  background: var(--card);
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px 18px 14px;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.16);
}
.st-kicker {
  font-size: 11px;
  letter-spacing: 1px;
  color: var(--mut);
  margin-bottom: 8px;
}
.st-title {
  margin: 0 0 8px;
  font-family: 'Noto Serif SC', serif;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
}
.st-body {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--mut);
}
.st-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.st-skip,
.st-prev {
  background: transparent;
  border: none;
  color: var(--mut);
  font-size: 12.5px;
  cursor: pointer;
  padding: 6px 4px;
  font-family: inherit;
}
.st-next {
  margin-left: auto;
  height: 36px;
  padding: 0 16px;
  background: var(--acc);
  border: none;
  border-radius: 9px;
  color: #fffdf8;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
}
</style>
