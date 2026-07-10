import { http } from './http.js'

/**
 * 获取当前登录学生在某课程下的成长档案
 * @param {number} courseId
 * @returns {Promise<Object>} 成长档案数据（曲线 + 前后对比）
 */
export function fetchGrowth(courseId) {
  return http.get('/growth', { params: { courseId } })
}
