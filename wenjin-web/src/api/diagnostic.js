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
