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
/**
 * 编辑编程题的起始代码与用例（教师可见/可改全部用例）。
 * PUT /teacher/questions/{id}/code  body { starterCode, cases:[{seq, stdin, expectedStdout, hidden}] }
 */
export function updateQuestionCode(courseId, id, { starterCode, cases }) {
  return http.put(`/teacher/questions/${id}/code`, { starterCode, cases }, { params: { courseId } })
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

// ── RAG 语料审核（教师端）──
export function fetchRagSources(courseId, config = {}) {
  return http.get('/teacher/rag/sources', {
    ...config,
    params: { ...config.params, courseId }
  })
}
export function fetchRagMappings({ courseId, nodeCode, status, page, size }, config = {}) {
  return http.get('/teacher/rag/mappings', {
    ...config,
    params: { ...config.params, courseId, nodeCode, status, page, size }
  })
}
export function approveRagMapping(id) {
  return http.post(`/teacher/rag/mappings/${id}/approve`)
}
export function rejectRagMapping(id) {
  return http.post(`/teacher/rag/mappings/${id}/reject`)
}
export function batchApproveRagMappings(courseId, body = {}) {
  return http.post('/teacher/rag/mappings/batch-approve', body, { params: { courseId } })
}
export function createNodeRefSource(body) {
  return http.post('/teacher/rag/sources/node-ref', body)
}
/**
 * 上传 md/txt/pdf/docx，异步入库 + 自动初标（HTTP 202）。
 * @param {number} courseId
 * @param {File} file
 * @param {{ title?: string, kind?: number }} [opts] kind: 0 课件(默认) / 1 教材
 * @returns {Promise<{ sourceId: number, jobId: number, title: string, kind: number, status: number }>}
 */
export function uploadRagSource(courseId, file, opts = {}) {
  const form = new FormData()
  form.append('courseId', String(courseId))
  form.append('file', file)
  if (opts.title) form.append('title', opts.title)
  if (opts.kind != null) form.append('kind', String(opts.kind))
  return http.post('/teacher/rag/sources/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 「重建向量」：全课程按 READY 来源派发 RAG_EMBED（R2）。rag / rag-vector 关 → 404，无嵌入模型 → 503。
 * @param {number} courseId
 * @returns {Promise<{ model: string, sourceCount: number, jobIds: number[] }>}
 */
export function reembedRagCourse(courseId) {
  return http.post(`/teacher/rag/courses/${courseId}/reembed`)
}

// ── 多智能体报告（教师端）──
export function fetchTeacherReports(courseId, kind, config = {}) {
  return http.get('/teacher/reports', {
    ...config,
    params: { ...config.params, courseId, kind }
  })
}
