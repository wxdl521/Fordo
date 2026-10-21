import axios from 'axios'
import { getActivePinia } from 'pinia'

// 统一 axios 实例：开发期经 Vite proxy 转发到后端 8080
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

const UNSCOPED_AUTH_FAILURE = Symbol('unscoped-auth-failure')

function requestAuthToken(...configs) {
  for (const config of configs) {
    if (config && Object.prototype.hasOwnProperty.call(config, '__wjAuthToken')) {
      return config.__wjAuthToken
    }
  }
  return undefined
}

/**
 * 清除登录态并在非登录页时跳转登录。
 * 优先走 session/course store（若 Pinia 已激活），否则仅清 localStorage。
 * 传入请求发出时捕获的 token 后，仅当它仍是当前会话时才执行清理。
 * 不传参数保留退出登录等调用点的无条件清理语义。
 *
 * @param {string|null|symbol} [expectedToken]
 * @returns {boolean} 是否接受并执行了本次清理
 */
export function clearSessionAndRedirect(expectedToken = UNSCOPED_AUTH_FAILURE) {
  const currentToken = localStorage.getItem('wj_token')
  if (expectedToken !== UNSCOPED_AUTH_FAILURE && currentToken !== expectedToken) {
    return false
  }

  const pinia = getActivePinia()
  if (pinia) {
    // 动态 import 切断 http ↔ session ↔ user 的静态环
    import('../store/session.js').then(({ useSessionStore }) => {
      // import 解析期间可能已经完成新登录；任何新 token 都属于更新一代会话。
      if (localStorage.getItem('wj_token') !== null) return
      try {
        useSessionStore(pinia).clearSession()
      } catch {
        // localStorage 已同步清理；store 不可用时无需再次写入。
      }
    }).catch(() => {})
  } else {
    localStorage.removeItem('wj_token')
    localStorage.removeItem('wj_user')
  }
  // 同步清 LS，避免拦截器读到旧 token（store clear 可能异步）
  localStorage.removeItem('wj_token')
  localStorage.removeItem('wj_user')
  if (typeof window !== 'undefined' && window.location.pathname !== '/') {
    window.location.assign('/')
  }
  return true
}

/**
 * 将非成功 Result 体（含 HTTP 非 2xx 下发的 {code,message,data}）统一拆解为带明细的 Error。
 * @param {{ status?: number, headers?: Record<string, string>, config?: Record<string, unknown> }} responseLike
 * @param {{ code?: number, message?: string, data?: unknown, requestId?: string }} body
 * @param {string|null|undefined} [expectedToken]
 */
export function unwrapFailure(responseLike, body, expectedToken = requestAuthToken(responseLike?.config)) {
  const status = responseLike?.status
  const headers = responseLike?.headers || {}
  const code = body?.code
  if (code === 401 || status === 401) {
    clearSessionAndRedirect(expectedToken)
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

function isBlob(value) {
  return typeof Blob !== 'undefined' && value instanceof Blob
}

/**
 * responseType: 'blob' 的请求失败时，服务端的 JSON Result 体也是 Blob；
 * 读出来解析成对象，业务 message / code 才不会丢。非 JSON 或读取失败 → undefined（走通用错误）。
 * @param {Blob} blob
 * @returns {Promise<unknown>}
 */
function readBlobJson(blob) {
  if (typeof blob.text !== 'function') return Promise.resolve(undefined)
  return blob.text().then(
    (text) => {
      try {
        return JSON.parse(text)
      } catch {
        return undefined
      }
    },
    () => undefined
  )
}

/**
 * 错误响应：网络错误不伪装成业务失败；有 Result 体则 unwrapFailure。
 * 2xx 的 Blob 下载响应不经此处（unwrapSuccess 对无 code 字段的对象原样返回）。
 */
export function unwrapError(error) {
  if (!error || !error.response) {
    // axios 取消
    if (error && (error.code === 'ERR_CANCELED' || error.name === 'CanceledError' || error.name === 'AbortError')) {
      return Promise.reject(error)
    }
    const err = new Error((error && error.message) || '网络错误')
    err.network = true
    err.isNetworkError = true
    return Promise.reject(err)
  }
  const raw = error.response.data
  if (isBlob(raw)) {
    return readBlobJson(raw).then((parsed) => rejectWithBody(error, parsed))
  }
  return rejectWithBody(error, raw)
}

function rejectWithBody(error, body) {
  const expectedToken = requestAuthToken(error.config, error.response.config)
  if (body && typeof body === 'object' && 'code' in body) {
    return unwrapFailure(error.response, body, expectedToken)
  }
  const err = new Error(
    (body && typeof body === 'object' && body.message) || error.message || '请求失败'
  )
  err.httpStatus = error.response.status
  err.network = false
  if (error.response.status === 401) {
    clearSessionAndRedirect(expectedToken)
  }
  return Promise.reject(err)
}

// 请求拦截器：带上登录令牌（Bearer）。SSE 走原生 fetch 不经此处，见 companion.js。
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('wj_token')
  config.__wjAuthToken = token
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一 Result 拆解；非 2xx 经 unwrapError 保留业务码与 requestId
http.interceptors.response.use(unwrapSuccess, unwrapError)

export { http }
