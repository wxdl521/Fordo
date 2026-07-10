import axios from 'axios'

// 统一 axios 实例：开发期经 Vite proxy 转发到后端 8080
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

/** 清除登录态并在非登录页时跳转登录。 */
export function clearSessionAndRedirect() {
  localStorage.removeItem('wj_token')
  localStorage.removeItem('wj_user')
  if (typeof window !== 'undefined' && window.location.pathname !== '/') {
    window.location.assign('/')
  }
}

/**
 * 将非成功 Result 体（含 HTTP 非 2xx 下发的 {code,message,data}）统一拆解为带明细的 Error。
 * @param {{ status?: number, headers?: Record<string, string> }} responseLike
 * @param {{ code?: number, message?: string, data?: unknown, requestId?: string }} body
 */
export function unwrapFailure(responseLike, body) {
  const status = responseLike?.status
  const headers = responseLike?.headers || {}
  const code = body?.code
  if (code === 401 || status === 401) {
    clearSessionAndRedirect()
  }
  const err = new Error(body?.message || '请求失败')
  err.code = code
  err.detail = body?.data
  const headerId = headers['x-request-id'] ?? headers['X-Request-Id']
  err.requestId = headerId || body?.requestId || undefined
  err.httpStatus = status
  return Promise.reject(err)
}

/** 2xx 响应：code===0 取 data，否则按失败拆解。 */
export function unwrapSuccess(resp) {
  const body = resp.data
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === 0) return body.data
    return unwrapFailure(resp, body)
  }
  return body
}

/**
 * 错误响应：网络错误不伪装成业务失败；有 Result 体则 unwrapFailure。
 */
export function unwrapError(error) {
  if (!error || !error.response) {
    const err = new Error((error && error.message) || '网络错误')
    err.network = true
    err.isNetworkError = true
    return Promise.reject(err)
  }
  const body = error.response.data
  if (body && typeof body === 'object' && 'code' in body) {
    return unwrapFailure(error.response, body)
  }
  const err = new Error(
    (body && typeof body === 'object' && body.message) || error.message || '请求失败'
  )
  err.httpStatus = error.response.status
  err.network = false
  return Promise.reject(err)
}

// 请求拦截器：带上登录令牌（Bearer）。SSE 走原生 fetch 不经此处，见 companion.js。
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('wj_token')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一 Result 拆解；非 2xx 经 unwrapError 保留业务码与 requestId
http.interceptors.response.use(unwrapSuccess, unwrapError)

export { http }
