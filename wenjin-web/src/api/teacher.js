import { http } from './http.js'

// ── 图谱审核 ──
export function fetchTeacherGraph(courseId) {
  return http.get('/teacher/graph', { params: { courseId } })
}
export function fetchPendingEdges(courseId) {
  return http.get('/teacher/graph/pending-edges', { params: { courseId } })
}
export function acceptEdge(id) {
  return http.post(`/teacher/graph/edges/${id}/accept`)
}
export function rejectEdge(id) {
  return http.post(`/teacher/graph/edges/${id}/reject`)
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
export function fetchQuestions({ courseId, status, nodeCode, conf, page, size }) {
  return http.get('/teacher/questions', { params: { courseId, status, nodeCode, conf, page, size } })
}
export function reviewQuestions(courseId, ids, action) {
  return http.post('/teacher/questions/review', { ids, action }, { params: { courseId } })
}

// ── 学情看板 ──
export function fetchDashboard(courseId) {
  return http.get('/teacher/dashboard', { params: { courseId } })
}

// ── 图谱预览 SVG（AI 生成，长任务）──
export function generateGraphPreviewSvg(courseId) {
  // AI 出 SVG 最坏 2 轮 LLM（每轮读超时 60s），给足超时避免前端先放弃
  return http.post('/teacher/graph/preview-svg', null, { params: { courseId }, timeout: 180000 })
}
