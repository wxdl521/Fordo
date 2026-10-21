<template>
  <div :style="overlayStyle" data-testid="admin-modal-overlay">
    <div
      ref="dialogRef"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="titleId"
      :data-testid="testid || undefined"
      :style="dialogStyle"
    >
      <div :style="headerStyle">
        <h2 :id="titleId" :style="titleStyle">{{ title }}</h2>
        <button
          type="button"
          aria-label="关闭"
          data-testid="admin-modal-close"
          class="wj-admin-modal-x"
          :style="closeBtnStyle"
          @click="emit('close')"
        >×</button>
      </div>
      <div :style="bodyStyle">
        <slot />
      </div>
      <div v-if="$slots.footer" :style="footerStyle">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<script>
// 跨实例自增 id，用于 aria-labelledby（普通 <script> 块在模块级只执行一次）
let modalSeq = 0
</script>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  testid: { type: String, default: '' },
  width: { type: String, default: '460px' }
})
const emit = defineEmits(['close'])

const dialogRef = ref(null)
const titleId = `wj-admin-modal-title-${++modalSeq}`
let previouslyFocused = null

const FOCUSABLE = 'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'

function focusables() {
  const root = dialogRef.value
  return root ? Array.from(root.querySelectorAll(FOCUSABLE)) : []
}

// 简易焦点陷阱：Tab / Shift+Tab 在弹窗内循环（焦点已丢到弹窗外/页面上时也拉回来）
function trapTab(event) {
  const items = focusables()
  if (items.length === 0) return
  const first = items[0]
  const last = items[items.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || !dialogRef.value.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || !dialogRef.value.contains(active))) {
    event.preventDefault()
    first.focus()
  }
}

// 监听挂在 document 上：弹窗里正聚焦的按钮可能因 :disabled（提交中 / 下载中）而失焦，
// 挂在弹窗元素上的 Esc / Tab 会因此失效。
function onDocumentKeydown(event) {
  if (event.isComposing) return
  if (event.key === 'Escape') {
    emit('close')
  } else if (event.key === 'Tab') {
    trapTab(event)
  }
}

onMounted(() => {
  document.addEventListener('keydown', onDocumentKeydown)
  previouslyFocused = document.activeElement
  // 先聚焦第一个表单控件（跳过右上角关闭按钮），没有则聚焦弹窗本身
  const body = dialogRef.value
  const firstField = body && body.querySelector('input:not([disabled]), select:not([disabled]), textarea:not([disabled])')
  if (firstField) firstField.focus()
  else if (body) {
    body.setAttribute('tabindex', '-1')
    body.focus()
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDocumentKeydown)
  if (previouslyFocused && typeof previouslyFocused.focus === 'function' && document.contains(previouslyFocused)) {
    previouslyFocused.focus()
  }
})

const overlayStyle = {
  position: 'fixed',
  inset: 0,
  zIndex: 1000,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: '16px',
  background: 'rgba(0, 0, 0, 0.45)'
}

const dialogStyle = {
  width: props.width,
  maxWidth: '100%',
  maxHeight: '100%',
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--card)',
  color: 'var(--ink)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  boxShadow: '0 18px 48px rgba(0, 0, 0, 0.28)',
  overflow: 'hidden'
}

const headerStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '16px 20px 12px',
  borderBottom: '1px solid var(--line)'
}

const titleStyle = {
  margin: 0,
  fontFamily: "'Noto Serif SC', serif",
  fontSize: '17px',
  fontWeight: 600
}

const closeBtnStyle = {
  width: '28px',
  height: '28px',
  border: 'none',
  borderRadius: '6px',
  background: 'transparent',
  color: 'var(--mut)',
  fontSize: '20px',
  lineHeight: 1,
  cursor: 'pointer'
}

const bodyStyle = {
  padding: '16px 20px',
  overflowY: 'auto',
  minHeight: 0
}

const footerStyle = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: '10px',
  padding: '12px 20px 16px',
  borderTop: '1px solid var(--line)'
}
</script>

<style scoped>
.wj-admin-modal-x:hover {
  background: var(--card2) !important;
  color: var(--ink) !important;
}
</style>
