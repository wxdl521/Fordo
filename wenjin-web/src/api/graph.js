import { http } from './http.js'

/** 查询某课程图谱（节点 + 边） */
export function fetchGraph(courseId) {
  return http.get(`/graph/${courseId}`)
}
