import { http } from './http.js'

/** 生成（重算）学习路径；targetNodeId 省略时服务端取诊断卡点 */
export function generatePath(payload) {
  return http.post('/path/generate', payload)
}

/** 加载当前有效学习路径 */
export function fetchCurrentPath(studentId, courseId) {
  return http.get('/path/current', { params: { studentId, courseId } })
}

/** 标记某步完成 */
export function completeItem(itemId) {
  return http.post('/path/item/complete', null, { params: { itemId } })
}
