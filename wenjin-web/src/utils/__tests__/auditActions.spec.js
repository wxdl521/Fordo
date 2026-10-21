import { describe, expect, it } from 'vitest'
import {
  AUDIT_ACTION_LABELS,
  auditActionLabel,
  auditTargetLabel,
  loginFailReasonLabel
} from '../auditActions.js'

// 方案标注清单里的全部 action（与后端 @Audited 一一对应）
const PLANNED_ACTIONS = [
  'USER_CREATE', 'USER_UPDATE', 'USER_STATUS', 'USER_RESET_PASSWORD', 'USER_IMPORT', 'USER_ROLE',
  'COURSE_CREATE', 'COURSE_DELETE', 'COURSE_RESTORE', 'COURSE_STATUS',
  'COURSE_ADMIN_CREATE', 'COURSE_TRANSFER',
  'CLAZZ_CREATE', 'CLAZZ_UPDATE', 'CLAZZ_DELETE',
  'CLAZZ_STUDENT_ADD', 'CLAZZ_STUDENT_REMOVE', 'CLAZZ_COURSE_ADD', 'CLAZZ_COURSE_REMOVE',
  'GRAPH_IMPORT', 'GRAPH_EXTRACT_COMMIT',
  'GRAPH_NODE_CREATE', 'GRAPH_NODE_UPDATE', 'GRAPH_NODE_DELETE',
  'GRAPH_EDGE_CREATE', 'GRAPH_EDGE_ACCEPT', 'GRAPH_EDGE_REJECT',
  'QUESTION_REVIEW', 'QUESTION_REVIEW_ALL', 'QUESTION_IMPORT', 'QUESTION_GENERATE',
  'RAG_MAPPING_APPROVE', 'RAG_MAPPING_REJECT', 'RAG_MAPPING_BATCH_APPROVE',
  'CONFIG_UPDATE', 'CONFIG_RESET', 'AI_TEST', 'DATA_RESET'
]

describe('auditActions', () => {
  it('maps every planned action to a non-empty Chinese label', () => {
    for (const action of PLANNED_ACTIONS) {
      const label = auditActionLabel(action)
      expect(label, action).not.toBe(action)
      expect(label, action).toMatch(/[一-龥]/)
    }
    expect(Object.keys(AUDIT_ACTION_LABELS).sort()).toEqual([...PLANNED_ACTIONS].sort())
  })

  it('translates the headline actions', () => {
    expect(auditActionLabel('USER_CREATE')).toBe('新建用户')
    expect(auditActionLabel('USER_RESET_PASSWORD')).toBe('重置用户密码')
    expect(auditActionLabel('COURSE_DELETE')).toBe('删除课程')
    expect(auditActionLabel('COURSE_TRANSFER')).toBe('转让课程教师')
    expect(auditActionLabel('CLAZZ_STUDENT_ADD')).toBe('添加班级学生')
    expect(auditActionLabel('CLAZZ_COURSE_REMOVE')).toBe('班级解除课程')
  })

  it('translates the A4 config / maintenance actions and the CONFIG target type', () => {
    expect(auditActionLabel('CONFIG_UPDATE')).toBe('修改配置')
    expect(auditActionLabel('CONFIG_RESET')).toBe('恢复默认配置')
    expect(auditActionLabel('AI_TEST')).toBe('测试 AI 连接')
    expect(auditActionLabel('DATA_RESET')).toBe('清空学习数据')
    expect(auditTargetLabel('CONFIG')).toBe('配置')
  })

  it('keeps the A3 actions in a stable order right after the course actions', () => {
    const keys = Object.keys(AUDIT_ACTION_LABELS)
    const from = keys.indexOf('COURSE_STATUS')
    expect(keys.slice(from + 1, from + 10)).toEqual([
      'COURSE_ADMIN_CREATE', 'COURSE_TRANSFER',
      'CLAZZ_CREATE', 'CLAZZ_UPDATE', 'CLAZZ_DELETE',
      'CLAZZ_STUDENT_ADD', 'CLAZZ_STUDENT_REMOVE', 'CLAZZ_COURSE_ADD', 'CLAZZ_COURSE_REMOVE'
    ])
  })

  it('shows unknown actions as-is and blanks as a dash (no prototype leaks)', () => {
    expect(auditActionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(auditActionLabel('constructor')).toBe('constructor')
    expect(auditActionLabel('')).toBe('—')
    expect(auditActionLabel(null)).toBe('—')
  })

  it('maps target types and login failure reasons', () => {
    expect(auditTargetLabel('USER')).toBe('用户')
    expect(auditTargetLabel('COURSE')).toBe('课程')
    expect(auditTargetLabel('CLAZZ')).toBe('班级')
    expect(auditTargetLabel('WEIRD')).toBe('WEIRD')
    expect(auditTargetLabel(null)).toBe('—')

    expect(loginFailReasonLabel('BAD_CREDENTIALS')).toBe('用户名或密码错误')
    expect(loginFailReasonLabel('DISABLED')).toBe('账号已禁用')
    expect(loginFailReasonLabel('LOCKED')).toBe('LOCKED')
    expect(loginFailReasonLabel(null)).toBe('—')
  })
})
