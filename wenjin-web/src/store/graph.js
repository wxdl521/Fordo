import { defineStore } from 'pinia'
import { fetchGraph } from '../api/graph'

/**
 * 图谱数据 store。加载接口数据，并提供章节列表、前置点查询等派生数据。
 */
export const useGraphStore = defineStore('graph', {
  state: () => ({
    course: null,
    nodes: [],
    edges: [],
    loading: false,
    error: ''
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
    /** 加载某课程图谱（可带 studentId 染色） */
    async load(courseId, studentId) {
      this.loading = true
      this.error = ''
      try {
        const data = await fetchGraph(courseId, studentId)
        this.course = data.course
        this.nodes = data.nodes || []
        this.edges = data.edges || []
      } catch (e) {
        this.error = e.message || '加载失败'
      } finally {
        this.loading = false
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
