/** 用户角色码（与后端 com.wenjin.security.UserRole 一致）：1=教师 2=学生 3=管理员 */
export const ROLE_TEACHER = 1
export const ROLE_STUDENT = 2
export const ROLE_ADMIN = 3

/**
 * 角色码 → 中文名。
 * @param {number | null | undefined} role
 */
export function roleLabel(role) {
  if (role === ROLE_ADMIN) return '管理员'
  if (role === ROLE_TEACHER) return '教师'
  if (role === ROLE_STUDENT) return '学生'
  return '未知'
}
