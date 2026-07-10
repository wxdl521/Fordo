import { http } from './http.js'

/**
 * 开始节点练习：组卷 + 创建 practice_session。
 * POST /api/practice/start
 * @param {{ courseId: number, nodeId: number, size?: number, pathItemId?: number }} payload
 * @returns {Promise<PracticeStartVO>} { sessionId, node: { nodeId, nodeCode, name }, questions: [...] }
 */
export function startPractice(payload) {
  const body = { ...payload }
  delete body.studentId
  return http.post('/practice/start', body)
}

/**
 * 提交练习作答：服务端判分 + 掌握度更新。
 * POST /api/practice/{sessionId}/submit
 * @param {number} sessionId
 * @param {{ answers: Array<{ questionId: number, studentAnswer: string }> }} payload
 * @returns {Promise<PracticeSubmitVO>}
 */
export function submitPractice(sessionId, payload) {
  const body = { ...payload }
  delete body.studentId
  return http.post(`/practice/${sessionId}/submit`, body)
}

/**
 * 查询节点练习历史（供显示"上次练习 x/y"）。
 * GET /api/practice/history
 * @param {number} courseId
 * @param {number} nodeId
 * @returns {Promise<PracticeHistoryVO[]>}
 */
export function fetchPracticeHistory(courseId, nodeId) {
  return http.get('/practice/history', { params: { courseId, nodeId } })
}
