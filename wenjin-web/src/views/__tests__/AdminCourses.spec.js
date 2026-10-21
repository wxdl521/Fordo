import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const api = vi.hoisted(() => ({
  listAdminCourses: vi.fn(),
  listTeachers: vi.fn(),
  createAdminCourse: vi.fn(),
  transferCourseOwner: vi.fn(),
  setAdminCoursePublished: vi.fn(),
  archiveAdminCourse: vi.fn(),
  restoreAdminCourse: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)

import AdminCourses from '../AdminCourses.vue'
import { useCourseStore, TEACHER_COURSE_KEY } from '../../store/course.js'

const TEACHERS = [
  { id: 3, username: 't_li', realName: '李老师' },
  { id: 4, username: 't_zhang', realName: '张老师' }
]

function course(overrides = {}) {
  return {
    id: 5,
    code: 'se2201',
    name: '软件工程',
    teacherId: 3,
    teacherName: '李老师',
    status: 1,
    archived: false,
    archivedAt: null,
    nodeCount: 39,
    studentCount: 12,
    clazzCount: 2,
    createdAt: '2026-10-01T08:00:00',
    ...overrides
  }
}

const PUBLISHED = course()
const DRAFT = course({ id: 6, code: 'java01', name: 'Java 程序设计', teacherId: 4, teacherName: '张老师', status: 0, nodeCount: 33, studentCount: 0, clazzCount: 0 })
const ARCHIVED = course({ id: 7, code: 'old01', name: '旧课程', status: 0, archived: true, archivedAt: '2026-10-05T10:00:00' })
const ROWS = [PUBLISHED, DRAFT, ARCHIVED]

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
      { path: '/teacher/graph', component: { template: '<div data-testid="teacher-graph" />' } }
    ]
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(AdminCourses, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminCourses', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    confirmMock = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmMock)
    api.listAdminCourses.mockResolvedValue(pageOf())
    api.listTeachers.mockResolvedValue(TEACHERS)
    api.createAdminCourse.mockResolvedValue(course({ id: 9 }))
    api.transferCourseOwner.mockResolvedValue(course())
    api.setAdminCoursePublished.mockResolvedValue(course())
    api.archiveAdminCourse.mockResolvedValue(course())
    api.restoreAdminCourse.mockResolvedValue(course())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('list', () => {
    it('loads page 1 on mount with no filters and renders every row', async () => {
      const wrapper = await mountPage()

      expect(api.listAdminCourses).toHaveBeenCalledTimes(1)
      expect(api.listAdminCourses).toHaveBeenCalledWith({ page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) }))
      expect(api.listTeachers).toHaveBeenCalledTimes(1)

      expect(tid(wrapper, 'admin-courses-table').findAll('tbody tr')).toHaveLength(3)
      const row = tid(wrapper, 'admin-course-row-5').text()
      expect(row).toContain('软件工程')
      expect(row).toContain('se2201')
      expect(row).toContain('39')
      expect(row).toContain('12')
      expect(row).toContain('2026-10-01 08:00')
      expect(tid(wrapper, 'admin-course-teacher-5').text()).toBe('李老师')
      expect(tid(wrapper, 'admin-course-status-tag-5').text()).toBe('已发布')
      expect(tid(wrapper, 'admin-course-status-tag-6').text()).toBe('草稿')
      expect(tid(wrapper, 'admin-course-status-tag-7').text()).toBe('已归档')
      expect(tid(wrapper, 'admin-courses-total').text()).toBe('共 3 条')
      wrapper.unmount()
    })

    it('shows an empty state and the backend message on load failure', async () => {
      api.listAdminCourses.mockResolvedValueOnce(pageOf([], 0))
      const wrapper = await mountPage()
      expect(has(wrapper, 'admin-courses-empty')).toBe(true)
      expect(has(wrapper, 'admin-courses-pagination')).toBe(false)
      wrapper.unmount()

      api.listAdminCourses.mockRejectedValueOnce(new Error('无访问权限'))
      const failed = await mountPage()
      expect(tid(failed, 'admin-courses-error').text()).toBe('无访问权限')
      failed.unmount()
    })

    it('search sends keyword / status / archived / teacherId as typed values and resets to page 1', async () => {
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-courses-keyword').setValue('  软件 ')
      await tid(wrapper, 'admin-courses-status').setValue('0')
      await tid(wrapper, 'admin-courses-archived').setValue('false')
      await tid(wrapper, 'admin-courses-teacher').setValue('4')
      api.listAdminCourses.mockClear()
      await tid(wrapper, 'admin-courses-search').trigger('click')
      await flushPromises()

      expect(api.listAdminCourses).toHaveBeenCalledTimes(1)
      expect(api.listAdminCourses).toHaveBeenLastCalledWith(
        { keyword: '软件', status: 0, archived: false, teacherId: 4, page: 1, size: 20 },
        expect.anything()
      )
      wrapper.unmount()
    })

    it('archived "only archived" is sent as true; changing a dropdown searches right away', async () => {
      const wrapper = await mountPage()
      api.listAdminCourses.mockClear()

      await tid(wrapper, 'admin-courses-archived').setValue('true')
      await flushPromises()

      expect(api.listAdminCourses).toHaveBeenCalledTimes(1)
      expect(api.listAdminCourses).toHaveBeenLastCalledWith({ archived: true, page: 1, size: 20 }, expect.anything())
      wrapper.unmount()
    })

    it('the teacher filter lists active teachers by name; reset clears every filter', async () => {
      const wrapper = await mountPage()

      const options = tid(wrapper, 'admin-courses-teacher').findAll('option')
      expect(options.map((o) => o.text())).toEqual(['全部教师', '李老师（t_li）', '张老师（t_zhang）'])

      await tid(wrapper, 'admin-courses-keyword').setValue('abc')
      await tid(wrapper, 'admin-courses-teacher').setValue('3')
      await flushPromises()
      api.listAdminCourses.mockClear()
      await tid(wrapper, 'admin-courses-reset').trigger('click')
      await flushPromises()

      expect(api.listAdminCourses).toHaveBeenLastCalledWith({ page: 1, size: 20 }, expect.anything())
      expect(tid(wrapper, 'admin-courses-keyword').element.value).toBe('')
      expect(tid(wrapper, 'admin-courses-teacher').element.value).toBe('')
      wrapper.unmount()
    })

    it('paginates with the applied filters', async () => {
      api.listAdminCourses.mockResolvedValue(pageOf(ROWS, 45))
      const wrapper = await mountPage()
      expect(tid(wrapper, 'admin-courses-page-info').text()).toBe('第 1 / 3 页')

      api.listAdminCourses.mockClear()
      await tid(wrapper, 'admin-courses-next').trigger('click')
      await flushPromises()
      expect(api.listAdminCourses).toHaveBeenLastCalledWith({ page: 2, size: 20 }, expect.anything())
      wrapper.unmount()
    })
  })

  describe('row actions', () => {
    it('archived rows offer restore (and transfer) but not publish / archive / graph editing', async () => {
      const wrapper = await mountPage()

      expect(has(wrapper, 'admin-course-restore-7')).toBe(true)
      expect(has(wrapper, 'admin-course-transfer-7')).toBe(true)
      expect(has(wrapper, 'admin-course-archive-7')).toBe(false)
      expect(has(wrapper, 'admin-course-publish-7')).toBe(false)
      expect(has(wrapper, 'admin-course-graph-7')).toBe(false)

      expect(has(wrapper, 'admin-course-archive-5')).toBe(true)
      expect(has(wrapper, 'admin-course-restore-5')).toBe(false)
      expect(has(wrapper, 'admin-course-graph-5')).toBe(true)
      expect(tid(wrapper, 'admin-course-publish-5').text()).toBe('下架')
      expect(tid(wrapper, 'admin-course-publish-6').text()).toBe('发布')
      wrapper.unmount()
    })

    it('publishing a draft needs no confirm and reloads the list', async () => {
      const wrapper = await mountPage()
      api.listAdminCourses.mockClear()

      await tid(wrapper, 'admin-course-publish-6').trigger('click')
      await flushPromises()

      expect(confirmMock).not.toHaveBeenCalled()
      expect(api.setAdminCoursePublished).toHaveBeenCalledWith(6, true)
      expect(api.listAdminCourses).toHaveBeenCalledTimes(1)
      expect(tid(wrapper, 'admin-courses-notice').text()).toContain('已发布「Java 程序设计」')
      wrapper.unmount()
    })

    it('unpublishing asks first and keeps the learner data promise in the text', async () => {
      const wrapper = await mountPage()

      confirmMock.mockReturnValueOnce(false)
      await tid(wrapper, 'admin-course-publish-5').trigger('click')
      await flushPromises()
      expect(api.setAdminCoursePublished).not.toHaveBeenCalled()

      await tid(wrapper, 'admin-course-publish-5').trigger('click')
      await flushPromises()
      expect(confirmMock).toHaveBeenLastCalledWith(expect.stringContaining('选课记录与学习数据保留'))
      expect(api.setAdminCoursePublished).toHaveBeenCalledWith(5, false)
      expect(tid(wrapper, 'admin-courses-notice').text()).toContain('已下架「软件工程」')
      wrapper.unmount()
    })

    it('archive confirms then calls the API; restore goes straight through', async () => {
      const wrapper = await mountPage()

      confirmMock.mockReturnValueOnce(false)
      await tid(wrapper, 'admin-course-archive-6').trigger('click')
      await flushPromises()
      expect(api.archiveAdminCourse).not.toHaveBeenCalled()

      await tid(wrapper, 'admin-course-archive-6').trigger('click')
      await flushPromises()
      expect(api.archiveAdminCourse).toHaveBeenCalledWith(6)
      expect(tid(wrapper, 'admin-courses-notice').text()).toContain('已归档「Java 程序设计」')

      confirmMock.mockClear()
      await tid(wrapper, 'admin-course-restore-7').trigger('click')
      await flushPromises()
      expect(confirmMock).not.toHaveBeenCalled()
      expect(api.restoreAdminCourse).toHaveBeenCalledWith(7)
      expect(tid(wrapper, 'admin-courses-notice').text()).toContain('已恢复「旧课程」')
      wrapper.unmount()
    })

    it('shows the backend message when an action fails and does not reload', async () => {
      api.archiveAdminCourse.mockRejectedValueOnce(new Error('课程不存在'))
      const wrapper = await mountPage()
      api.listAdminCourses.mockClear()

      await tid(wrapper, 'admin-course-archive-5').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-courses-action-error').text()).toBe('课程不存在')
      expect(api.listAdminCourses).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('"编辑图谱" remembers the course for the teacher side and jumps to the graph review page', async () => {
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-course-graph-6').trigger('click')
      await flushPromises()

      expect(localStorage.getItem(TEACHER_COURSE_KEY)).toBe('6')
      expect(useCourseStore().teacherCourseId).toBe(6)
      expect(router.currentRoute.value.path).toBe('/teacher/graph')
      wrapper.unmount()
    })
  })

  describe('transfer teacher', () => {
    it('opens a dialog listing teachers, with the current owner disabled', async () => {
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-course-transfer-5').trigger('click')

      const modal = tid(wrapper, 'admin-course-transfer-modal')
      expect(modal.text()).toContain('软件工程')
      expect(modal.text()).toContain('李老师')
      const options = tid(wrapper, 'admin-course-transfer-teacher').findAll('option')
      expect(options.map((o) => o.text())).toEqual(['请选择教师', '李老师（t_li）（当前）', '张老师（t_zhang）'])
      expect(options[1].attributes('disabled')).toBeDefined()
      expect(options[2].attributes('disabled')).toBeUndefined()
      wrapper.unmount()
    })

    it('refuses to submit without a teacher chosen', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-course-transfer-5').trigger('click')

      await tid(wrapper, 'admin-course-transfer-submit').trigger('click')

      expect(api.transferCourseOwner).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-course-transfer-error').text()).toBe('请选择要转让给的教师')
      wrapper.unmount()
    })

    it('submits the chosen teacher id, closes, announces and reloads', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-course-transfer-5').trigger('click')
      await tid(wrapper, 'admin-course-transfer-teacher').setValue('4')
      api.listAdminCourses.mockClear()

      await tid(wrapper, 'admin-course-transfer-submit').trigger('click')
      await flushPromises()

      expect(api.transferCourseOwner).toHaveBeenCalledWith(5, 4)
      expect(has(wrapper, 'admin-course-transfer-modal')).toBe(false)
      expect(tid(wrapper, 'admin-courses-notice').text()).toBe('已将「软件工程」转让给「张老师（t_zhang）」')
      expect(api.listAdminCourses).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    })

    it('keeps the dialog open with the backend message when the transfer is rejected', async () => {
      api.transferCourseOwner.mockRejectedValueOnce(new Error('目标教师已被禁用'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-course-transfer-5').trigger('click')
      await tid(wrapper, 'admin-course-transfer-teacher').setValue('4')

      await tid(wrapper, 'admin-course-transfer-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-course-transfer-error').text()).toBe('目标教师已被禁用')
      expect(has(wrapper, 'admin-course-transfer-modal')).toBe(true)
      wrapper.unmount()
    })
  })

  describe('create course', () => {
    it('validates name and teacher before calling the API', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-course-create').trigger('click')
      expect(has(wrapper, 'admin-course-create-modal')).toBe(true)

      await tid(wrapper, 'admin-course-create-submit').trigger('click')
      expect(tid(wrapper, 'admin-course-create-error').text()).toBe('请填写课程名称')

      await tid(wrapper, 'admin-course-create-name').setValue('数据结构')
      await tid(wrapper, 'admin-course-create-submit').trigger('click')
      expect(tid(wrapper, 'admin-course-create-error').text()).toBe('请选择任课教师')

      await tid(wrapper, 'admin-course-create-name').setValue('x'.repeat(129))
      await tid(wrapper, 'admin-course-create-teacher').setValue('3')
      await tid(wrapper, 'admin-course-create-submit').trigger('click')
      expect(tid(wrapper, 'admin-course-create-error').text()).toContain('128')
      expect(api.createAdminCourse).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('creates the course under the chosen teacher, then reloads from page 1', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-course-create').trigger('click')
      await tid(wrapper, 'admin-course-create-name').setValue('  数据结构 ')
      await tid(wrapper, 'admin-course-create-teacher').setValue('4')
      api.listAdminCourses.mockClear()

      await tid(wrapper, 'admin-course-create-submit').trigger('click')
      await flushPromises()

      expect(api.createAdminCourse).toHaveBeenCalledWith({ name: '数据结构', teacherId: 4 })
      expect(has(wrapper, 'admin-course-create-modal')).toBe(false)
      expect(tid(wrapper, 'admin-courses-notice').text()).toContain('「张老师（t_zhang）」')
      expect(tid(wrapper, 'admin-courses-notice').text()).toContain('数据结构')
      expect(api.listAdminCourses).toHaveBeenCalledTimes(1)
      expect(api.listAdminCourses).toHaveBeenLastCalledWith({ page: 1, size: 20 }, expect.anything())
      wrapper.unmount()
    })

    it('shows the backend message and stays open when creation fails', async () => {
      api.createAdminCourse.mockRejectedValueOnce(new Error('教师不存在或已禁用'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-course-create').trigger('click')
      await tid(wrapper, 'admin-course-create-name').setValue('数据结构')
      await tid(wrapper, 'admin-course-create-teacher').setValue('3')

      await tid(wrapper, 'admin-course-create-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-course-create-error').text()).toBe('教师不存在或已禁用')
      expect(has(wrapper, 'admin-course-create-modal')).toBe(true)
      wrapper.unmount()
    })

    it('retries a failed teacher list when the dialog opens', async () => {
      api.listTeachers.mockRejectedValueOnce(new Error('加载失败'))
      const wrapper = await mountPage()
      expect(api.listTeachers).toHaveBeenCalledTimes(1)

      await tid(wrapper, 'admin-course-create').trigger('click')
      await flushPromises()

      expect(api.listTeachers).toHaveBeenCalledTimes(2)
      // 第二次成功 → 提示消失、下拉有了教师
      expect(has(wrapper, 'admin-course-create-teachers-error')).toBe(false)
      expect(tid(wrapper, 'admin-course-create-teacher').findAll('option')).toHaveLength(3)
      wrapper.unmount()
    })

    it('tells the admin inside the dialog when the teacher list still cannot be loaded', async () => {
      api.listTeachers.mockRejectedValue(new Error('教师列表加载失败'))
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-course-create').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-course-create-teachers-error').text()).toBe('教师列表加载失败')
      expect(tid(wrapper, 'admin-course-create-teacher').findAll('option')).toHaveLength(1)
      wrapper.unmount()
    })
  })
})
