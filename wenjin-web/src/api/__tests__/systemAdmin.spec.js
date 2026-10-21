import { beforeEach, describe, expect, it, vi } from 'vitest'

const { http } = vi.hoisted(() => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn()
  }
}))

vi.mock('../http.js', () => ({ http }))

import * as systemAdmin from '../systemAdmin.js'

describe('systemAdmin API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('exports exactly the user-management and audit/monitor operations', () => {
    expect(Object.keys(systemAdmin).sort()).toEqual([
      'changeUserRole',
      'createUser',
      'downloadImportTemplate',
      'getOverview',
      'importUsers',
      'listAuditActions',
      'listAuditLogs',
      'listLoginLogs',
      'listUsers',
      'resetUserPassword',
      'setUserStatus',
      'updateUser'
    ])
  })

  it('listUsers sends only non-empty filters and forwards axios config', () => {
    const signal = new AbortController().signal
    systemAdmin.listUsers({ keyword: '张', role: 1, status: 0, page: 2, size: 20 }, { signal })
    systemAdmin.listUsers({ keyword: '', role: '', status: undefined, page: 1, size: 20 })

    expect(http.get.mock.calls).toEqual([
      ['/system-admin/users', { signal, params: { keyword: '张', role: 1, status: 0, page: 2, size: 20 } }],
      ['/system-admin/users', { params: { page: 1, size: 20 } }]
    ])
  })

  it('maps create / update / status / role to the contract paths and bodies', () => {
    systemAdmin.createUser({ username: 'alice', password: 'password-1', realName: 'Alice', role: 1, extra: 'ignored' })
    systemAdmin.updateUser(7, { realName: '新名字', role: 3 })
    systemAdmin.setUserStatus(7, 0)
    systemAdmin.changeUserRole(7, 2)

    expect(http.post.mock.calls).toEqual([
      ['/system-admin/users', { username: 'alice', password: 'password-1', realName: 'Alice', role: 1 }]
    ])
    expect(http.put.mock.calls).toEqual([['/system-admin/users/7', { realName: '新名字' }]])
    expect(http.patch.mock.calls).toEqual([
      ['/system-admin/users/7/status', { status: 0 }],
      ['/system-admin/users/7/role', { role: 2 }]
    ])
  })

  it('resetUserPassword sends newPassword only when given (empty body → server generates)', () => {
    systemAdmin.resetUserPassword(9, 'brand-new-pw')
    systemAdmin.resetUserPassword(9)
    systemAdmin.resetUserPassword(9, '')

    expect(http.post.mock.calls).toEqual([
      ['/system-admin/users/9/reset-password', { newPassword: 'brand-new-pw' }],
      ['/system-admin/users/9/reset-password', {}],
      ['/system-admin/users/9/reset-password', {}]
    ])
  })

  it('importUsers uploads the file as multipart form field "file"', () => {
    const file = new File(['x'], 'users.xlsx')
    systemAdmin.importUsers(file)

    const [path, body, config] = http.post.mock.calls[0]
    expect(path).toBe('/system-admin/users/import')
    expect(body).toBeInstanceOf(FormData)
    expect(Array.from(body.entries())).toEqual([['file', file]])
    expect(config.headers['Content-Type']).toBe('multipart/form-data')
  })

  it('downloadImportTemplate requests a blob and returns it untouched', async () => {
    const blob = new Blob(['xlsx-bytes'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    http.get.mockResolvedValueOnce(blob)

    await expect(systemAdmin.downloadImportTemplate()).resolves.toBe(blob)
    expect(http.get).toHaveBeenCalledWith('/system-admin/users/import-template', { responseType: 'blob' })
  })

  it('downloadImportTemplate turns a 2xx JSON body into an Error with the backend message', async () => {
    http.get.mockResolvedValueOnce(
      new Blob([JSON.stringify({ code: 500, message: '模板生成失败', data: null })], { type: 'application/json' })
    )

    await expect(systemAdmin.downloadImportTemplate()).rejects.toThrow('模板生成失败')
  })

  it('listAuditLogs / listLoginLogs send only non-empty filters (success 0 is kept) and forward config', () => {
    const signal = new AbortController().signal
    systemAdmin.listAuditLogs(
      { operator: 'root', action: 'USER_CREATE', success: 0, from: '2026-10-01', to: '', page: 2, size: 20 },
      { signal }
    )
    systemAdmin.listAuditLogs({ operator: '', action: '', success: '', page: 1, size: 20 })
    systemAdmin.listLoginLogs({ username: 'stu', success: 1, from: '2026-10-01', to: '2026-10-07', page: 3, size: 20 })

    expect(http.get.mock.calls).toEqual([
      ['/system-admin/audit-logs', { signal, params: { operator: 'root', action: 'USER_CREATE', success: 0, from: '2026-10-01', page: 2, size: 20 } }],
      ['/system-admin/audit-logs', { params: { page: 1, size: 20 } }],
      ['/system-admin/login-logs', { params: { username: 'stu', success: 1, from: '2026-10-01', to: '2026-10-07', page: 3, size: 20 } }]
    ])
  })

  it('listAuditActions and getOverview hit the contract paths', () => {
    const signal = new AbortController().signal
    systemAdmin.listAuditActions({ signal })
    systemAdmin.getOverview({ days: 14 }, { signal })
    systemAdmin.getOverview()

    expect(http.get.mock.calls).toEqual([
      ['/system-admin/audit-logs/actions', { signal }],
      ['/system-admin/overview', { signal, params: { days: 14 } }],
      ['/system-admin/overview', { params: {} }]
    ])
  })
})
