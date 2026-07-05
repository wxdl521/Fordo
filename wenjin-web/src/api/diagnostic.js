import { http } from './http.js'

/**
 * 获取诊断试卷（答案已剥离）
 * @param {number} courseId
 * @returns {Promise<PaperVO>} { courseId, total, questions: [{ questionId, stem, chapter, type, options: [{ key, text }] }] }
 */
export function fetchPaper(courseId) {
  return http.get('/diagnostic/paper', { params: { courseId } })
}

/**
 * 提交诊断答卷
 * @param {{ studentId: number, courseId: number, answers: Array<{ questionId: number, optionKey: string }> }} payload
 * @returns {Promise<SubmitResult>} { total, correctCount, grades: [{ questionId, correct, correctKey }] }
 */
export function submitPaper(payload) {
  return http.post('/diagnostic/submit', payload)
}

/**
 * 获取诊断回溯结果
 * @param {number} studentId
 * @param {number} courseId
 * @returns {Promise<DiagnosticResultVO>}
 */
export function fetchResult(studentId, courseId) {
  return http.get('/diagnostic/result', { params: { studentId, courseId } })
}

/**
 * 开始一次图引导自适应诊断（快速诊断）。
 * @param {{ studentId: number, courseId: number }} payload
 * @returns {Promise<AdaptiveVO>} { sessionId, done, probe|null, progress, summary|null, resultUrl|null }
 *   probe: { nodeId, questions: [脱敏 PaperQuestionVO] }（done=false 时非空，无 nodeName）
 *   progress: { tested, inferred, unknown, budgetLeft }
 *   summary: { tested, inferredPass, inferredFail, unknown, nodes: [...] }（done=true 时非空）
 */
export function startAdaptive(payload) {
  return http.post('/diagnostic/adaptive/start', payload)
}

/**
 * 提交当前探针作答，换取下一探针或结束。
 * @param {number} sessionId
 * @param {{ studentId: number, answers: Array<{ questionId: number, studentAnswer: string }> }} payload
 *   answers 必须 ⊆ 当前探针题目；studentAnswer 与固定卷 optionKey 同格式（如 'A'）
 * @returns {Promise<AdaptiveVO>} 结构同 startAdaptive
 */
export function answerAdaptive(sessionId, payload) {
  return http.post(`/diagnostic/adaptive/${sessionId}/answer`, payload)
}

/**
 * 放弃当前自适应诊断会话（服务端做终止收尾推断落库）。
 * @param {number} sessionId
 * @param {{ studentId: number }} payload
 * @returns {Promise<AdaptiveVO>}
 */
export function abandonAdaptive(sessionId, payload) {
  return http.post(`/diagnostic/adaptive/${sessionId}/abandon`, payload)
}

/**
 * 获取最近一次已完成自适应诊断会话的证据（回放归因，T8）。
 * @param {number} studentId
 * @param {number} courseId
 * @returns {Promise<EvidenceVO|null>}
 *   EvidenceVO: { sessionId, finishedAt, tested, inferredPass, inferredFail, unknown,
 *     inferredNodes: [{ nodeId, name, state, fromNodeId, fromName, fromPass }] }
 *   无已完成会话时 data=null（合法业务状态）。
 */
export function fetchEvidence(studentId, courseId) {
  return http.get('/diagnostic/adaptive/evidence', { params: { studentId, courseId } })
}
