import { http } from './http.js'

/**
 * AI 出题 → 异步作业（HTTP 202 + AiJobVO）
 * @param {number} courseId
 * @param {string} nodeCode
 * @param {number} count
 * @param {{ headers?: Record<string,string> }} [opts]
 * @returns {Promise<AiJobVO>}
 */
export function generateQuestions(courseId, nodeCode, count, opts = {}) {
  return http.post('/admin/question/generate', null, {
    params: { courseId, nodeCode, count },
    headers: { ...(opts.headers || {}) }
  })
}

/**
 * 批量标注 → 异步作业
 * @param {number} courseId
 * @param {object} req
 * @param {{ headers?: Record<string,string> }} [opts]
 * @returns {Promise<AiJobVO>}
 */
export function annotateQuestions(courseId, req, opts = {}) {
  return http.post('/admin/question/annotate', req, {
    params: { courseId },
    headers: { ...(opts.headers || {}) }
  })
}

/**
 * 导入题库（JSON，同步）
 * @param {number} courseId
 * @param {File} file
 * @returns {Promise<ImportBankResult>}
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
 * 导入题库（Excel + AI 清洗）→ 异步作业
 * @param {number} courseId
 * @param {File} file
 * @param {{ headers?: Record<string,string>, onUploadProgress?: function }} [opts]
 * @returns {Promise<AiJobVO>}
 */
export function importQuestionExcel(courseId, file, opts = {}) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/question/import/excel', form, {
    params: { courseId },
    headers: {
      'Content-Type': 'multipart/form-data',
      ...(opts.headers || {})
    },
    onUploadProgress: opts.onUploadProgress
  })
}

/**
 * 导入知识图谱（JSON，同步）
 * @param {string} courseCode
 * @param {object} graphData
 * @returns {Promise<{ nodeCount: number, edgeCount: number }>}
 */
export function importGraphJson(courseCode, graphData) {
  return http.post('/admin/graph/import', graphData, { params: { courseCode } })
}

/**
 * 导入知识图谱（Excel + AI 清洗）→ 异步作业
 * @param {string} courseCode
 * @param {File} file
 * @param {{ headers?: Record<string,string>, onUploadProgress?: function }} [opts]
 * @returns {Promise<AiJobVO>}
 */
export function importGraphExcel(courseCode, file, opts = {}) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/graph/import/excel', form, {
    params: { courseCode },
    headers: {
      'Content-Type': 'multipart/form-data',
      ...(opts.headers || {})
    },
    onUploadProgress: opts.onUploadProgress
  })
}

/**
 * 从课程标准抽取图谱草稿 → 异步作业
 * @param {string} courseCode
 * @param {File} file
 * @param {{ headers?: Record<string,string>, onUploadProgress?: function }} [opts]
 * @returns {Promise<AiJobVO>}
 */
export function extractGraphFromFile(courseCode, file, opts = {}) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/admin/graph/extract', form, {
    params: { courseCode },
    headers: {
      'Content-Type': 'multipart/form-data',
      ...(opts.headers || {})
    },
    onUploadProgress: opts.onUploadProgress
  })
}

/**
 * 按 draftId 拉取暂存草稿
 * @param {string} draftId
 */
export function fetchExtractDraft(draftId) {
  return http.get(`/admin/graph/extract/${draftId}`)
}

/**
 * 提交审核结果
 * @param {string} draftId
 * @param {object} finalGraph
 */
export function commitExtractDraft(draftId, finalGraph) {
  return http.post(`/admin/graph/extract/${draftId}/commit`, finalGraph)
}

/**
 * 抽取审核指标历史
 * @param {string} courseCode
 */
export function fetchExtractionReviews(courseCode, config = {}) {
  return http.get('/admin/graph/extract/reviews', {
    ...config,
    params: { ...config.params, courseCode }
  })
}

/** @deprecated 兼容旧签名：第三参为 onUploadProgress 函数 */
export function importQuestionExcelLegacy(courseId, file, onUploadProgress) {
  return importQuestionExcel(courseId, file, { onUploadProgress })
}
