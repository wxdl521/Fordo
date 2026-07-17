import { http } from './http.js'

/**
 * 获取当前登录学生在某课程下的成长档案
 * @param {number} courseId
 * @param {{ signal?: AbortSignal, params?: Record<string, unknown> }} [config]
 * @returns {Promise<Object>} 成长档案数据（曲线 + 前后对比）
 */
export function fetchGrowth(courseId, config = {}) {
  return http.get('/growth', {
    ...config,
    params: { ...config.params, courseId }
  })
}
