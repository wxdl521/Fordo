import { ref } from 'vue'

// 全局主题（暖纸 paper / 墨夜 ink），持久化到 localStorage('wj_theme')
const STORE_KEY = 'wj_theme'
export const theme = ref('paper')

function apply(value) {
  theme.value = value
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('data-theme', value)
  }
  try {
    localStorage.setItem(STORE_KEY, value)
  } catch (e) {}
}

export function initTheme() {
  let saved = 'paper'
  try {
    saved = localStorage.getItem(STORE_KEY) || 'paper'
  } catch (e) {}
  apply(saved)
}

export function useTheme() {
  return {
    theme,
    setTheme: apply,
    setInk: () => apply('ink'),
    setPaper: () => apply('paper')
  }
}
