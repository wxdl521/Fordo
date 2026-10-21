import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

const api = vi.hoisted(() => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  setUserStatus: vi.fn(),
  resetUserPassword: vi.fn(),
  changeUserRole: vi.fn(),
  downloadImportTemplate: vi.fn(),
  importUsers: vi.fn()
}))
const saveBlob = vi.hoisted(() => vi.fn())

vi.mock('../../api/systemAdmin.js', () => api)
vi.mock('../../utils/downloadBlob.js', () => ({ saveBlob }))

import AdminUsers from '../AdminUsers.vue'
import { useSessionStore } from '../../store/session.js'

const ADMIN = { id: 9, username: 'root', realName: '系统管理员', role: 3, status: 1, createdAt: '2026-10-01T08:00:00' }
const TEACHER = { id: 8, username: 'teacher1', realName: '李老师', role: 1, status: 1, createdAt: '2026-10-02T09:30:00' }
const STUDENT = { id: 7, username: 'stu1', realName: '王同学', role: 2, status: 0, createdAt: '2026-10-03T10:45:00' }
const ROWS = [ADMIN, TEACHER, STUDENT]

function pageOf(records = ROWS, total = records.length) {
  return { records, total, page: 1, size: 20 }
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

let confirmMock

async function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore().setSession({ token: 'admin-token', user: ADMIN })
  const wrapper = mount(AdminUsers, { global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminUsers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmMock)
    api.listUsers.mockResolvedValue(pageOf())
    api.setUserStatus.mockResolvedValue({})
    api.changeUserRole.mockResolvedValue({})
    api.createUser.mockResolvedValue({})
    api.updateUser.mockResolvedValue({})
    api.resetUserPassword.mockResolvedValue({ password: null })
    api.importUsers.mockResolvedValue({ created: 0, errors: [] })
    api.downloadImportTemplate.mockResolvedValue(new Blob(['xlsx']))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('list', () => {
    it('loads page 1 on mount and renders every row with role/status tags', async () => {
      const wrapper = await mountPage()

      expect(api.listUsers).toHaveBeenCalledTimes(1)
      expect(api.listUsers).toHaveBeenCalledWith({ page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) }))

      const table = tid(wrapper, 'admin-users-table')
      expect(table.findAll('tbody tr')).toHaveLength(3)
      expect(tid(wrapper, 'admin-user-row-8').text()).toContain('teacher1')
      expect(tid(wrapper, 'admin-user-row-8').text()).toContain('李老师')
      expect(tid(wrapper, 'admin-user-row-8').text()).toContain('2026-10-02 09:30')
      expect(tid(wrapper, 'admin-user-role-tag-9').text()).toBe('管理员')
      expect(tid(wrapper, 'admin-user-role-tag-8').text()).toBe('教师')
      expect(tid(wrapper, 'admin-user-role-tag-7').text()).toBe('学生')
      expect(tid(wrapper, 'admin-user-status-tag-8').text()).toBe('启用')
      expect(tid(wrapper, 'admin-user-status-tag-7').text()).toBe('禁用')
      expect(tid(wrapper, 'admin-users-total').text()).toBe('共 3 条')
      wrapper.unmount()
    })

    it('shows an empty state and the backend message on load failure', async () => {
      api.listUsers.mockResolvedValueOnce(pageOf([], 0))
      const wrapper = await mountPage()
      expect(has(wrapper, 'admin-users-empty')).toBe(true)
      expect(has(wrapper, 'admin-users-pagination')).toBe(false)
      wrapper.unmount()

      api.listUsers.mockRejectedValueOnce(new Error('无访问权限'))
      const failed = await mountPage()
      expect(tid(failed, 'admin-users-error').text()).toBe('无访问权限')
      failed.unmount()
    })

    it('search sends keyword / role / status as typed numbers and resets to page 1', async () => {
      const wrapper = await mountPage()
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-users-keyword').setValue('  张 ')
      await tid(wrapper, 'admin-users-role').setValue('1')
      await tid(wrapper, 'admin-users-status').setValue('0')
      api.listUsers.mockClear()
      await tid(wrapper, 'admin-users-search').trigger('click')
      await flushPromises()

      expect(api.listUsers).toHaveBeenCalledTimes(1)
      expect(api.listUsers).toHaveBeenLastCalledWith(
        { keyword: '张', role: 1, status: 0, page: 1, size: 20 },
        expect.anything()
      )
      wrapper.unmount()
    })

    it('omits empty filters, and Enter in the keyword box also searches', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-users-keyword').setValue('wang')
      api.listUsers.mockClear()
      await tid(wrapper, 'admin-users-keyword').trigger('keydown.enter')
      await flushPromises()

      expect(api.listUsers).toHaveBeenLastCalledWith({ keyword: 'wang', page: 1, size: 20 }, expect.anything())
      wrapper.unmount()
    })

    it('paginates with the applied filters, not half-typed ones', async () => {
      api.listUsers.mockResolvedValue(pageOf(ROWS, 45))
      const wrapper = await mountPage()
      expect(tid(wrapper, 'admin-users-page-info').text()).toBe('第 1 / 3 页')
      expect(tid(wrapper, 'admin-users-prev').attributes('disabled')).toBeDefined()

      await tid(wrapper, 'admin-users-role').setValue('2')
      await tid(wrapper, 'admin-users-search').trigger('click')
      await flushPromises()
      // 之后输入了关键字但没点搜索：翻页不应带上它
      await tid(wrapper, 'admin-users-keyword').setValue('未提交')
      api.listUsers.mockClear()
      await tid(wrapper, 'admin-users-next').trigger('click')
      await flushPromises()

      expect(api.listUsers).toHaveBeenCalledTimes(1)
      expect(api.listUsers).toHaveBeenLastCalledWith({ role: 2, page: 2, size: 20 }, expect.anything())
      expect(tid(wrapper, 'admin-users-page-info').text()).toBe('第 2 / 3 页')

      await tid(wrapper, 'admin-users-prev').trigger('click')
      await flushPromises()
      expect(api.listUsers).toHaveBeenLastCalledWith({ role: 2, page: 1, size: 20 }, expect.anything())

      await tid(wrapper, 'admin-users-next').trigger('click')
      await tid(wrapper, 'admin-users-next').trigger('click')
      await flushPromises()
      expect(tid(wrapper, 'admin-users-next').attributes('disabled')).toBeDefined()
      wrapper.unmount()
    })

    it('drops a late response from an earlier search', async () => {
      const first = deferred()
      api.listUsers.mockReturnValueOnce(first.promise)
      const pinia = createPinia()
      setActivePinia(pinia)
      useSessionStore().setSession({ token: 't', user: ADMIN })
      const wrapper = mount(AdminUsers, { global: { plugins: [pinia] } })

      api.listUsers.mockResolvedValueOnce(pageOf([TEACHER]))
      await tid(wrapper, 'admin-users-search').trigger('click')
      await flushPromises()
      expect(wrapper.findAll('tbody tr')).toHaveLength(1)

      first.resolve(pageOf(ROWS))
      await flushPromises()
      expect(wrapper.findAll('tbody tr')).toHaveLength(1)
      wrapper.unmount()
    })
  })

  describe('row actions', () => {
    it('disables a user only after confirmation, then reloads the list', async () => {
      const wrapper = await mountPage()
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-user-toggle-8').trigger('click')
      await flushPromises()

      expect(confirmMock).toHaveBeenCalledTimes(1)
      expect(confirmMock.mock.calls[0][0]).toContain('teacher1')
      expect(api.setUserStatus).toHaveBeenCalledWith(8, 0)
      expect(api.listUsers).toHaveBeenCalledTimes(1)
      expect(tid(wrapper, 'admin-users-notice').text()).toContain('已禁用')
      wrapper.unmount()
    })

    it('does nothing when the confirmation is declined', async () => {
      confirmMock.mockReturnValue(false)
      const wrapper = await mountPage()
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-user-toggle-8').trigger('click')
      await flushPromises()

      expect(api.setUserStatus).not.toHaveBeenCalled()
      expect(api.listUsers).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('enables a disabled user without a confirm dialog', async () => {
      const wrapper = await mountPage()
      expect(tid(wrapper, 'admin-user-toggle-7').text()).toBe('启用')

      await tid(wrapper, 'admin-user-toggle-7').trigger('click')
      await flushPromises()

      expect(confirmMock).not.toHaveBeenCalled()
      expect(api.setUserStatus).toHaveBeenCalledWith(7, 1)
      expect(tid(wrapper, 'admin-users-notice').text()).toContain('已启用')
      wrapper.unmount()
    })

    it('does not let the signed-in admin disable themselves', async () => {
      const wrapper = await mountPage()
      const selfToggle = tid(wrapper, 'admin-user-toggle-9')
      expect(selfToggle.attributes('disabled')).toBeDefined()
      expect(tid(wrapper, 'admin-user-row-9').text()).toContain('你')

      await selfToggle.trigger('click')
      expect(api.setUserStatus).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('shows the backend message when an action is rejected', async () => {
      api.setUserStatus.mockRejectedValueOnce(new Error('禁止禁用最后一个启用管理员'))
      const wrapper = await mountPage()
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-user-toggle-8').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-users-action-error').text()).toBe('禁止禁用最后一个启用管理员')
      expect(has(wrapper, 'admin-users-notice')).toBe(false)
      expect(api.listUsers).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('changes teacher ⇄ student after confirmation; admin rows have no role button', async () => {
      const wrapper = await mountPage()
      expect(has(wrapper, 'admin-user-role-9')).toBe(false)
      expect(tid(wrapper, 'admin-user-role-8').text()).toBe('改为学生')
      expect(tid(wrapper, 'admin-user-role-7').text()).toBe('改为教师')

      await tid(wrapper, 'admin-user-role-8').trigger('click')
      await flushPromises()
      expect(confirmMock).toHaveBeenCalledTimes(1)
      expect(api.changeUserRole).toHaveBeenLastCalledWith(8, 2)

      await tid(wrapper, 'admin-user-role-7').trigger('click')
      await flushPromises()
      expect(api.changeUserRole).toHaveBeenLastCalledWith(7, 1)
      wrapper.unmount()
    })
  })

  describe('create user', () => {
    async function openCreate(wrapper) {
      await tid(wrapper, 'admin-user-create').trigger('click')
      expect(has(wrapper, 'admin-user-create-modal')).toBe(true)
    }

    async function fillCreate(wrapper, { username, realName, password, role }) {
      await tid(wrapper, 'admin-user-create-username').setValue(username)
      await tid(wrapper, 'admin-user-create-realname').setValue(realName)
      await tid(wrapper, 'admin-user-create-password').setValue(password)
      if (role != null) await tid(wrapper, 'admin-user-create-role').setValue(String(role))
    }

    it('submits trimmed values with the chosen role, closes and reloads', async () => {
      const wrapper = await mountPage()
      await openCreate(wrapper)
      await fillCreate(wrapper, { username: '  newteacher ', realName: ' 赵老师 ', password: 'password-123', role: 1 })
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-user-create-submit').trigger('click')
      await flushPromises()

      expect(api.createUser).toHaveBeenCalledWith({
        username: 'newteacher',
        realName: '赵老师',
        password: 'password-123',
        role: 1
      })
      expect(has(wrapper, 'admin-user-create-modal')).toBe(false)
      expect(api.listUsers).toHaveBeenCalledTimes(1)
      expect(tid(wrapper, 'admin-users-notice').text()).toContain('newteacher')
      wrapper.unmount()
    })

    it('defaults the role to student', async () => {
      const wrapper = await mountPage()
      await openCreate(wrapper)
      await fillCreate(wrapper, { username: 'student99', realName: '学生九', password: 'password-123' })
      await tid(wrapper, 'admin-user-create-submit').trigger('click')
      await flushPromises()

      expect(api.createUser).toHaveBeenCalledWith(expect.objectContaining({ role: 2 }))
      wrapper.unmount()
    })

    it.each([
      ['username too short', { username: 'ab', realName: '甲', password: 'password-123' }, '用户名'],
      ['blank name', { username: 'abcd', realName: '   ', password: 'password-123' }, '姓名'],
      ['password too short', { username: 'abcd', realName: '甲', password: 'short' }, '密码']
    ])('validates before calling the API: %s', async (_label, form, hint) => {
      const wrapper = await mountPage()
      await openCreate(wrapper)
      await fillCreate(wrapper, form)

      await tid(wrapper, 'admin-user-create-submit').trigger('click')
      await flushPromises()

      expect(api.createUser).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-user-create-error').text()).toContain(hint)
      expect(has(wrapper, 'admin-user-create-modal')).toBe(true)
      wrapper.unmount()
    })

    it('keeps the modal open and shows the backend message (e.g. duplicate username)', async () => {
      api.createUser.mockRejectedValueOnce(new Error('用户名已存在'))
      const wrapper = await mountPage()
      await openCreate(wrapper)
      await fillCreate(wrapper, { username: 'teacher1', realName: '重名', password: 'password-123' })

      await tid(wrapper, 'admin-user-create-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-user-create-error').text()).toBe('用户名已存在')
      expect(has(wrapper, 'admin-user-create-modal')).toBe(true)
      wrapper.unmount()
    })

    it('Escape closes the modal and discards the typed password', async () => {
      const wrapper = await mountPage()
      await openCreate(wrapper)
      await fillCreate(wrapper, { username: 'abcd', realName: '甲', password: 'password-123' })

      // Esc 监听在 document 上（焦点所在按钮被 disabled 后弹窗内的 keydown 就收不到了）
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      await nextTick()
      expect(has(wrapper, 'admin-user-create-modal')).toBe(false)

      await openCreate(wrapper)
      expect(tid(wrapper, 'admin-user-create-password').element.value).toBe('')
      wrapper.unmount()
    })
  })

  describe('edit name', () => {
    it('prefills the current name and saves the trimmed one', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-edit-8').trigger('click')
      expect(tid(wrapper, 'admin-user-edit-realname').element.value).toBe('李老师')

      await tid(wrapper, 'admin-user-edit-realname').setValue('  李教授 ')
      api.listUsers.mockClear()
      await tid(wrapper, 'admin-user-edit-submit').trigger('click')
      await flushPromises()

      expect(api.updateUser).toHaveBeenCalledWith(8, { realName: '李教授' })
      expect(has(wrapper, 'admin-user-edit-modal')).toBe(false)
      expect(api.listUsers).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    })

    it('rejects an empty name locally', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-edit-8').trigger('click')
      await tid(wrapper, 'admin-user-edit-realname').setValue('  ')
      await tid(wrapper, 'admin-user-edit-submit').trigger('click')
      await flushPromises()

      expect(api.updateUser).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-user-edit-error').text()).toContain('姓名')
      wrapper.unmount()
    })
  })

  describe('reset password', () => {
    it('shows a server-generated password once, with a copy button', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
      api.resetUserPassword.mockResolvedValueOnce({ password: 'Xy7kQ2mP9aZ4' })
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-user-reset-8').trigger('click')
      await tid(wrapper, 'admin-user-reset-submit').trigger('click')
      await flushPromises()

      expect(api.resetUserPassword).toHaveBeenCalledWith(8, undefined)
      expect(tid(wrapper, 'admin-user-reset-generated').text()).toBe('Xy7kQ2mP9aZ4')
      expect(has(wrapper, 'admin-user-reset-submit')).toBe(false)

      await tid(wrapper, 'admin-user-reset-copy').trigger('click')
      await flushPromises()
      expect(writeText).toHaveBeenCalledWith('Xy7kQ2mP9aZ4')
      expect(tid(wrapper, 'admin-user-reset-copy-state').text()).toContain('已复制')

      // 关闭后明文消失，重新打开是干净的表单
      await tid(wrapper, 'admin-user-reset-done').trigger('click')
      expect(has(wrapper, 'admin-user-reset-modal')).toBe(false)
      expect(wrapper.html()).not.toContain('Xy7kQ2mP9aZ4')
      await tid(wrapper, 'admin-user-reset-8').trigger('click')
      expect(has(wrapper, 'admin-user-reset-generated')).toBe(false)
      expect(tid(wrapper, 'admin-user-reset-password').element.value).toBe('')
      wrapper.unmount()
    })

    it('uses the given password and closes without showing any plaintext', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-reset-7').trigger('click')
      await tid(wrapper, 'admin-user-reset-password').setValue('my-new-password')
      await tid(wrapper, 'admin-user-reset-submit').trigger('click')
      await flushPromises()

      expect(api.resetUserPassword).toHaveBeenCalledWith(7, 'my-new-password')
      expect(has(wrapper, 'admin-user-reset-modal')).toBe(false)
      expect(has(wrapper, 'admin-user-reset-generated')).toBe(false)
      expect(tid(wrapper, 'admin-users-notice').text()).toContain('stu1')
      wrapper.unmount()
    })

    it('rejects a too-short custom password locally', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-reset-7').trigger('click')
      await tid(wrapper, 'admin-user-reset-password').setValue('short')
      await tid(wrapper, 'admin-user-reset-submit').trigger('click')
      await flushPromises()

      expect(api.resetUserPassword).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-user-reset-error').text()).toContain('8–72')
      wrapper.unmount()
    })
  })

  describe('batch import', () => {
    function chooseFile(wrapper, file) {
      const input = tid(wrapper, 'admin-user-import-file')
      Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
      return input.trigger('change')
    }

    const xlsx = (name = 'users.xlsx', size) => {
      const file = new File(['PK'], name)
      if (size != null) Object.defineProperty(file, 'size', { value: size })
      return file
    }

    it('downloads the template through the blob helper', async () => {
      const blob = new Blob(['template'])
      api.downloadImportTemplate.mockResolvedValueOnce(blob)
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-import').trigger('click')

      await tid(wrapper, 'admin-user-import-template').trigger('click')
      await flushPromises()

      expect(api.downloadImportTemplate).toHaveBeenCalledTimes(1)
      expect(saveBlob).toHaveBeenCalledWith(blob, '用户导入模板.xlsx')
      wrapper.unmount()
    })

    it('shows the backend message when the template download fails', async () => {
      api.downloadImportTemplate.mockRejectedValueOnce(new Error('无访问权限'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-import').trigger('click')
      await tid(wrapper, 'admin-user-import-template').trigger('click')
      await flushPromises()

      expect(saveBlob).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-user-import-error').text()).toBe('无访问权限')
      wrapper.unmount()
    })

    it('keeps upload disabled until a valid .xlsx is chosen; rejects other types and big files', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-import').trigger('click')
      expect(tid(wrapper, 'admin-user-import-submit').attributes('disabled')).toBeDefined()

      await chooseFile(wrapper, xlsx('users.csv'))
      expect(tid(wrapper, 'admin-user-import-error').text()).toContain('.xlsx')
      expect(tid(wrapper, 'admin-user-import-submit').attributes('disabled')).toBeDefined()

      await chooseFile(wrapper, xlsx('big.xlsx', 3 * 1024 * 1024))
      expect(tid(wrapper, 'admin-user-import-error').text()).toContain('2MB')
      expect(tid(wrapper, 'admin-user-import-submit').attributes('disabled')).toBeDefined()

      await chooseFile(wrapper, xlsx())
      expect(has(wrapper, 'admin-user-import-error')).toBe(false)
      expect(tid(wrapper, 'admin-user-import-submit').attributes('disabled')).toBeUndefined()
      wrapper.unmount()
    })

    it('lists every error row and does not reload when the import was rejected', async () => {
      api.importUsers.mockResolvedValueOnce({
        created: 0,
        errors: [
          { row: 3, username: 'dup', message: '用户名已存在' },
          { row: 5, username: '', message: '角色非法' }
        ]
      })
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-import').trigger('click')
      const file = xlsx()
      await chooseFile(wrapper, file)
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-user-import-submit').trigger('click')
      await flushPromises()

      expect(api.importUsers).toHaveBeenCalledWith(file)
      expect(tid(wrapper, 'admin-user-import-failed').text()).toContain('2 处错误')
      expect(tid(wrapper, 'admin-user-import-failed').text()).toContain('没有导入任何用户')
      const rows = wrapper.findAll('[data-testid="admin-user-import-error-row"]')
      expect(rows).toHaveLength(2)
      expect(rows[0].text()).toContain('3')
      expect(rows[0].text()).toContain('dup')
      expect(rows[0].text()).toContain('用户名已存在')
      expect(rows[1].text()).toContain('角色非法')
      expect(has(wrapper, 'admin-user-import-success')).toBe(false)
      expect(api.listUsers).not.toHaveBeenCalled()
      // 弹窗保留，让管理员对照错误行修正
      expect(has(wrapper, 'admin-user-import-modal')).toBe(true)
      wrapper.unmount()
    })

    it('reports success, refreshes the list and clears the used file', async () => {
      api.importUsers.mockResolvedValueOnce({ created: 12, errors: [] })
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-import').trigger('click')
      await chooseFile(wrapper, xlsx())
      api.listUsers.mockClear()

      await tid(wrapper, 'admin-user-import-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-user-import-success').text()).toContain('12')
      expect(api.listUsers).toHaveBeenCalledTimes(1)
      expect(has(wrapper, 'admin-user-import-errors')).toBe(false)
      expect(tid(wrapper, 'admin-user-import-submit').attributes('disabled')).toBeDefined()
      wrapper.unmount()
    })

    it('shows the backend message when the upload itself fails', async () => {
      api.importUsers.mockRejectedValueOnce(new Error('文件超过 1000 行'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-user-import').trigger('click')
      await chooseFile(wrapper, xlsx())
      await tid(wrapper, 'admin-user-import-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-user-import-error').text()).toBe('文件超过 1000 行')
      expect(has(wrapper, 'admin-user-import-result')).toBe(false)
      wrapper.unmount()
    })
  })
})
