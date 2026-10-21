import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listAuditLogs: vi.fn(),
  listAuditActions: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)

import AdminAuditLogs from '../AdminAuditLogs.vue'
import { AUDIT_ACTION_LABELS } from '../../utils/auditActions.js'

const CREATE_USER = {
  id: 101,
  operatorId: 9,
  operatorName: 'root',
  operatorRole: 3,
  action: 'USER_CREATE',
  targetType: 'USER',
  targetId: '42',
  httpMethod: 'POST',
  path: '/api/system-admin/users',
  httpStatus: 200,
  success: true,
  ip: '10.0.0.8',
  requestId: 'r-1',
  createdAt: '2026-10-15T09:30:00'
}
const FAILED_DELETE = {
  id: 102,
  operatorId: 8,
  operatorName: 'teacher1',
  operatorRole: 1,
  action: 'COURSE_DELETE',
  targetType: 'COURSE',
  targetId: '7',
  httpMethod: 'DELETE',
  path: '/api/teacher/courses/7',
  httpStatus: 403,
  success: false,
  ip: '10.0.0.9',
  requestId: 'r-2',
  createdAt: '2026-10-15T10:05:00'
}
const ANONYMOUS_UNKNOWN = {
  id: 103,
  operatorId: null,
  operatorName: null,
  operatorRole: null,
  action: 'BRAND_NEW_ACTION',
  targetType: null,
  targetId: null,
  httpMethod: 'POST',
  path: '/api/x',
  httpStatus: 500,
  success: false,
  ip: null,
  requestId: null,
  createdAt: '2026-10-15T11:00:00'
}
const ROWS = [CREATE_USER, FAILED_DELETE, ANONYMOUS_UNKNOWN]

function pageOf(records = ROWS, total = records.length) {
  return { records, total, page: 1, size: 20 }
}

