import { http } from './http.js'

/**
 * 学生每日简报（计划任务 / 复习积压 / 薄弱下滑）。
 * GET /api/student/daily-brief?courseId=
 */
export function fetchDailyBrief(courseId, config = {}) {
  return http.get('/student/daily-brief', {
    ...config,
    params: { ...config.params, courseId }
  })
}

/**
 * 语料切块详情（引用抽屉）。
 * GET /api/student/rag/chunks/{id}
 */
export function fetchStudentChunk(id, config = {}) {
  return http.get(`/student/rag/chunks/${id}`, config)
}
