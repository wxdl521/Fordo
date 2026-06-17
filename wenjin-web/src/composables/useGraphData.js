import { ref } from 'vue'
import { fetchGraph } from '../api/graph.js'

// 单例图谱数据加载 composable，各页面共享。
// 从后端 /api/graph/{courseId} 加载数据并转换为设计稿格式。

/** 后端节点 -> 设计稿节点 */
function mapNode(n) {
  return {
    id: n.nodeCode,
    name: n.name,
    chapter: n.chapter || '',
    difficulty: n.difficulty ?? 0,
    is_key: !!n.isKey,
    bloom: n.bloom || '',   // 后端暂无 bloom 字段，留空占位
    description: n.description || ''
  }
}

/** 后端边 -> 设计稿边（格式基本一致，直接透传） */
function mapEdge(e) {
  return { source: e.source, target: e.target, type: e.type }
}

const data = ref(null)
let loading = null

/**
 * 加载图谱数据（单例，首次调用发请求，后续复用）。
 * @param {number} courseId
 * @param {number} [studentId]
 * @returns {{ data: import('vue').Ref<{ nodes: Array, edges: Array } | null>, ready: () => boolean }}
 */
export function useGraphData(courseId = 1, studentId) {
  if (!data.value && !loading) {
    loading = fetchGraph(courseId, studentId)
      .then((res) => {
        // http 拦截器已拆 Result 信封，res 就是 GraphDataVO
        data.value = {
          nodes: (res.nodes || []).map(mapNode),
          edges: (res.edges || []).map(mapEdge)
        }
        return data.value
      })
      .catch((err) => {
        console.error('[useGraphData] 加载失败', err)
        data.value = { nodes: [], edges: [] }
      })
  }
  return { data, ready: () => !!data.value }
}
