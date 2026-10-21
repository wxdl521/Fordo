import { http } from './http.js'

/**
 * 系统管理后台 · 用户管理 + 审计与监控接口（/api/system-admin，仅管理员，后端 AdminAuthInterceptor 保护）。
 * 所有函数返回已被 http 拦截器剥掉 Result 信封后的 data；失败 reject Error(message)。
 */

/** 去掉 undefined / null / 空串，避免把空筛选条件拼进 query。 */
function compact(params) {
  const out = {}
  for (const [key, value] of Object.entries(params || {})) {
    if (value === undefined || value === null || value === '') continue
    out[key] = value
  }
  return out
}

/**
 * 用户分页列表。
 * @param {{ keyword?: string, role?: number|'', status?: number|'', page?: number, size?: number }} [query]
 * @param {object} [config] axios 额外配置（如 signal）
 * @returns {Promise<{ records: AdminUserVO[], total: number, page: number, size: number }>}
 */
export function listUsers(query = {}, config = {}) {
  return http.get('/system-admin/users', { ...config, params: compact(query) })
}

/**
 * 新建用户（角色仅教师/学生）。
 * @param {{ username: string, password: string, realName: string, role: number }} payload
 * @returns {Promise<AdminUserVO>}
 */
export function createUser({ username, password, realName, role }) {
  return http.post('/system-admin/users', { username, password, realName, role })
}

/**
 * 修改姓名。
 * @param {number} id
 * @param {{ realName: string }} payload
 * @returns {Promise<AdminUserVO>}
 */
export function updateUser(id, { realName }) {
  return http.put(`/system-admin/users/${id}`, { realName })
}

/**
 * 启用 / 禁用。
 * @param {number} id
 * @param {0|1} status 1=启用 0=禁用
 * @returns {Promise<AdminUserVO>}
 */
export function setUserStatus(id, status) {
  return http.patch(`/system-admin/users/${id}/status`, { status })
}

/**
 * 重置密码：newPassword 为空 → 服务端生成 12 位随机密码，仅本次响应返回一次。
 * @param {number} id
 * @param {string} [newPassword]
 * @returns {Promise<{ password: string | null }>}
 */
export function resetUserPassword(id, newPassword) {
  const body = newPassword ? { newPassword } : {}
  return http.post(`/system-admin/users/${id}/reset-password`, body)
}

/**
 * 变更角色（教师 ⇄ 学生；管理员行不可改）。
 * @param {number} id
 * @param {number} role 1 或 2
 */
export function changeUserRole(id, role) {
  return http.patch(`/system-admin/users/${id}/role`, { role })
}

/**
 * 下载批量导入模板（xlsx）。
 *
 * 注意：responseType 为 'blob'。http 拦截器对成功的 Blob 响应原样返回（Blob 没有 code 字段，不会被当 Result 拆包）；
 * 失败时 http.js 会把 Blob 里的 JSON Result 读出来，保留后端 message。
 * 兜底：若 2xx 返回的是 JSON（后端把错误包在 Result 里），这里也转成 Error。
 * @returns {Promise<Blob>}
 */
export async function downloadImportTemplate() {
  const blob = await http.get('/system-admin/users/import-template', { responseType: 'blob' })
  if (typeof Blob !== 'undefined' && blob instanceof Blob && /json/i.test(blob.type || '')) {
    let message = '下载模板失败'
    try {
      const parsed = JSON.parse(await blob.text())
      if (parsed && typeof parsed.message === 'string' && parsed.message) message = parsed.message
    } catch {
      /* 保持默认 message */
    }
    throw new Error(message)
  }
  return blob
}

/**
 * 批量导入用户（全有或全无）。
 * @param {File} file .xlsx，≤2MB，≤1000 数据行
 * @param {object} [config] axios 额外配置
 * @returns {Promise<{ created: number, errors: { row: number, username: string, message: string }[] }>}
 */
export function importUsers(file, config = {}) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/system-admin/users/import', form, {
    ...config,
    headers: { 'Content-Type': 'multipart/form-data', ...(config.headers || {}) }
  })
}

// ───────── 审计与监控（A2） ─────────

/**
 * 操作日志分页列表（倒序）。
 * @param {{ operator?: string, action?: string, success?: 0|1|'', from?: string, to?: string, page?: number, size?: number }} [query]
 *   from / to 为 yyyy-MM-dd（含当日）；operator 为用户名模糊匹配
 * @param {object} [config] axios 额外配置（如 signal）
 * @returns {Promise<{ records: AuditLogVO[], total: number, page: number, size: number }>}
 */
export function listAuditLogs(query = {}, config = {}) {
  return http.get('/system-admin/audit-logs', { ...config, params: compact(query) })
}

/**
 * 库里出现过的操作类型（去重），供筛选下拉。
 * @param {object} [config] axios 额外配置
 * @returns {Promise<string[]>}
 */
export function listAuditActions(config = {}) {
  return http.get('/system-admin/audit-logs/actions', config)
}

/**
 * 登录日志分页列表（倒序，成功与失败都有）。
 * @param {{ username?: string, success?: 0|1|'', from?: string, to?: string, page?: number, size?: number }} [query]
 * @param {object} [config] axios 额外配置
 * @returns {Promise<{ records: LoginLogVO[], total: number, page: number, size: number }>}
 */
export function listLoginLogs(query = {}, config = {}) {
  return http.get('/system-admin/login-logs', { ...config, params: compact(query) })
}

/**
 * 平台总览：用户 / 课程计数、近 N 天活跃趋势、AI 用量。
 * @param {{ days?: number }} [query] days 默认 7，范围 1–30
 * @param {object} [config] axios 额外配置
 * @returns {Promise<AdminOverviewVO>}
 */
export function getOverview(query = {}, config = {}) {
  return http.get('/system-admin/overview', { ...config, params: compact(query) })
}
