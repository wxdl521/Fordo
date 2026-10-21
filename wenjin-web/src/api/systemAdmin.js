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

// ───────── 课程与班级（A3） ─────────

/**
 * 课程分页列表（管理员全量，含已归档）。
 * @param {{ keyword?: string, status?: 0|1|'', archived?: boolean|'', teacherId?: number|'', page?: number, size?: number }} [query]
 *   archived 为空 = 全部；true = 仅已归档；false = 仅未归档
 * @param {object} [config] axios 额外配置（如 signal）
 * @returns {Promise<{ records: AdminCourseVO[], total: number, page: number, size: number }>}
 */
export function listAdminCourses(query = {}, config = {}) {
  return http.get('/system-admin/courses', { ...config, params: compact(query) })
}

/**
 * 以指定教师为 owner 新建课程（teacherId 必须是启用中的教师）。
 * @param {{ name: string, teacherId: number }} payload
 * @returns {Promise<AdminCourseVO>}
 */
export function createAdminCourse({ name, teacherId }) {
  return http.post('/system-admin/courses', { name, teacherId })
}

/**
 * 转让课程 owner。
 * @param {number} id
 * @param {number} teacherId 新任课教师（启用中的教师）
 * @returns {Promise<AdminCourseVO>}
 */
export function transferCourseOwner(id, teacherId) {
  return http.patch(`/system-admin/courses/${id}/owner`, { teacherId })
}

/**
 * 发布 / 下架课程。
 * @param {number} id
 * @param {boolean} published true=发布 false=下架
 * @returns {Promise<AdminCourseVO>}
 */
export function setAdminCoursePublished(id, published) {
  return http.patch(`/system-admin/courses/${id}/status`, { published: Boolean(published) })
}

/** 归档课程（软删除，可恢复）。 */
export function archiveAdminCourse(id) {
  return http.post(`/system-admin/courses/${id}/archive`)
}

/** 恢复已归档课程。 */
export function restoreAdminCourse(id) {
  return http.post(`/system-admin/courses/${id}/restore`)
}

/**
 * 启用中的教师（建课 / 转让 / 筛选下拉用）。
 * @param {object} [config] axios 额外配置
 * @returns {Promise<{ id: number, username: string, realName: string }[]>}
 */
export function listTeachers(config = {}) {
  return http.get('/system-admin/teachers', config)
}

/**
 * 班级分页列表。
 * @param {{ keyword?: string, page?: number, size?: number }} [query]
 * @param {object} [config] axios 额外配置（如 signal）
 * @returns {Promise<{ records: ClazzVO[], total: number, page: number, size: number }>}
 */
export function listClazzes(query = {}, config = {}) {
  return http.get('/system-admin/clazzes', { ...config, params: compact(query) })
}

/**
 * 新建班级（班级名唯一）。
 * @param {{ name: string, description?: string }} payload
 * @returns {Promise<ClazzVO>}
 */
export function createClazz({ name, description }) {
  return http.post('/system-admin/clazzes', { name, description })
}

/**
 * 修改班级名称 / 描述（整体替换）。
 * @param {number} id
 * @param {{ name: string, description?: string }} payload
 * @returns {Promise<ClazzVO>}
 */
export function updateClazz(id, { name, description }) {
  return http.put(`/system-admin/clazzes/${id}`, { name, description })
}

/** 删除班级（连同成员 / 课程关联行；学生已有的选课记录保留）。 */
export function deleteClazz(id) {
  return http.delete(`/system-admin/clazzes/${id}`)
}

/**
 * 班级详情：基本信息 + 学生 + 关联课程。
 * @param {number} id
 * @param {object} [config] axios 额外配置（如 signal）
 * @returns {Promise<ClazzDetailVO>}
 */
export function getClazzDetail(id, config = {}) {
  return http.get(`/system-admin/clazzes/${id}`, config)
}

/**
 * 批量添加学生：传 usernames（字符串数组）或 studentIds（数字数组）二选一，≤500。
 * 已在别的班的学生会被转班；不存在 / 非学生的用户名放进 notFound。
 * @param {number} id
 * @param {{ usernames?: string[], studentIds?: number[] }} payload
 * @returns {Promise<{ added: number, movedFrom: { studentId: number, fromClazzName: string }[], notFound: string[], enrolled: number }>}
 */
export function addClazzStudents(id, { usernames, studentIds }) {
  const body = Array.isArray(usernames) ? { usernames } : { studentIds }
  return http.post(`/system-admin/clazzes/${id}/students`, body)
}

/** 把学生移出班级（不删除其选课记录）。 */
export function removeClazzStudent(id, studentId) {
  return http.delete(`/system-admin/clazzes/${id}/students/${studentId}`)
}

/**
 * 班级关联课程：为班级内全部学生补选课。
 * @param {number} id
 * @param {number} courseId
 * @returns {Promise<{ enrolled: number }>} enrolled 为新增的选课行数
 */
export function addClazzCourse(id, courseId) {
  return http.post(`/system-admin/clazzes/${id}/courses`, { courseId })
}

/** 解除班级与课程的关联（不删除已有选课记录）。 */
export function removeClazzCourse(id, courseId) {
  return http.delete(`/system-admin/clazzes/${id}/courses/${courseId}`)
}

// ───────── 系统配置 / 数据维护（A4） ─────────

/**
 * 系统配置分组列表（白名单 key，按分组排列）。
 * secret 项的 value / defaultValue 恒为 null，只返回 configured 与 masked。
 * @returns {Promise<{ group: string, items: object[] }[]>}
 */
export function listConfigs() {
  return http.get('/system-admin/configs')
}

/**
 * 覆盖某个配置项（立即生效，并记入审计）。secret 传明文，服务端加密存库。
 * @param {string} key 白名单内的配置 key，如 wenjin.ai.model
 * @param {string|number|boolean} value
 * @returns {Promise<object>} 更新后的单个配置项
 */
export function updateConfig(key, value) {
  return http.put(`/system-admin/configs/${encodeURIComponent(key)}`, { value })
}

/**
 * 恢复默认（删除覆盖，回到启动时的 yml / 环境变量取值）。
 * @param {string} key
 * @returns {Promise<object>} 恢复后的单个配置项
 */
export function resetConfig(key) {
  return http.delete(`/system-admin/configs/${encodeURIComponent(key)}`)
}

/**
 * 用当前生效配置发一个极小请求，检测 AI 连通性（不回显 key）。
 * @param {'text'|'vision'} target
 * @returns {Promise<{ ok: boolean, latencyMs: number, model: string, error?: string }>}
 */
export function testAiConnection(target) {
  return http.post('/system-admin/configs/ai-test', { target })
}

/**
 * 清空某门课程的学生学习数据（不删选课、图谱、题库、账号）。
 * confirmText 必须与课程名完全一致；生产环境默认 403。
 * @param {number} courseId
 * @param {string} confirmText
 * @returns {Promise<{ deleted: Record<string, number> }>} 每张表删除的行数
 */
export function resetLearningData(courseId, confirmText) {
  return http.post('/system-admin/data-reset', { courseId, confirmText })
}
