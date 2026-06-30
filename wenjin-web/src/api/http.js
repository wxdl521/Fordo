import axios from 'axios'

// 统一 axios 实例：开发期经 Vite proxy 转发到后端 8080
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器：带上登录令牌（Bearer）。SSE 走原生 fetch 不经此处，见 companion.js。
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('wj_token')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// 拆解统一返回体 Result<T>：code===0 取 data，code===401 清态跳登录，否则抛出携带明细的错误
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      if (body.code === 401) {
        // 令牌缺失/失效：清登录态，跳登录页（路由路径为 '/'）。避免在登录页自跳。
        localStorage.removeItem('wj_token')
        localStorage.removeItem('wj_user')
        if (window.location.pathname !== '/') window.location.assign('/')
      }
      const err = new Error(body.message || '请求失败')
      err.code = body.code
      err.detail = body.data
      return Promise.reject(err)
    }
    return body
  },
  (error) => Promise.reject(error)
)

export { http }
