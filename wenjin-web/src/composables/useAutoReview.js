import { ref, watch } from 'vue'

/**
 * ?review=<practiceSessionId> 自动错题复盘：每个 id 恰好发送一次。
 * 读到参数即记下并清掉 URL（防刷新重发）；若此刻不空闲（courseId 未就绪 / 正在输出 / 正在加载会话），
 * 不丢弃请求，而是等 ready() 变为 true 再发。
 *
 * @param {object} opts
 * @param {() => unknown} opts.reviewQuery 读取 route.query.review
 * @param {() => boolean} opts.ready 是否可以立即发送
 * @param {(id: number) => void} [opts.clearQuery] 清掉 URL 参数
 * @param {(id: number) => void} opts.send 发起复盘
 */
export function useAutoReview({ reviewQuery, ready, clearQuery, send }) {
  const handled = new Set()
  const pending = ref(null)

  watch(reviewQuery, (raw) => {
    const id = Number(Array.isArray(raw) ? raw[0] : raw)
    if (!Number.isInteger(id) || id <= 0 || handled.has(id)) return
    handled.add(id)
    pending.value = id
    clearQuery?.(id)
  }, { immediate: true })

  watch([pending, ready], ([id, ok]) => {
    if (id == null || !ok) return
    pending.value = null
    send(id)
  }, { immediate: true })

  return { pending }
}
