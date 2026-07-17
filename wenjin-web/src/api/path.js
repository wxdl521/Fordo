import { http } from './http.js'

/** 生成（重算）学习路径；targetNodeId 省略时服务端取诊断卡点 */
export function generatePath(payload, config) {
  // 不发送 studentId：身份由服务端 principal 派生
  const body = { ...payload }
  delete body.studentId
  return config
    ? http.post('/path/generate', body, config)
    : http.post('/path/generate', body)
}

/** 加载当前有效学习路径 */
export function fetchCurrentPath(courseId, config = {}) {
  return http.get('/path/current', {
    ...config,
    params: { ...config.params, courseId }
  })
}

/** 标记某步完成 */
export function completeItem(itemId, config = {}) {
  return http.post('/path/item/complete', null, {
    ...config,
    params: { ...config.params, itemId }
  })
}
