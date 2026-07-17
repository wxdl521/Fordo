/**
 * 只保留「最新一次」异步请求：每次 next() 提升 revision 并 abort 上一次。
 * 用于课程切换时丢弃迟到响应，避免旧图谱覆盖新课数据。
 */
export function createLatestRequest() {
  let revision = 0
  /** @type {AbortController | null} */
  let controller = null

  /**
   * 开始新一轮请求：取消上一轮，返回本轮 signal 与 isCurrent 判定。
   * @returns {{ signal: AbortSignal, revision: number, isCurrent: () => boolean }}
   */
  function next() {
    revision += 1
    if (controller) {
      try {
        controller.abort()
      } catch {
        /* ignore */
      }
    }
    controller = new AbortController()
    const rev = revision
    const signal = controller.signal
    return {
      signal,
      revision: rev,
      isCurrent: () => rev === revision
    }
  }

  /** 取消进行中的请求并作废当前 revision（如组件卸载）。 */
  function cancel() {
    revision += 1
    if (controller) {
      try {
        controller.abort()
      } catch {
        /* ignore */
      }
      controller = null
    }
  }

  return {
    next,
    cancel,
    get revision() {
      return revision
    }
  }
}
