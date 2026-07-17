import { defineStore } from 'pinia'
import { fetchGraph } from '../api/graph'
import { createLatestRequest } from '../utils/latestRequest.js'

/** store 级 latest gate：课程切换时忽略迟到响应 */
const latest = createLatestRequest()

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
 * 图谱数据 store。加载接口数据，并提供章节列表、前置点查询等派生数据。
 */
export const useGraphStore = defineStore('graph', {
  state: () => ({
    course: null,
    nodes: [],
    edges: [],
    loading: false,
    error: '',
    /** 当前已成功加载的 courseId，便于调用方判断 */
    loadedCourseId: /** @type {number | null} */ (null)
  }),
  getters: {
    /** 业务编码 -> 节点 */
    nodeMap(state) {
      const m = new Map()
      state.nodes.forEach((n) => m.set(n.nodeCode, n))
      return m
    },
    /** 去重后的章节列表（保持出现顺序） */
    chapters(state) {
      const seen = []
      state.nodes.forEach((n) => {
        if (n.chapter && !seen.includes(n.chapter)) seen.push(n.chapter)
      })
      return seen
    }
  },
  actions: {
    /** 加载某课程图谱（登录学生的掌握度染色由服务端自动附加） */
    async load(courseId, config = {}) {
      const id = courseId != null && Number(courseId) > 0 ? Number(courseId) : null
      if (id == null) {
        latest.cancel()
        this.course = null
        this.nodes = []
        this.edges = []
        this.loading = false
        this.error = ''
        this.loadedCourseId = null
        return
      }

      const ownerSignal = config?.signal
      if (ownerSignal?.aborted) return

      const { signal, isCurrent } = latest.next()
      if (ownerSignal) {
        ownerSignal.addEventListener('abort', () => {
          // A 的 owner 迟到 abort 时，若 B 已成为 latest，绝不能取消 B。
          if (!isCurrent()) return
          latest.cancel()
          this.loading = false
          this.error = ''
          this.course = null
          this.nodes = []
          this.edges = []
          this.loadedCourseId = null
        }, { once: true })
      }
      this.loading = true
      this.error = ''
      try {
        const data = await fetchGraph(id, { signal })
        if (!isCurrent()) return
        this.course = data.course
        this.nodes = data.nodes || []
        this.edges = data.edges || []
        this.loadedCourseId = id
      } catch (e) {
        if (!isCurrent() || isAbortError(e)) return
        this.error = e.message || '加载失败'
      } finally {
        if (isCurrent()) this.loading = false
      }
    },
    /** 某节点的前置知识点（沿「前置」边逆向取 source） */
    prerequisitesOf(nodeCode) {
      return this.edges
        .filter((e) => e.type === '前置' && e.target === nodeCode)
        .map((e) => this.nodeMap.get(e.source))
        .filter(Boolean)
    }
  }
})
