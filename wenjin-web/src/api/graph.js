import { http } from './http.js'

/**
 * 查询某课程图谱（节点 + 边）；登录学生的掌握度染色由服务端 principal 自动附加。
 * @param {number} courseId
 * @param {{ signal?: AbortSignal }} [config] axios 配置（支持 AbortSignal）
 */
export function fetchGraph(courseId, config = {}) {
  return http.get(`/graph/${courseId}`, config)
}