async function mountPage() {
  const wrapper = mount(AdminAuditLogs)
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminAuditLogs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listAuditLogs.mockResolvedValue(pageOf())
    api.listAuditActions.mockResolvedValue(['COURSE_DELETE', 'USER_CREATE', 'ZZ_UNKNOWN', 'AA_UNKNOWN'])
  })

  it('loads page 1 on mount and renders each record with Chinese action names', async () => {
    const wrapper = await mountPage()

    expect(api.listAuditLogs).toHaveBeenCalledTimes(1)
    expect(api.listAuditLogs).toHaveBeenCalledWith({ page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    expect(tid(wrapper, 'admin-audit-table').findAll('tbody tr')).toHaveLength(3)
    const row = tid(wrapper, 'admin-audit-row-101').text()
    expect(row).toContain('2026-10-15 09:30')
    expect(row).toContain('root')
    expect(row).toContain('管理员')
    expect(row).toContain('新建用户')
    expect(row).toContain('用户 #42')
    expect(row).toContain('POST /api/system-admin/users')
    expect(row).toContain('200')
    expect(row).toContain('10.0.0.8')
    expect(tid(wrapper, 'admin-audit-action-101').attributes('title')).toBe('USER_CREATE')
    expect(tid(wrapper, 'admin-audit-total').text()).toBe('共 3 条')
    wrapper.unmount()
  })

  it('tags success / failure, shows the failure status code and falls back for anonymous / unknown data', async () => {
    const wrapper = await mountPage()

    expect(tid(wrapper, 'admin-audit-result-101').text()).toBe('成功')
    expect(tid(wrapper, 'admin-audit-result-102').text()).toBe('失败')
    expect(tid(wrapper, 'admin-audit-row-102').text()).toContain('删除课程')
    expect(tid(wrapper, 'admin-audit-row-102').text()).toContain('教师')
    expect(tid(wrapper, 'admin-audit-row-102').text()).toContain('课程 #7')

    const anon = tid(wrapper, 'admin-audit-row-103').text()
    expect(anon).toContain('匿名')
    expect(anon).toContain('BRAND_NEW_ACTION') // 未知动作原样显示
    expect(tid(wrapper, 'admin-audit-result-103').text()).toBe('失败')
    wrapper.unmount()
  })

  it('builds the action dropdown from the server list: known actions first (Chinese), unknown ones after', async () => {
    const wrapper = await mountPage()
    expect(api.listAuditActions).toHaveBeenCalledTimes(1)

    const options = tid(wrapper, 'admin-audit-action').findAll('option')
    expect(options.map((o) => o.text())).toEqual(['全部操作', '新建用户', '删除课程', 'AA_UNKNOWN', 'ZZ_UNKNOWN'])
    expect(options.map((o) => o.element.value)).toEqual(['', 'USER_CREATE', 'COURSE_DELETE', 'AA_UNKNOWN', 'ZZ_UNKNOWN'])
    wrapper.unmount()
  })

  it('falls back to the full known action list when the actions request fails', async () => {
    api.listAuditActions.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage()

    const options = tid(wrapper, 'admin-audit-action').findAll('option')
    expect(options).toHaveLength(1 + Object.keys(AUDIT_ACTION_LABELS).length)
    expect(tid(wrapper, 'admin-audit-table').exists()).toBe(true) // 列表不受影响
    wrapper.unmount()
  })

  it('search sends operator / action / success (typed number) / date range and resets to page 1', async () => {
    const wrapper = await mountPage()

    await tid(wrapper, 'admin-audit-operator').setValue('  root ')
    await tid(wrapper, 'admin-audit-from').setValue('2026-10-01')
    await tid(wrapper, 'admin-audit-to').setValue('2026-10-15')
    await tid(wrapper, 'admin-audit-success').setValue('0')
    await flushPromises()
    api.listAuditLogs.mockClear()
    await tid(wrapper, 'admin-audit-action').setValue('USER_CREATE')
    await flushPromises()

    expect(api.listAuditLogs).toHaveBeenCalledTimes(1)
    expect(api.listAuditLogs).toHaveBeenLastCalledWith(
      { operator: 'root', action: 'USER_CREATE', success: 0, from: '2026-10-01', to: '2026-10-15', page: 1, size: 20 },
      expect.anything()
    )
    wrapper.unmount()
  })

  it('omits empty filters; Enter in the operator box also searches', async () => {
    const wrapper = await mountPage()
    await tid(wrapper, 'admin-audit-operator').setValue('alice')
    api.listAuditLogs.mockClear()
    await tid(wrapper, 'admin-audit-operator').trigger('keydown.enter')
    await flushPromises()

    expect(api.listAuditLogs).toHaveBeenLastCalledWith({ operator: 'alice', page: 1, size: 20 }, expect.anything())
    wrapper.unmount()
  })

  it('rejects an inverted date range without calling the API', async () => {
    const wrapper = await mountPage()
    await tid(wrapper, 'admin-audit-from').setValue('2026-10-20')
    await tid(wrapper, 'admin-audit-to').setValue('2026-10-01')
    api.listAuditLogs.mockClear()
    await tid(wrapper, 'admin-audit-search').trigger('click')
    await flushPromises()

    expect(api.listAuditLogs).not.toHaveBeenCalled()
    expect(tid(wrapper, 'admin-audit-range-error').text()).toBe('起始日期不能晚于结束日期')

    // 改正后再搜索：提示消失，请求发出
    await tid(wrapper, 'admin-audit-to').setValue('2026-10-25')
    await tid(wrapper, 'admin-audit-search').trigger('click')
    await flushPromises()
    expect(has(wrapper, 'admin-audit-range-error')).toBe(false)
    expect(api.listAuditLogs).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('paginates with the applied filters, not half-typed ones', async () => {
    api.listAuditLogs.mockResolvedValue(pageOf(ROWS, 45))
    const wrapper = await mountPage()
    expect(tid(wrapper, 'admin-audit-page-info').text()).toBe('第 1 / 3 页')

    await tid(wrapper, 'admin-audit-operator').setValue('applied')
    await tid(wrapper, 'admin-audit-search').trigger('click')
    await flushPromises()
    await tid(wrapper, 'admin-audit-operator').setValue('未提交')
    api.listAuditLogs.mockClear()
    await tid(wrapper, 'admin-audit-next').trigger('click')
    await flushPromises()

    expect(api.listAuditLogs).toHaveBeenLastCalledWith({ operator: 'applied', page: 2, size: 20 }, expect.anything())
    expect(tid(wrapper, 'admin-audit-page-info').text()).toBe('第 2 / 3 页')
    wrapper.unmount()
  })

  it('reset clears every filter and reloads page 1', async () => {
    const wrapper = await mountPage()
    await tid(wrapper, 'admin-audit-operator').setValue('root')
    await tid(wrapper, 'admin-audit-from').setValue('2026-10-01')
    await tid(wrapper, 'admin-audit-search').trigger('click')
    await flushPromises()

    api.listAuditLogs.mockClear()
    await tid(wrapper, 'admin-audit-reset').trigger('click')
    await flushPromises()

    expect(api.listAuditLogs).toHaveBeenLastCalledWith({ page: 1, size: 20 }, expect.anything())
    expect(tid(wrapper, 'admin-audit-operator').element.value).toBe('')
    expect(tid(wrapper, 'admin-audit-from').element.value).toBe('')
    wrapper.unmount()
  })

  it('shows the empty state, and the backend message when loading fails', async () => {
    api.listAuditLogs.mockResolvedValueOnce(pageOf([], 0))
    const empty = await mountPage()
    expect(has(empty, 'admin-audit-empty')).toBe(true)
    expect(has(empty, 'admin-audit-pagination')).toBe(false)
    empty.unmount()

    api.listAuditLogs.mockRejectedValueOnce(new Error('无访问权限'))
    const failed = await mountPage()
    expect(tid(failed, 'admin-audit-error').text()).toBe('无访问权限')
    expect(has(failed, 'admin-audit-empty')).toBe(false)
    failed.unmount()
  })
})
