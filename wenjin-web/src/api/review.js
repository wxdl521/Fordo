import { http } from './http.js'

/**
 * 今日复习队列（读时衰减：曾掌握、effective 已跌破 mastered）。
 * GET /api/review/today
 * @param {number} courseId
 * @returns {Promise<{ items: ReviewItem[], totalDecayingCount: number }>}
 */
export function fetchTodayReview(courseId) {
  return http.get('/review/today', { params: { courseId } })
}
