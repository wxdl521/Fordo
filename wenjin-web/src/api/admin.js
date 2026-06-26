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
 * 从课程标准(图片/文档)抽取图谱草稿并暂存(返回 draftId,不落库)
 * @param {string} courseCode
 * @param {File} file
 * @param {function} [onUploadProgress]
 * @returns {Promise<{draftId:string, draft:{nodes:Array, edges:Array}}>}
 */
export function extractGraphFromFile(courseCode, file, onUploadProgress) {
  const form = new FormData()
  form.append('file', file)
  // 竖长图会被后端切成多片(compressTiles),逐片串行做视觉转写(每片读超时 120s),
  // 之后再做一次图谱抽取(读超时 120s)。多片相加很容易超过 3 分钟,故给足 10 分钟,
  // 避免前端先报"timeout exceeded"而后端仍在出图。(Vite 代理 proxyTimeout 同步放宽)
  return http.post('/admin/graph/extract', form, {
    params: { courseCode },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 600000,
    onUploadProgress
  })
}

/**
 * 按 draftId 拉取暂存草稿(审核页刷新用)
 * @param {string} draftId
 * @returns {Promise<{nodes:Array, edges:Array}>}
 */
export function fetchExtractDraft(draftId) {
  return http.get(`/admin/graph/extract/${draftId}`)
}

/**
 * 提交审核结果:全量替换导入并返回指标
 * @param {string} draftId
 * @param {object} finalGraph  { nodes:[], edges:[] }
 * @returns {Promise<{ importResult:object, metrics:object }>}
 */
export function commitExtractDraft(draftId, finalGraph) {
  return http.post(`/admin/graph/extract/${draftId}/commit`, finalGraph)
}

/**
 * 抽取审核指标历史(倒序)
 * @param {string} courseCode
 * @returns {Promise<Array>}
 */
export function fetchExtractionReviews(courseCode) {
  return http.get('/admin/graph/extract/reviews', { params: { courseCode } })
}
