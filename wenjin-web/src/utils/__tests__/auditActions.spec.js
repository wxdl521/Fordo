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
  'GRAPH_IMPORT', 'GRAPH_EXTRACT_COMMIT',
  'GRAPH_NODE_CREATE', 'GRAPH_NODE_UPDATE', 'GRAPH_NODE_DELETE',
  'GRAPH_EDGE_CREATE', 'GRAPH_EDGE_ACCEPT', 'GRAPH_EDGE_REJECT',
  'QUESTION_REVIEW', 'QUESTION_REVIEW_ALL', 'QUESTION_IMPORT', 'QUESTION_GENERATE',
  'RAG_MAPPING_APPROVE', 'RAG_MAPPING_REJECT', 'RAG_MAPPING_BATCH_APPROVE'
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
    expect(auditTargetLabel('WEIRD')).toBe('WEIRD')
    expect(auditTargetLabel(null)).toBe('—')

    expect(loginFailReasonLabel('BAD_CREDENTIALS')).toBe('用户名或密码错误')
    expect(loginFailReasonLabel('DISABLED')).toBe('账号已禁用')
    expect(loginFailReasonLabel('LOCKED')).toBe('LOCKED')
    expect(loginFailReasonLabel(null)).toBe('—')
  })
})
