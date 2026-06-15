import { http } from './http.js'

/** 查询某课程图谱（节点 + 边）；传 studentId 时附带学情染色 */
export function fetchGraph(courseId, studentId) {
  const params = (studentId === undefined || studentId === null) ? {} : { studentId }
  return http.get(`/graph/${courseId}`, { params })
}
