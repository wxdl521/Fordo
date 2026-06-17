import { http } from './http.js'

/** 注册 */
export function register({ username, password, realName, role }) {
  return http.post('/register', { username, password, realName, role })
}

/** 登录 */
export function login({ username, password }) {
  return http.post('/login', { username, password })
}

/** 查询用户 */
export function getUser(id) {
  return http.get(`/user/${id}`)
}
