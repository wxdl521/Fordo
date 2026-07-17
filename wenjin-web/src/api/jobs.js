import { http } from './http.js'

/**
 * 查询 AI 作业进度/结果。
 * @param {number|string} id
 * @returns {Promise<AiJobVO>}
 */
export function getJob(id) {
  return http.get(`/teacher/jobs/${id}`)
}

/**
 * 取消作业：PENDING 立即取消；RUNNING 协作取消。
 * @param {number|string} id
 * @returns {Promise<AiJobVO>}
 */
export function cancelJob(id) {
  return http.post(`/teacher/jobs/${id}/cancel`)
}

/**
 * 作业是否终态。
 * @param {{ status?: number } | null | undefined} job
 */
export function isJobTerminal(job) {
  const s = job?.status
  return s === 2 || s === 3 || s === 4
}

/**
 * 作业是否成功。
 * @param {{ status?: number } | null | undefined} job
 */
export function isJobSucceeded(job) {
  return job?.status === 2
}
