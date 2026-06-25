import { http } from './http.js'

/**
 * 触发 AI 出题
 * @param {number} courseId   课程 ID
 * @param {string} nodeCode   知识点编码，如 'KT07'
 * @param {number} count      出题数量
 * @returns {Promise<GenerateResult>} { generated, dropped, duplicated, questionIds, message }
 */
export function generateQuestions(courseId, nodeCode, count) {
  // AI 出题是同步长任务：后端每轮 LLM 读超时 60s，最多 2 轮（MAX_ROUNDS），
  // 20 题最坏 ~120s+，远超默认 15s。给足超时，避免前端先放弃、后端却仍在出题
  // （表现为前台报超时、刷新后题目又出现）。
  return http.post('/admin/question/generate', null, {
    params: { courseId, nodeCode, count },
    timeout: 180000
  })
}

/**
 * 批量标注题目知识点
 * @param {number} courseId  课程 ID
 * @param {object} req       { items: [{ stem, options }] }
 * @returns {Promise<Array<AnnotateItemResult>>} each = { stem, mainPoint, subPoints, reason, persisted }
 */
export function annotateQuestions(courseId, req) {
  return http.post('/admin/question/annotate', req, { params: { courseId } })
}

/**
 * 导入题库（JSON 文件上传）
 * @param {number} courseId  课程 ID
 * @param {File} file  .json 文件
 * @returns {Promise<ImportBankResult>} { imported, skipped, aiCleaned }
 */
export function importQuestionJson(courseId, file) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/question/import/json', form, {
    params: { courseId },
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 导入题库（Excel + AI 清洗）
 * @param {number} courseId  课程 ID
 * @param {File} file  .xlsx / .xls 文件
 * @param {function} [onUploadProgress]  上传进度回调
 * @returns {Promise<ImportBankResult>} { imported, skipped, aiCleaned }
 */
export function importQuestionExcel(courseId, file, onUploadProgress) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/question/import/excel', form, {
    params: { courseId },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    onUploadProgress
  })
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

/**
 * 从课程标准(图片/文档)抽取图谱草稿(不落库)
 * @param {string} courseCode
 * @param {File} file  图片(或后续文档)
 * @param {function} [onUploadProgress]
 * @returns {Promise<{nodes:Array, edges:Array}>} 图谱草稿
 */
export function extractGraphFromFile(courseCode, file, onUploadProgress) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/graph/extract', form, {
    params: { courseCode },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180000,
    onUploadProgress
  })
}
