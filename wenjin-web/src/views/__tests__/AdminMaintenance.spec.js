import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listAdminCourses: vi.fn(),
  resetLearningData: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)

import AdminMaintenance from '../AdminMaintenance.vue'

const COURSES = [
  { id: 5, name: '软件工程', code: 'se2201', status: 1, archived: false },
  { id: 6, name: 'Java 程序设计', code: 'java01', status: 1, archived: false }
]

async function mountPage() {
  const wrapper = mount(AdminMaintenance)
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

async function pickCourse(wrapper, id) {
  await tid(wrapper, 'admin-maint-course').setValue(String(id))
  await flushPromises()
}

async function typeConfirm(wrapper, text) {
  await tid(wrapper, 'admin-maint-confirm').setValue(text)
}

describe('AdminMaintenance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listAdminCourses.mockResolvedValue({ records: COURSES, total: COURSES.length, page: 1, size: 100 })
    api.resetLearningData.mockResolvedValue({ deleted: {} })
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads unarchived courses (size 100) into the picker and lists what is cleared and what is kept', async () => {
    const wrapper = await mountPage()

    expect(api.listAdminCourses).toHaveBeenCalledWith({ page: 1, size: 100, archived: false })
    const options = tid(wrapper, 'admin-maint-course').findAll('option')
    expect(options.map((o) => o.text())).toEqual(['请选择课程', '软件工程', 'Java 程序设计'])

    const page = tid(wrapper, 'admin-maint-course').element.closest('section').textContent
    expect(page).toContain('学生的答题记录')
    expect(page).toContain('学生掌握度与掌握度快照')
    expect(page).toContain('入口诊断、快速诊断与练习会话')
    expect(page).toContain('学习路径')
    expect(page).toContain('AI 学习伴侣的对话与学生记忆')
    expect(page).toContain('教师 AI 助教的会话')
    expect(page).toContain('选课关系、班级、知识图谱、题库、账号')

    expect(tid(wrapper, 'admin-maint-confirm').attributes('disabled')).toBeDefined()
    expect(tid(wrapper, 'admin-maint-submit').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('keeps the submit button disabled until the confirm text matches the course name exactly', async () => {
    const wrapper = await mountPage()
    await pickCourse(wrapper, 5)
    const submit = tid(wrapper, 'admin-maint-submit')
    expect(tid(wrapper, 'admin-maint-confirm').attributes('disabled')).toBeUndefined()
    expect(submit.attributes('disabled')).toBeDefined()

    await typeConfirm(wrapper, '软件')
    expect(submit.attributes('disabled')).toBeDefined()

    await typeConfirm(wrapper, ' 软件工程')
    expect(submit.attributes('disabled')).toBeDefined()

    await typeConfirm(wrapper, '软件工程 ')
    expect(submit.attributes('disabled')).toBeDefined()

    await typeConfirm(wrapper, '软件工程')
    expect(submit.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('clears the confirm text when another course is picked, so a stale match cannot fire', async () => {
    const wrapper = await mountPage()
    await pickCourse(wrapper, 5)
    await typeConfirm(wrapper, '软件工程')
    expect(tid(wrapper, 'admin-maint-submit').attributes('disabled')).toBeUndefined()

    await pickCourse(wrapper, 6)

    expect(tid(wrapper, 'admin-maint-confirm').element.value).toBe('')
    expect(tid(wrapper, 'admin-maint-submit').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('asks once more, then submits the course id and confirm text and renders the per-table counts', async () => {
    api.resetLearningData.mockResolvedValueOnce({
      deleted: {
        answer_record: 128,
        student_mastery: 39,
        mastery_snapshot: 210,
        practice_session: 4,
        some_new_table: 2
      }
    })
    const wrapper = await mountPage()
    await pickCourse(wrapper, 5)
    await typeConfirm(wrapper, '软件工程')

    await tid(wrapper, 'admin-maint-submit').trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledTimes(1)
    expect(window.confirm.mock.calls[0][0]).toContain('软件工程')
    expect(api.resetLearningData).toHaveBeenCalledTimes(1)
    expect(api.resetLearningData).toHaveBeenCalledWith(5, '软件工程')

    const result = tid(wrapper, 'admin-maint-result')
    expect(result.text()).toContain('已清空「软件工程」的学习数据，共删除 383 行')
    expect(result.findAll('tbody tr')).toHaveLength(5)
    expect(tid(wrapper, 'admin-maint-result-row-answer_record').text()).toContain('学生答题记录')
    expect(tid(wrapper, 'admin-maint-result-row-answer_record').text()).toContain('128')
    expect(tid(wrapper, 'admin-maint-result-row-mastery_snapshot').text()).toContain('210')
    // 未知表名原样显示
    expect(tid(wrapper, 'admin-maint-result-row-some_new_table').text()).toContain('some_new_table')

    // 成功后确认文本清空，按钮回到禁用，避免重复误触
    expect(tid(wrapper, 'admin-maint-confirm').element.value).toBe('')
    expect(tid(wrapper, 'admin-maint-submit').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('sends nothing when the second confirmation is declined', async () => {
    window.confirm.mockReturnValueOnce(false)
    const wrapper = await mountPage()
    await pickCourse(wrapper, 5)
    await typeConfirm(wrapper, '软件工程')

    await tid(wrapper, 'admin-maint-submit').trigger('click')
    await flushPromises()

    expect(api.resetLearningData).not.toHaveBeenCalled()
    expect(has(wrapper, 'admin-maint-result')).toBe(false)
    wrapper.unmount()
  })

  it('shows the backend message (production is disabled, 403) and no result', async () => {
    api.resetLearningData.mockRejectedValueOnce(new Error('生产环境禁用学习数据重置'))
    const wrapper = await mountPage()
    await pickCourse(wrapper, 5)
    await typeConfirm(wrapper, '软件工程')

    await tid(wrapper, 'admin-maint-submit').trigger('click')
    await flushPromises()

    expect(tid(wrapper, 'admin-maint-error').text()).toBe('生产环境禁用学习数据重置')
    expect(has(wrapper, 'admin-maint-result')).toBe(false)
    // 失败后保留确认文本，便于修正后重试
    expect(tid(wrapper, 'admin-maint-confirm').element.value).toBe('软件工程')
    wrapper.unmount()
  })

  it('shows a course-list failure in the error slot', async () => {
    api.listAdminCourses.mockRejectedValueOnce(new Error('无访问权限'))
    const wrapper = await mountPage()

    expect(tid(wrapper, 'admin-maint-error').text()).toBe('无访问权限')
    expect(tid(wrapper, 'admin-maint-submit').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('notes when the picker is showing only the first page of courses', async () => {
    api.listAdminCourses.mockResolvedValueOnce({ records: COURSES, total: 150, page: 1, size: 100 })
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('只列出前 2 门未归档课程')
    wrapper.unmount()
  })
})
