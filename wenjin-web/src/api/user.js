import { http } from './http.js'

/** 注册（公开注册仅学生；不接受/不提交 role，防止客户端提权） */
export function register({ username, password, realName }, config) {
  const body = { username, password, realName }
  return config ? http.post('/register', body, config) : http.post('/register', body)
}

/** 登录 */
export function login({ username, password }, config) {
  const body = { username, password }
  return config ? http.post('/login', body, config) : http.post('/login', body)
}

/**
 * 刷新当前会话用户（服务端权威身份）。
 * localStorage.wj_user 仅作展示缓存，不能决定服务端权限。
 */
export function me() {
  return http.get('/me')
}
