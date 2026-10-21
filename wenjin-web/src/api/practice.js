import { http } from './http.js'

/**
 * 开始节点练习：组卷 + 创建 practice_session。
 * POST /api/practice/start
 * @param {{ courseId: number, nodeId: number, size?: number, pathItemId?: number }} payload
 * @param {{ signal?: AbortSignal }} [config]
 * @returns {Promise<PracticeStartVO>} { sessionId, node: { nodeId, nodeCode, name }, questions: [...] }
 */
export function startPractice(payload, config) {
  const body = { ...payload }
  delete body.studentId
  return config
    ? http.post('/practice/start', body, config)
    : http.post('/practice/start', body)
}

/**
 * 提交练习作答：服务端判分 + 掌握度更新。
 * POST /api/practice/{sessionId}/submit
 * @param {number} sessionId
 * @param {{ answers: Array<{ questionId: number, studentAnswer: string }> }} payload
 * @param {{ signal?: AbortSignal }} [config]
 * @returns {Promise<PracticeSubmitVO>}
 */
export function submitPractice(sessionId, payload, config) {
  const body = { ...payload }
  delete body.studentId
  return config
    ? http.post(`/practice/${sessionId}/submit`, body, config)
    : http.post(`/practice/${sessionId}/submit`, body)
}

