import { http } from './http.js'

/**
 * 触发 AI 出题
 * @param {string} nodeCode  知识点编码，如 'KT07'
 * @param {number} count     出题数量
 * @returns {Promise<GenerateResult>} { generated, dropped, duplicated, questionIds, message }
 */
export function generateQuestions(nodeCode, count) {
  return http.post('/admin/question/generate', null, { params: { nodeCode, count } })
}

/**
 * 批量标注题目知识点
 * @param {{ items: Array<{ stem: string, options: Array<{ key: string, text: string, correct: boolean }> }> }} req
 * @returns {Promise<Array<AnnotateItemResult>>} each = { stem, mainPoint, subPoints, reason, persisted }
 */
export function annotateQuestions(req) {
  return http.post('/admin/question/annotate', req)
}

/**
 * 导入题库（固定课程）
 * @param {string} courseCode
 * @returns {Promise<ImportBankResult>} { imported, skipped }
 */
export function importBank(courseCode) {
  return http.post('/admin/question/import-bank', null, { params: { courseCode } })
}
