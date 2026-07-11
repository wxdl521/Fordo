import { ref, watch, onUnmounted, unref } from 'vue'
import { fetchGraph } from '../api/graph.js'
import { createLatestRequest } from '../utils/latestRequest.js'

/** 后端节点 -> 设计稿节点 */
export function mapNode(n) {
  return {
    id: n.nodeCode,
    name: n.name,
    chapter: n.chapter || '',
    difficulty: n.difficulty ?? 0,
    is_key: !!n.isKey,
    bloom: n.bloom || '',
    description: n.description || '',
    mastery: n.mastery || 'unlearned',
    masteryScore: n.masteryScore != null ? Math.round(n.masteryScore) : null,
    rawScore: n.rawScore != null ? Math.round(n.rawScore) : (n.masteryScore != null ? Math.round(n.masteryScore) : null),
    effectiveScore: n.effectiveScore != null ? Math.round(n.effectiveScore) : null,
    effectiveLevel: n.effectiveLevel ?? null,
    daysSinceEvidence: n.daysSinceEvidence ?? null,
    decaying: n.decaying === true
  }
}

/** 后端边 -> 设计稿边 */
export function mapEdge(e) {
  return { source: e.source, target: e.target, type: e.type }
}

function isAbortError(err) {
  if (!err) return false
  return (
    err.name === 'CanceledError' ||
    err.name === 'AbortError' ||
    err.code === 'ERR_CANCELED' ||
    err.message === 'canceled'
  )
}

/**
 * 实例级图谱数据加载（非单例）。
 * 使用 createLatestRequest：课程切换时 abort/忽略迟到响应；卸载时 cancel。
 *
 * @param {import('vue').Ref<number|null|undefined>|import('vue').ComputedRef<number|null|undefined>|number|null|undefined} courseIdSource
 * @param {{ immediate?: boolean }} [options]
 * @returns {{
 *   data: import('vue').Ref<{ nodes: Array, edges: Array } | null>,
 *   loading: import('vue').Ref<boolean>,
 *   error: import('vue').Ref<string>,
 *   reload: () => Promise<void>,
 *   ready: () => boolean
 * }}
 */
export function useGraphData(courseIdSource, options = {}) {
  const { immediate = true } = options
  const data = ref(null)
  const loading = ref(false)
  const error = ref('')
  const latest = createLatestRequest()

  async function load() {
    const courseId = unref(courseIdSource)
    const id = courseId != null && Number(courseId) > 0 ? Number(courseId) : null

    if (id == null) {
      latest.cancel()
      data.value = null
      loading.value = false
      error.value = ''
      return
    }

    const { signal, isCurrent } = latest.next()
    loading.value = true
    error.value = ''

    try {
      const res = await fetchGraph(id, { signal })
      if (!isCurrent()) return
      data.value = {
        nodes: (res?.nodes || []).map(mapNode),
        edges: (res?.edges || []).map(mapEdge)
      }
    } catch (err) {
      if (!isCurrent() || isAbortError(err)) return
      console.error('[useGraphData] 加载失败', err)
      data.value = { nodes: [], edges: [] }
      error.value = err?.message || '加载失败'
    } finally {
      if (isCurrent()) loading.value = false
    }
  }

  watch(
    () => {
      const v = unref(courseIdSource)
      return v != null && Number(v) > 0 ? Number(v) : null
    },
    () => {
      load()
    },
    { immediate }
  )

  onUnmounted(() => {
    latest.cancel()
  })

  return {
    data,
    loading,
    error,
    reload: load,
    ready: () => !!data.value
  }
}
