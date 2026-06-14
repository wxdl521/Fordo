import axios from 'axios'

// 统一 axios 实例：开发期经 Vite proxy 转发到后端 8080
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 拆解统一返回体 Result<T>：code===0 取 data，否则抛出携带明细的错误
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      const err = new Error(body.message || '请求失败')
      err.code = body.code
      err.detail = body.data
      return Promise.reject(err)
    }
    return body
  },
  (error) => Promise.reject(error)
)

/** 查询某课程图谱（节点 + 边） */
export function fetchGraph(courseId) {
  return http.get(`/graph/${courseId}`)
}
