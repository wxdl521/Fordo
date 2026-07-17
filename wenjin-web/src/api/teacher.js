import { http } from './http.js'

// ── 图谱审核 ──
export function fetchTeacherGraph(courseId, config = {}) {
  return http.get('/teacher/graph', {
    ...config,
    params: { ...config.params, courseId }
  })
}
export function fetchPendingEdges(courseId, config = {}) {
  return http.get('/teacher/graph/pending-edges', {
    ...config,
    params: { ...config.params, courseId }
  })
}
export function acceptEdge(id) {
  return http.post(`/teacher/graph/edges/${id}/accept`)
}
export function rejectEdge(id) {
  return http.post(`/teacher/graph/edges/${id}/reject`)
}
/** 创建边：body { sourceNodeCode, targetNodeCode, relationType 1..4, relationNote? } */
export function createEdge(courseId, req) {
  return http.post('/teacher/graph/edges', req, { params: { courseId } })
}
export function createNode(courseId, req) {
  return http.post('/teacher/graph/nodes', req, { params: { courseId } })
}
export function updateNode(id, req) {
  return http.put(`/teacher/graph/nodes/${id}`, req)
}
export function deleteNode(id) {
  return http.delete(`/teacher/graph/nodes/${id}`)
}

// ── 题目审核池 ──
export function fetchQuestions({ courseId, status, nodeCode, conf, page, size }, config = {}) {
  return http.get('/teacher/questions', {
    ...config,
    params: { ...config.params, courseId, status, nodeCode, conf, page, size }
  })
}
export function reviewQuestions(courseId, ids, action) {
  return http.post('/teacher/questions/review', { ids, action }, { params: { courseId } })
}
/** T6: 服务端全量审批 — 单次请求替代前端"拉全量id→分块回传"方案 */
export function reviewAllQuestions(courseId, { status, conf, nodeCode, action }) {
  return http.post('/teacher/questions/review-all', { status, conf, nodeCode, action }, { params: { courseId } })
}

// ── 学情看板 ──
export function fetchDashboard(courseId, config = {}) {
  return http.get('/teacher/dashboard', {
    ...config,
    params: { ...config.params, courseId }
  })
}

// ── Agent 轨迹（教师端）──
export function fetchAgentRuns({ courseId, studentId, page, size }, config = {}) {
  return http.get('/teacher/agent-runs', {
    ...config,
    params: { ...config.params, courseId, studentId, page, size }
  })
}
export function fetchAgentRunSteps(runId, config = {}) {
  return http.get(`/teacher/agent-runs/${runId}/steps`, config)
}

// ── 课程管理（多课程切换）──
export function fetchTeacherCourses(config = {}) {
  return http.get('/teacher/courses', config)
}
export function createTeacherCourse(name) {
  return http.post('/teacher/courses', { name })
}
export function deleteTeacherCourse(id) {
  return http.delete(`/teacher/courses/${id}`)
}
export function setTeacherCourseStatus(id, published) {
  return http.patch(`/teacher/courses/${id}/status`, { published })
}
