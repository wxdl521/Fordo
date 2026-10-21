import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const api = vi.hoisted(() => ({
  listClazzes: vi.fn(),
  createClazz: vi.fn(),
  deleteClazz: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)

import AdminClazzes from '../AdminClazzes.vue'

const C1 = { id: 3, name: '软工2201', description: '软件工程 22 级 1 班', studentCount: 30, courseCount: 2, createdAt: '2026-10-01T08:00:00' }
const C2 = { id: 4, name: '软工2202', description: null, studentCount: 0, courseCount: 0, createdAt: '2026-10-02T09:30:00' }
const ROWS = [C1, C2]

function pageOf(records = ROWS, total = records.length) {
  return { records, total, page: 1, size: 20 }
}

let confirmMock
let router

async function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/clazzes', component: { template: '<div />' } },
      { path: '/admin/clazzes/:id', component: { template: '<div data-testid="detail-route" />' } }
    ]
  })
  await router.push('/admin/clazzes')
  await router.isReady()
  const wrapper = mount(AdminClazzes, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminClazzes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmMock)
    api.listClazzes.mockResolvedValue(pageOf())
    api.createClazz.mockResolvedValue(C1)
    api.deleteClazz.mockResolvedValue(null)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('list', () => {
    it('loads page 1 on mount and renders the counts and detail links', async () => {
      const wrapper = await mountPage()

      expect(api.listClazzes).toHaveBeenCalledTimes(1)
      expect(api.listClazzes).toHaveBeenCalledWith({ page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) }))

      expect(tid(wrapper, 'admin-clazzes-table').findAll('tbody tr')).toHaveLength(2)
      const row = tid(wrapper, 'admin-clazz-row-3').text()
      expect(row).toContain('软工2201')
      expect(row).toContain('软件工程 22 级 1 班')
      expect(row).toContain('30')
      expect(row).toContain('2026-10-01 08:00')
      expect(tid(wrapper, 'admin-clazz-link-3').attributes('href')).toBe('/admin/clazzes/3')
      // 没说明的班级给个破折号
      expect(tid(wrapper, 'admin-clazz-row-4').text()).toContain('—')
      expect(tid(wrapper, 'admin-clazzes-total').text()).toBe('共 2 条')
      wrapper.unmount()
    })

    it('shows an empty state with a hint, and the backend message on load failure', async () => {
      api.listClazzes.mockResolvedValueOnce(pageOf([], 0))
      const wrapper = await mountPage()
      expect(tid(wrapper, 'admin-clazzes-empty').text()).toContain('新建班级')
      wrapper.unmount()

      api.listClazzes.mockRejectedValueOnce(new Error('无访问权限'))
      const failed = await mountPage()
      expect(tid(failed, 'admin-clazzes-error').text()).toBe('无访问权限')
      failed.unmount()
    })

    it('search sends the trimmed keyword; reset clears it', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazzes-keyword').setValue('  2201 ')
      api.listClazzes.mockClear()

      await tid(wrapper, 'admin-clazzes-search').trigger('click')
      await flushPromises()
      expect(api.listClazzes).toHaveBeenLastCalledWith({ keyword: '2201', page: 1, size: 20 }, expect.anything())

      await tid(wrapper, 'admin-clazzes-reset').trigger('click')
      await flushPromises()
      expect(api.listClazzes).toHaveBeenLastCalledWith({ page: 1, size: 20 }, expect.anything())
      expect(tid(wrapper, 'admin-clazzes-keyword').element.value).toBe('')
      wrapper.unmount()
    })

    it('"管理" opens the class detail route', async () => {
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-clazz-open-4').trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.path).toBe('/admin/clazzes/4')
      wrapper.unmount()
    })
  })

  describe('create', () => {
    it('requires a name and enforces the length limits', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-create').trigger('click')
      expect(has(wrapper, 'admin-clazz-create-modal')).toBe(true)

      await tid(wrapper, 'admin-clazz-create-submit').trigger('click')
      expect(tid(wrapper, 'admin-clazz-create-error').text()).toBe('请填写班级名称')

      await tid(wrapper, 'admin-clazz-create-name').setValue('x'.repeat(65))
      await tid(wrapper, 'admin-clazz-create-submit').trigger('click')
      expect(tid(wrapper, 'admin-clazz-create-error').text()).toContain('64')

      await tid(wrapper, 'admin-clazz-create-name').setValue('软工2203')
      await tid(wrapper, 'admin-clazz-create-description').setValue('y'.repeat(256))
      await tid(wrapper, 'admin-clazz-create-submit').trigger('click')
      expect(tid(wrapper, 'admin-clazz-create-error').text()).toContain('255')
      expect(api.createClazz).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('creates the class with trimmed fields, closes and reloads from page 1', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-create').trigger('click')
      await tid(wrapper, 'admin-clazz-create-name').setValue('  软工2203 ')
      await tid(wrapper, 'admin-clazz-create-description').setValue(' 新班 ')
      api.listClazzes.mockClear()

      await tid(wrapper, 'admin-clazz-create-submit').trigger('click')
      await flushPromises()

      expect(api.createClazz).toHaveBeenCalledWith({ name: '软工2203', description: '新班' })
      expect(has(wrapper, 'admin-clazz-create-modal')).toBe(false)
      expect(tid(wrapper, 'admin-clazzes-notice').text()).toContain('软工2203')
      expect(api.listClazzes).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    })

    it('keeps the dialog open with the backend message on a duplicate name', async () => {
      api.createClazz.mockRejectedValueOnce(new Error('班级名已存在'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-create').trigger('click')
      await tid(wrapper, 'admin-clazz-create-name').setValue('软工2201')

      await tid(wrapper, 'admin-clazz-create-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazz-create-error').text()).toBe('班级名已存在')
      expect(has(wrapper, 'admin-clazz-create-modal')).toBe(true)
      wrapper.unmount()
    })
  })

  describe('delete', () => {
    it('confirms with the "enrollments are kept" promise, then deletes and reloads', async () => {
      const wrapper = await mountPage()

      confirmMock.mockReturnValueOnce(false)
      await tid(wrapper, 'admin-clazz-delete-3').trigger('click')
      await flushPromises()
      expect(api.deleteClazz).not.toHaveBeenCalled()

      api.listClazzes.mockClear()
      await tid(wrapper, 'admin-clazz-delete-3').trigger('click')
      await flushPromises()

      expect(confirmMock).toHaveBeenLastCalledWith(expect.stringContaining('选课记录保留'))
      expect(confirmMock).toHaveBeenLastCalledWith(expect.stringContaining('软工2201'))
      expect(api.deleteClazz).toHaveBeenCalledWith(3)
      expect(tid(wrapper, 'admin-clazzes-notice').text()).toBe('已删除班级「软工2201」')
      expect(api.listClazzes).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    })

    it('shows the backend message when deleting fails', async () => {
      api.deleteClazz.mockRejectedValueOnce(new Error('班级不存在'))
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-clazz-delete-4').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazzes-action-error').text()).toBe('班级不存在')
      wrapper.unmount()
    })
  })
})
