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

  it('exports exactly the user / audit / course / class / config operations', () => {
    expect(Object.keys(systemAdmin).sort()).toEqual([
      'addClazzCourse',
      'addClazzStudents',
      'archiveAdminCourse',
      'changeUserRole',
      'createAdminCourse',
      'createClazz',
      'createUser',
      'deleteClazz',
      'downloadImportTemplate',
      'getClazzDetail',
      'getOverview',
      'importUsers',
      'listAdminCourses',
      'listAuditActions',
      'listAuditLogs',
      'listClazzes',
      'listConfigs',
      'listLoginLogs',
      'listTeachers',
      'listUsers',
      'removeClazzCourse',
      'removeClazzStudent',
      'resetConfig',
      'resetLearningData',
      'resetUserPassword',
      'restoreAdminCourse',
      'setAdminCoursePublished',
      'setUserStatus',
      'testAiConnection',
      'transferCourseOwner',
      'updateClazz',
      'updateConfig',
      'updateUser'
    ])
  })

  describe('system config and data maintenance (A4)', () => {
    it('listConfigs GETs the config groups without params', () => {
      systemAdmin.listConfigs()

      expect(http.get.mock.calls).toEqual([['/system-admin/configs']])
    })

    it('updateConfig PUTs {value} to the key path (dots kept) and passes secret plaintext only in the body', () => {
      systemAdmin.updateConfig('wenjin.ai.model', 'deepseek-v4-pro')
      systemAdmin.updateConfig('wenjin.features.companion-llm-enabled', false)
      systemAdmin.updateConfig('wenjin.ai.api-key', 'sk-test-plain')

      expect(http.put.mock.calls).toEqual([
        ['/system-admin/configs/wenjin.ai.model', { value: 'deepseek-v4-pro' }],
        ['/system-admin/configs/wenjin.features.companion-llm-enabled', { value: false }],
        ['/system-admin/configs/wenjin.ai.api-key', { value: 'sk-test-plain' }]
      ])
    })

    it('resetConfig DELETEs the same key path', () => {
      systemAdmin.resetConfig('wenjin.ai.vision.base-url')

      expect(http.delete.mock.calls).toEqual([['/system-admin/configs/wenjin.ai.vision.base-url']])
    })

    it('testAiConnection POSTs the target to ai-test', () => {
      systemAdmin.testAiConnection('vision')

      expect(http.post.mock.calls).toEqual([['/system-admin/configs/ai-test', { target: 'vision' }]])
    })

    it('resetLearningData POSTs courseId and confirmText and drops anything else', () => {
      systemAdmin.resetLearningData(5, '软件工程')

      expect(http.post.mock.calls).toEqual([
        ['/system-admin/data-reset', { courseId: 5, confirmText: '软件工程' }]
      ])
    })
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

  describe('courses and classes (A3)', () => {
    it('listAdminCourses keeps archived=false / status=0 but drops empty filters', () => {
      const signal = new AbortController().signal
      systemAdmin.listAdminCourses({ keyword: '软件', status: 0, archived: false, teacherId: 4, page: 2, size: 20 }, { signal })
      systemAdmin.listAdminCourses({ keyword: '', status: '', archived: '', teacherId: '', page: 1, size: 20 })

      expect(http.get.mock.calls).toEqual([
        ['/system-admin/courses', { signal, params: { keyword: '软件', status: 0, archived: false, teacherId: 4, page: 2, size: 20 } }],
        ['/system-admin/courses', { params: { page: 1, size: 20 } }]
      ])
    })

    it('maps course create / transfer / publish / archive / restore / teachers to the contract', () => {
      const signal = new AbortController().signal
      systemAdmin.createAdminCourse({ name: '数据结构', teacherId: 3, extra: 'ignored' })
      systemAdmin.transferCourseOwner(8, 5)
      systemAdmin.setAdminCoursePublished(8, true)
      systemAdmin.setAdminCoursePublished(8, 0)
      systemAdmin.archiveAdminCourse(8)
      systemAdmin.restoreAdminCourse(8)
      systemAdmin.listTeachers({ signal })

      expect(http.post.mock.calls).toEqual([
        ['/system-admin/courses', { name: '数据结构', teacherId: 3 }],
        ['/system-admin/courses/8/archive'],
        ['/system-admin/courses/8/restore']
      ])
      expect(http.patch.mock.calls).toEqual([
        ['/system-admin/courses/8/owner', { teacherId: 5 }],
        ['/system-admin/courses/8/status', { published: true }],
        ['/system-admin/courses/8/status', { published: false }]
      ])
      expect(http.get.mock.calls).toEqual([['/system-admin/teachers', { signal }]])
    })

    it('maps class CRUD, detail and the student / course membership calls', () => {
      const signal = new AbortController().signal
      systemAdmin.listClazzes({ keyword: '2201', page: 1, size: 20 }, { signal })
      systemAdmin.getClazzDetail(6, { signal })
      systemAdmin.createClazz({ name: '软工2201', description: '示例', extra: 'ignored' })
      systemAdmin.updateClazz(6, { name: '软工2202', description: '' })
      systemAdmin.deleteClazz(6)
      systemAdmin.removeClazzStudent(6, 11)
      systemAdmin.addClazzCourse(6, 5)
      systemAdmin.removeClazzCourse(6, 5)

      expect(http.get.mock.calls).toEqual([
        ['/system-admin/clazzes', { signal, params: { keyword: '2201', page: 1, size: 20 } }],
        ['/system-admin/clazzes/6', { signal }]
      ])
      expect(http.post.mock.calls).toEqual([
        ['/system-admin/clazzes', { name: '软工2201', description: '示例' }],
        ['/system-admin/clazzes/6/courses', { courseId: 5 }]
      ])
      expect(http.put.mock.calls).toEqual([['/system-admin/clazzes/6', { name: '软工2202', description: '' }]])
      expect(http.delete.mock.calls).toEqual([
        ['/system-admin/clazzes/6'],
        ['/system-admin/clazzes/6/students/11'],
        ['/system-admin/clazzes/6/courses/5']
      ])
    })

    it('addClazzStudents sends usernames or studentIds (one of the two)', () => {
      systemAdmin.addClazzStudents(6, { usernames: ['a', 'b'] })
      systemAdmin.addClazzStudents(6, { studentIds: [1, 2] })

      expect(http.post.mock.calls).toEqual([
        ['/system-admin/clazzes/6/students', { usernames: ['a', 'b'] }],
        ['/system-admin/clazzes/6/students', { studentIds: [1, 2] }]
      ])
    })
  })
})
