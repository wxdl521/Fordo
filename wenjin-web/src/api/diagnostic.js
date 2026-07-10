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
 * 提交诊断答卷（身份由服务端 principal 派生）
 * @param {{ courseId: number, answers: Array<{ questionId: number, optionKey: string }> }} payload
 * @returns {Promise<SubmitResult>} { total, correctCount, grades: [{ questionId, correct, correctKey }] }
 */
export function submitPaper(payload) {
  const body = { ...payload }
  delete body.studentId
  return http.post('/diagnostic/submit', body)
}

/**
 * 获取诊断回溯结果
 * @param {number} courseId
 * @returns {Promise<DiagnosticResultVO>}
 */
export function fetchResult(courseId) {
  return http.get('/diagnostic/result', { params: { courseId } })
}

/**
 * 开始一次图引导自适应诊断（快速诊断）。
 * @param {{ courseId: number }} payload
 * @returns {Promise<AdaptiveVO>} { sessionId, done, probe|null, progress, summary|null, resultUrl|null }
 */
export function startAdaptive(payload) {
  const body = { ...payload }
  delete body.studentId
  return http.post('/diagnostic/adaptive/start', body)
}

/**
 * 提交当前探针作答，换取下一探针或结束。
 * @param {number} sessionId
 * @param {{ answers: Array<{ questionId: number, studentAnswer: string }> }} payload
 * @returns {Promise<AdaptiveVO>} 结构同 startAdaptive
 */
export function answerAdaptive(sessionId, payload) {
  const body = { ...payload }
  delete body.studentId
  return http.post(`/diagnostic/adaptive/${sessionId}/answer`, body)
}

/**
 * 放弃当前自适应诊断会话（服务端做终止收尾推断落库）。
 * @param {number} sessionId
 * @param {object} [payload]
 * @returns {Promise<AdaptiveVO>}
 */
export function abandonAdaptive(sessionId, payload = {}) {
  const body = { ...payload }
  delete body.studentId
  return http.post(`/diagnostic/adaptive/${sessionId}/abandon`, body)
}

/**
 * 获取最近一次已完成自适应诊断会话的证据（回放归因，T8）。
 * @param {number} courseId
 * @returns {Promise<EvidenceVO|null>}
 */
export function fetchEvidence(courseId) {
  return http.get('/diagnostic/adaptive/evidence', { params: { courseId } })
}
