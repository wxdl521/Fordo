import { http } from './http.js'

function post(url, body, config) {
  return config && Object.keys(config).length > 0
    ? http.post(url, body, config)
    : http.post(url, body)
}

/**
 * 获取诊断试卷（答案已剥离；含冻结 sessionToken）
 * @param {number} courseId
 * @returns {Promise<PaperVO>} { courseId, sessionToken, total, questions: [{ questionId, stem, chapter, type, options: [{ key, text }] }] }
 */
export function fetchPaper(courseId, config = {}) {
  return http.get('/diagnostic/paper', {
    ...config,
    params: { ...config.params, courseId }
  })
}

/**
 * 提交诊断答卷（身份由服务端 principal 派生；须带 sessionToken）
 * @param {{ sessionToken: string, answers: Array<{ questionId: number, optionKey: string|null }>, courseId?: number }} payload
 * @returns {Promise<SubmitResult>} { total, correctCount, grades: [{ questionId, correct, correctKey }] }
 */
export function submitPaper(payload, config = {}) {
  const body = {
    sessionToken: payload.sessionToken,
    answers: payload.answers
  }
  // courseId 可选（服务端以会话为准）；不再传 studentId
  if (payload.courseId != null) {
    body.courseId = payload.courseId
  }
  return post('/diagnostic/submit', body, config)
}

/**
 * 获取诊断回溯结果
 * @param {number} courseId
 * @returns {Promise<DiagnosticResultVO>}
 */
export function fetchResult(courseId, config = {}) {
  return http.get('/diagnostic/result', {
    ...config,
    params: { ...config.params, courseId }
  })
}

/**
 * 开始一次图引导自适应诊断（快速诊断）。
 * @param {{ courseId: number }} payload
 * @returns {Promise<AdaptiveVO>} { sessionId, done, probe|null, progress, summary|null, resultUrl|null }
 */
export function startAdaptive(payload, config = {}) {
  const body = { ...payload }
  delete body.studentId
  return post('/diagnostic/adaptive/start', body, config)
}

/**
 * 提交当前探针作答，换取下一探针或结束。
 * @param {number} sessionId
 * @param {{ answers: Array<{ questionId: number, studentAnswer: string }> }} payload
 * @returns {Promise<AdaptiveVO>} 结构同 startAdaptive
 */
export function answerAdaptive(sessionId, payload, config = {}) {
  const body = { ...payload }
  delete body.studentId
  return post(`/diagnostic/adaptive/${sessionId}/answer`, body, config)
}

/**
 * 放弃当前自适应诊断会话（服务端做终止收尾推断落库）。
 * @param {number} sessionId
 * @param {object} [payload]
 * @returns {Promise<AdaptiveVO>}
 */
export function abandonAdaptive(sessionId, payload = {}, config = {}) {
  const body = { ...payload }
  delete body.studentId
  return post(`/diagnostic/adaptive/${sessionId}/abandon`, body, config)
}

/**
 * 获取最近一次已完成自适应诊断会话的证据（回放归因，T8）。
 * @param {number} courseId
 * @returns {Promise<EvidenceVO|null>}
 */
export function fetchEvidence(courseId, config = {}) {
  return http.get('/diagnostic/adaptive/evidence', {
    ...config,
    params: { ...config.params, courseId }
  })
}
