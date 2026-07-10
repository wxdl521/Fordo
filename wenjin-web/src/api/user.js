import { http } from './http.js'

/** 注册（公开注册仅学生；不接受/不提交 role，防止客户端提权） */
export function register({ username, password, realName }) {
  return http.post('/register', { username, password, realName })
}

/** 登录 */
export function login({ username, password }) {
  return http.post('/login', { username, password })
}

/** 查询用户 */
export function getUser(id) {
  return http.get(`/user/${id}`)
}
