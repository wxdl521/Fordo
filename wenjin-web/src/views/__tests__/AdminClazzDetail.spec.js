import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const api = vi.hoisted(() => ({
  getClazzDetail: vi.fn(),
  updateClazz: vi.fn(),
  addClazzStudents: vi.fn(),
  removeClazzStudent: vi.fn(),
  addClazzCourse: vi.fn(),
  removeClazzCourse: vi.fn()
}))
const teacherApi = vi.hoisted(() => ({
  fetchTeacherCourses: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)
vi.mock('../../api/teacher.js', () => teacherApi)

import AdminClazzDetail from '../AdminClazzDetail.vue'

const STU_A = { id: 11, username: 'stu1', realName: '王同学', status: 1 }
const STU_B = { id: 12, username: 'stu2', realName: '', status: 0 }
const STU_C = { id: 13, username: 'stu3', realName: '赵同学', status: 1 }
const LINKED = { id: 5, name: '软件工程', status: 1, archived: false }

function detail(overrides = {}) {
  return {
    id: 6,
    name: '软工2201',
    description: '示例班级',
    studentCount: 2,
    courseCount: 1,
    createdAt: '2026-10-01T08:00:00',
    students: [STU_A, STU_B],
    courses: [LINKED],
    ...overrides
  }
}

const COURSE_OPTIONS = [
  { id: 5, code: 'se', name: '软件工程', published: true },
  { id: 6, code: 'java', name: 'Java 程序设计', published: false },
  { id: 8, code: 'test', name: '软件测试', published: true }
]

let confirmMock
let router

async function mountPage(id = 6) {
  const pinia = createPinia()
  setActivePinia(pinia)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/clazzes', component: { template: '<div />' } },
      { path: '/admin/clazzes/:id', component: AdminClazzDetail }
    ]
  })
  await router.push(`/admin/clazzes/${id}`)
  await router.isReady()
  const wrapper = mount(AdminClazzDetail, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminClazzDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmMock)
    api.getClazzDetail.mockResolvedValue(detail())
    api.updateClazz.mockResolvedValue({})
    api.addClazzStudents.mockResolvedValue({ added: 0, movedFrom: [], notFound: [], enrolled: 0 })
    api.removeClazzStudent.mockResolvedValue(null)
    api.addClazzCourse.mockResolvedValue({ enrolled: 0 })
    api.removeClazzCourse.mockResolvedValue(null)
    teacherApi.fetchTeacherCourses.mockResolvedValue(COURSE_OPTIONS)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('detail', () => {
    it('loads the class by route id and renders info, students and linked courses', async () => {
      const wrapper = await mountPage()

      expect(api.getClazzDetail).toHaveBeenCalledTimes(1)
      expect(api.getClazzDetail).toHaveBeenCalledWith(6, expect.objectContaining({ signal: expect.any(AbortSignal) }))
      expect(wrapper.get('h1').text()).toBe('软工2201')
      expect(tid(wrapper, 'admin-clazz-name').element.value).toBe('软工2201')
      expect(tid(wrapper, 'admin-clazz-description').element.value).toBe('示例班级')
      expect(tid(wrapper, 'admin-clazz-back').attributes('href')).toBe('/admin/clazzes')

      expect(tid(wrapper, 'admin-clazz-students-table').findAll('tbody tr')).toHaveLength(2)
      expect(tid(wrapper, 'admin-clazz-student-row-11').text()).toContain('王同学')
      expect(tid(wrapper, 'admin-clazz-student-row-11').text()).toContain('启用')
      expect(tid(wrapper, 'admin-clazz-student-row-12').text()).toContain('禁用')
      expect(tid(wrapper, 'admin-clazz-course-row-5').text()).toContain('软件工程')
      expect(tid(wrapper, 'admin-clazz-course-row-5').text()).toContain('已发布')
      wrapper.unmount()
    })

    it('shows the backend message when the class cannot be loaded', async () => {
      api.getClazzDetail.mockRejectedValueOnce(new Error('班级不存在'))
      const wrapper = await mountPage(99)

      expect(tid(wrapper, 'admin-clazz-error').text()).toBe('班级不存在')
      expect(has(wrapper, 'admin-clazz-students-table')).toBe(false)
      wrapper.unmount()
    })

    it('shows empty states for a class with no students and no courses', async () => {
      api.getClazzDetail.mockResolvedValueOnce(detail({ students: [], courses: [] }))
      const wrapper = await mountPage()

      expect(has(wrapper, 'admin-clazz-students-empty')).toBe(true)
      expect(has(wrapper, 'admin-clazz-courses-empty')).toBe(true)
      wrapper.unmount()
    })
  })

  describe('basic info', () => {
    it('refuses an empty name', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-name').setValue('   ')

      await tid(wrapper, 'admin-clazz-save').trigger('click')

      expect(tid(wrapper, 'admin-clazz-info-error').text()).toBe('请填写班级名称')
      expect(api.updateClazz).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('saves trimmed name and description, reloads and announces', async () => {
      api.getClazzDetail.mockResolvedValueOnce(detail()).mockResolvedValueOnce(detail({ name: '软工2299', description: '' }))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-name').setValue('  软工2299 ')
      await tid(wrapper, 'admin-clazz-description').setValue('   ')

      await tid(wrapper, 'admin-clazz-save').trigger('click')
      await flushPromises()

      expect(api.updateClazz).toHaveBeenCalledWith(6, { name: '软工2299', description: '' })
      expect(api.getClazzDetail).toHaveBeenCalledTimes(2)
      expect(tid(wrapper, 'admin-clazz-notice').text()).toBe('班级信息已保存')
      expect(wrapper.get('h1').text()).toBe('软工2299')
      expect(tid(wrapper, 'admin-clazz-name').element.value).toBe('软工2299')
      wrapper.unmount()
    })

    it('shows the backend message for a duplicate name and keeps the typed value', async () => {
      api.updateClazz.mockRejectedValueOnce(new Error('班级名已存在'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-name').setValue('软工2202')

      await tid(wrapper, 'admin-clazz-save').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazz-info-error').text()).toBe('班级名已存在')
      expect(tid(wrapper, 'admin-clazz-name').element.value).toBe('软工2202')
      wrapper.unmount()
    })
  })

  describe('add students', () => {
    it('requires at least one username and caps the batch at 500', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-add-students').trigger('click')
      expect(has(wrapper, 'admin-clazz-students-modal')).toBe(true)

      await tid(wrapper, 'admin-clazz-students-input').setValue(' \n \n')
      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      expect(tid(wrapper, 'admin-clazz-students-error').text()).toBe('请至少填写一个学生用户名')

      await tid(wrapper, 'admin-clazz-students-input').setValue(Array.from({ length: 501 }, (_, i) => `u${i}`).join('\n'))
      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      expect(tid(wrapper, 'admin-clazz-students-error').text()).toContain('500')
      expect(api.addClazzStudents).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('sends one username per line (blanks and duplicates dropped) and shows added / moved / not-found / enrolled', async () => {
      api.addClazzStudents.mockResolvedValueOnce({
        added: 2,
        movedFrom: [{ studentId: 13, fromClazzName: '软工2200' }, { studentId: 99, fromClazzName: '软工2100' }],
        notFound: ['ghost'],
        enrolled: 4
      })
      api.getClazzDetail.mockResolvedValueOnce(detail()).mockResolvedValueOnce(detail({ students: [STU_A, STU_B, STU_C] }))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-add-students').trigger('click')
      await tid(wrapper, 'admin-clazz-students-input').setValue('stu3\r\n\n ghost \nstu3\nstu4\n')

      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      await flushPromises()

      expect(api.addClazzStudents).toHaveBeenCalledWith(6, { usernames: ['stu3', 'ghost', 'stu4'] })
      // 列表同步刷新
      expect(api.getClazzDetail).toHaveBeenCalledTimes(2)
      expect(has(wrapper, 'admin-clazz-student-row-13')).toBe(true)

      const summary = tid(wrapper, 'admin-clazz-students-summary').text()
      expect(summary).toContain('成功加入 2 人')
      expect(summary).toContain('新增选课记录 4 条')

      const moved = tid(wrapper, 'admin-clazz-students-moved').text()
      expect(moved).toContain('赵同学（stu3）：原属「软工2200」') // 刷新后能解析出用户名
      expect(moved).toContain('学生 #99：原属「软工2100」') // 解析不出就回退到 id

      const notFound = tid(wrapper, 'admin-clazz-students-notfound').text()
      expect(notFound).toContain('ghost')
      expect(notFound).toContain('1 个')

      // 输入框被结果面板替换
      expect(has(wrapper, 'admin-clazz-students-input')).toBe(false)
      wrapper.unmount()
    })

    it('omits the moved / not-found blocks when everything was a clean add', async () => {
      api.addClazzStudents.mockResolvedValueOnce({ added: 1, movedFrom: [], notFound: [], enrolled: 0 })
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-add-students').trigger('click')
      await tid(wrapper, 'admin-clazz-students-input').setValue('stu3')

      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazz-students-summary').text()).toContain('成功加入 1 人')
      expect(has(wrapper, 'admin-clazz-students-moved')).toBe(false)
      expect(has(wrapper, 'admin-clazz-students-notfound')).toBe(false)
      expect(has(wrapper, 'admin-clazz-students-again')).toBe(false)
      wrapper.unmount()
    })

    it('"retry" returns to the input prefilled with the not-found usernames; "done" closes', async () => {
      api.addClazzStudents.mockResolvedValueOnce({ added: 1, movedFrom: [], notFound: ['ghost', 'typo'], enrolled: 0 })
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-add-students').trigger('click')
      await tid(wrapper, 'admin-clazz-students-input').setValue('stu3\nghost\ntypo')
      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      await flushPromises()

      await tid(wrapper, 'admin-clazz-students-again').trigger('click')
      expect(tid(wrapper, 'admin-clazz-students-input').element.value).toBe('ghost\ntypo')

      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      await flushPromises()
      await tid(wrapper, 'admin-clazz-students-done').trigger('click')
      expect(has(wrapper, 'admin-clazz-students-modal')).toBe(false)
      wrapper.unmount()
    })

    it('keeps the input and shows the backend message when the request fails', async () => {
      api.addClazzStudents.mockRejectedValueOnce(new Error('一次最多添加 500 个'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-add-students').trigger('click')
      await tid(wrapper, 'admin-clazz-students-input').setValue('stu3')

      await tid(wrapper, 'admin-clazz-students-submit').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazz-students-error').text()).toBe('一次最多添加 500 个')
      expect(tid(wrapper, 'admin-clazz-students-input').element.value).toBe('stu3')
      wrapper.unmount()
    })
  })

  describe('remove student', () => {
    it('confirms with the "enrollments are kept" promise, then removes and reloads', async () => {
      const wrapper = await mountPage()

      confirmMock.mockReturnValueOnce(false)
      await tid(wrapper, 'admin-clazz-student-remove-11').trigger('click')
      await flushPromises()
      expect(api.removeClazzStudent).not.toHaveBeenCalled()

      api.getClazzDetail.mockClear()
      api.getClazzDetail.mockResolvedValueOnce(detail({ students: [STU_B] }))
      await tid(wrapper, 'admin-clazz-student-remove-11').trigger('click')
      await flushPromises()

      const text = confirmMock.mock.calls.at(-1)[0]
      expect(text).toContain('王同学（stu1）')
      expect(text).toContain('选课记录保留')
      expect(text).toContain('仍可继续学习')
      expect(api.removeClazzStudent).toHaveBeenCalledWith(6, 11)
      expect(api.getClazzDetail).toHaveBeenCalledTimes(1)
      expect(has(wrapper, 'admin-clazz-student-row-11')).toBe(false)
      expect(tid(wrapper, 'admin-clazz-notice').text()).toContain('选课记录保留')
      wrapper.unmount()
    })

    it('shows the backend message when removing fails', async () => {
      api.removeClazzStudent.mockRejectedValueOnce(new Error('学生不在该班级'))
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-clazz-student-remove-12').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazz-action-error').text()).toBe('学生不在该班级')
      wrapper.unmount()
    })
  })

  describe('courses', () => {
    it('offers only the courses not yet linked, marking drafts; the link button needs a pick', async () => {
      const wrapper = await mountPage()

      const options = tid(wrapper, 'admin-clazz-course-select').findAll('option')
      expect(options.map((o) => o.text())).toEqual(['选择要关联的课程', 'Java 程序设计（草稿）', '软件测试'])
      expect(tid(wrapper, 'admin-clazz-add-course').attributes('disabled')).toBeDefined()

      await tid(wrapper, 'admin-clazz-course-select').setValue('8')
      expect(tid(wrapper, 'admin-clazz-add-course').attributes('disabled')).toBeUndefined()
      wrapper.unmount()
    })

    it('links the chosen course, reports the new enrollments and refreshes the table', async () => {
      api.addClazzCourse.mockResolvedValueOnce({ enrolled: 7 })
      api.getClazzDetail
        .mockResolvedValueOnce(detail())
        .mockResolvedValueOnce(detail({ courses: [LINKED, { id: 8, name: '软件测试', status: 0, archived: false }] }))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-course-select').setValue('8')

      await tid(wrapper, 'admin-clazz-add-course').trigger('click')
      await flushPromises()

      expect(api.addClazzCourse).toHaveBeenCalledWith(6, 8)
      expect(tid(wrapper, 'admin-clazz-notice').text()).toBe('已关联「软件测试」，为班级学生新增选课记录 7 条')
      expect(tid(wrapper, 'admin-clazz-course-row-8').text()).toContain('草稿')
      // 已关联的课程从下拉里消失，选择被清空
      const options = tid(wrapper, 'admin-clazz-course-select').findAll('option')
      expect(options.map((o) => o.text())).toEqual(['选择要关联的课程', 'Java 程序设计（草稿）'])
      expect(tid(wrapper, 'admin-clazz-add-course').attributes('disabled')).toBeDefined()
      wrapper.unmount()
    })

    it('shows the backend message when linking fails and keeps the pick', async () => {
      api.addClazzCourse.mockRejectedValueOnce(new Error('课程已归档'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-clazz-course-select').setValue('8')

      await tid(wrapper, 'admin-clazz-add-course').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-clazz-action-error').text()).toBe('课程已归档')
      expect(tid(wrapper, 'admin-clazz-course-select').element.value).toBe('8')
      wrapper.unmount()
    })

    it('unlinking confirms with the "enrollments are kept" promise, then removes and reloads', async () => {
      const wrapper = await mountPage()

      confirmMock.mockReturnValueOnce(false)
      await tid(wrapper, 'admin-clazz-course-remove-5').trigger('click')
      await flushPromises()
      expect(api.removeClazzCourse).not.toHaveBeenCalled()

      api.getClazzDetail.mockClear()
      api.getClazzDetail.mockResolvedValueOnce(detail({ courses: [] }))
      await tid(wrapper, 'admin-clazz-course-remove-5').trigger('click')
      await flushPromises()

      const text = confirmMock.mock.calls.at(-1)[0]
      expect(text).toContain('软件工程')
      expect(text).toContain('选课记录保留')
      expect(text).toContain('仍可继续学习')
      expect(api.removeClazzCourse).toHaveBeenCalledWith(6, 5)
      expect(has(wrapper, 'admin-clazz-course-row-5')).toBe(false)
      expect(has(wrapper, 'admin-clazz-courses-empty')).toBe(true)
      wrapper.unmount()
    })

    it('tells the admin when the course list cannot be loaded', async () => {
      teacherApi.fetchTeacherCourses.mockRejectedValueOnce(new Error('课程服务不可用'))
      const wrapper = await mountPage()

      expect(tid(wrapper, 'admin-clazz-courses-error').text()).toBe('课程服务不可用')
      expect(tid(wrapper, 'admin-clazz-course-select').findAll('option')).toHaveLength(1)
      wrapper.unmount()
    })
  })
})
