/**
 * 审计日志 / 登录日志的中文展示映射（后端只存英文编码，前端负责翻译；未知编码原样显示）。
 * 编码清单与后端 @Audited(action=...) 标注一一对应。
 */

/** 操作类型 → 中文名（顺序即「操作日志」筛选下拉的兜底顺序）。 */
export const AUDIT_ACTION_LABELS = {
  USER_CREATE: '新建用户',
  USER_UPDATE: '修改用户信息',
  USER_STATUS: '启用 / 禁用用户',
  USER_RESET_PASSWORD: '重置用户密码',
  USER_IMPORT: '批量导入用户',
  USER_ROLE: '变更用户角色',
  COURSE_CREATE: '新建课程',
  COURSE_DELETE: '删除课程',
  COURSE_RESTORE: '恢复课程',
  COURSE_STATUS: '发布 / 下架课程',
  COURSE_ADMIN_CREATE: '管理员新建课程',
  COURSE_TRANSFER: '转让课程教师',
  CLAZZ_CREATE: '新建班级',
  CLAZZ_UPDATE: '修改班级',
  CLAZZ_DELETE: '删除班级',
  CLAZZ_STUDENT_ADD: '添加班级学生',
  CLAZZ_STUDENT_REMOVE: '移除班级学生',
  CLAZZ_COURSE_ADD: '班级关联课程',
  CLAZZ_COURSE_REMOVE: '班级解除课程',
  GRAPH_IMPORT: '导入知识图谱',
  GRAPH_EXTRACT_COMMIT: '提交图谱抽取结果',
  GRAPH_NODE_CREATE: '新增知识点',
  GRAPH_NODE_UPDATE: '修改知识点',
  GRAPH_NODE_DELETE: '删除知识点',
  GRAPH_EDGE_CREATE: '新增关系边',
  GRAPH_EDGE_ACCEPT: '采纳关系边',
  GRAPH_EDGE_REJECT: '驳回关系边',
  QUESTION_REVIEW: '审核题目',
  QUESTION_REVIEW_ALL: '批量审核题目',
  QUESTION_IMPORT: '导入题目',
  QUESTION_GENERATE: 'AI 生成题目',
  RAG_MAPPING_APPROVE: '通过语料映射',
  RAG_MAPPING_REJECT: '驳回语料映射',
  RAG_MAPPING_BATCH_APPROVE: '批量通过语料映射',
  CONFIG_UPDATE: '修改配置',
  CONFIG_RESET: '恢复默认配置',
  AI_TEST: '测试 AI 连接',
  DATA_RESET: '清空学习数据'
}

/** 审计对象类型 → 中文名。 */
export const AUDIT_TARGET_LABELS = {
  USER: '用户',
  COURSE: '课程',
  CLAZZ: '班级',
  GRAPH: '图谱',
  QUESTION: '题目',
  RAG: '语料',
  CONFIG: '配置'
}

/** 登录失败原因 → 中文名。 */
export const LOGIN_FAIL_REASON_LABELS = {
  BAD_CREDENTIALS: '用户名或密码错误',
  DISABLED: '账号已禁用'
}

function lookup(table, code) {
  if (code == null || code === '') return '—'
  return Object.prototype.hasOwnProperty.call(table, code) ? table[code] : String(code)
}

/** 操作类型编码 → 中文；未知编码原样返回，空值给「—」。 */
export function auditActionLabel(action) {
  return lookup(AUDIT_ACTION_LABELS, action)
}

/** 对象类型编码 → 中文；未知原样返回，空值给「—」。 */
export function auditTargetLabel(targetType) {
  return lookup(AUDIT_TARGET_LABELS, targetType)
}

/** 登录失败原因编码 → 中文；未知原样返回，空值给「—」。 */
export function loginFailReasonLabel(reason) {
  return lookup(LOGIN_FAIL_REASON_LABELS, reason)
}
