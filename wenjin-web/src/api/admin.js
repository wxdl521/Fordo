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

/**
 * 导入知识图谱（JSON）
 * @param {string} courseCode
 * @param {object} graphData  { nodes: [], edges: [] }
 * @returns {Promise<{ nodeCount: number, edgeCount: number }>}
 */
export function importGraphJson(courseCode, graphData) {
  return http.post('/admin/graph/import', graphData, { params: { courseCode } })
}

/**
 * 导入知识图谱（Excel + AI 清洗）
 * @param {string} courseCode
 * @param {File} file  .xlsx / .xls 文件
 * @param {function} [onUploadProgress]  上传进度回调
 * @returns {Promise<{ nodeCount: number, edgeCount: number }>}
 */
export function importGraphExcel(courseCode, file, onUploadProgress) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/graph/import/excel', form, {
    params: { courseCode },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    onUploadProgress
  })
}
