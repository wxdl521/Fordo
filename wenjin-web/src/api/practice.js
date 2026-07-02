import { http } from './http.js'

/**
 * 开始节点练习：组卷 + 创建 practice_session。
 * POST /api/practice/start
 * @param {{ studentId: number, courseId: number, nodeId: number, size?: number }} payload
 * @returns {Promise<PracticeStartVO>} { sessionId, node: { nodeId, nodeCode, name }, questions: [...] }
 */
export function startPractice(payload) {
  return http.post('/practice/start', payload)
}

/**
 * 提交练习作答：服务端判分 + 掌握度更新。
 * POST /api/practice/{sessionId}/submit
 * @param {number} sessionId
 * @param {{ studentId: number, answers: Array<{ questionId: number, studentAnswer: string }> }} payload
 * @returns {Promise<PracticeSubmitVO>}
 */
export function submitPractice(sessionId, payload) {
  return http.post(`/practice/${sessionId}/submit`, payload)
}

/**
 * 查询节点练习历史（供显示"上次练习 x/y"）。
 * GET /api/practice/history
 * @param {number} studentId
 * @param {number} courseId
 * @param {number} nodeId
 * @returns {Promise<PracticeHistoryVO[]>}
 */
export function fetchPracticeHistory(studentId, courseId, nodeId) {
  return http.get('/practice/history', { params: { studentId, courseId, nodeId } })
}
