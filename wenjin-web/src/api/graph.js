import { http } from './http.js'

/** 查询某课程图谱（节点 + 边）；登录学生的掌握度染色由服务端 principal 自动附加 */
export function fetchGraph(courseId) {
  return http.get(`/graph/${courseId}`)
}
