import { ref, onMounted, onUnmounted } from 'vue'

// 响应式窗口宽度，用于复刻原型中基于断点的布局切换
export function useViewport() {
  const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1440)
  let handler
  onMounted(() => {
    handler = () => {
      width.value = window.innerWidth
    }
    window.addEventListener('resize', handler)
  })
  onUnmounted(() => {
    if (handler) window.removeEventListener('resize', handler)
  })
  return { width }
}
