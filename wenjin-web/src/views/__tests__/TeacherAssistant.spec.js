import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'

const api = vi.hoisted(() => ({
  assistantChatStream: vi.fn(),
  listAssistantConversations: vi.fn(),
  fetchAssistantMessages: vi.fn(),
  deleteAssistantConversation: vi.fn()
}))
const course = vi.hoisted(() => ({ courseId: null }))

vi.mock('../../api/teacherAssistant.js', () => api)
vi.mock('../../composables/useTeacherCourse.js', () => ({ useTeacherCourse: () => course }))

import TeacherAssistant from '../TeacherAssistant.vue'
import InsightTable from '../../components/InsightTable.vue'

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset())
  api.listAssistantConversations.mockResolvedValue([{ id: 1, title: '旧对话', updatedAt: 't' }])
  course.courseId = ref(5)
})

describe('TeacherAssistant', () => {
  it('lists conversations, loads messages on select, deletes', async () => {
    api.fetchAssistantMessages.mockResolvedValue([
      { role: 'user', content: 'qq1' },
      { role: 'ai', content: 'aa1' }
    ])
    api.deleteAssistantConversation.mockResolvedValue(null)
    const w = mount(TeacherAssistant)
    await flushPromises()
    expect(api.listAssistantConversations.mock.calls[0][0]).toBe(5)
    await w.get('[data-testid=conv-item]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('qq1')
    expect(w.text()).toContain('aa1')
    await w.get('[data-testid=conv-del]').trigger('click')
    await flushPromises()
    expect(api.deleteAssistantConversation).toHaveBeenCalledWith(1)
    expect(w.findAll('[data-testid=conv-item]')).toHaveLength(0)
    expect(w.findAll('[data-testid=starter]').length).toBe(3)
  })

  it('streams tool capsule, insight table and tokens from a starter', async () => {
    api.assistantChatStream.mockImplementation(async (payload, cb) => {
      cb.onMeta({ conversationId: 7 })
      cb.onToolStart({ name: 'list', label: '查名单' })
      cb.onToolResult({ name: 'list', ok: true })
      cb.onInsightCard({ title: '关注名单', columns: ['姓名'], rows: [['张三']], note: '' })
      cb.onToken('结论')
      cb.onDone()
    })
    const w = mount(TeacherAssistant)
    await flushPromises()
    await w.get('[data-testid=starter]').trigger('click')
    await flushPromises()
    expect(api.assistantChatStream.mock.calls[0][0]).toMatchObject({
      courseId: 5,
      message: '这周哪些学生需要关注？'
    })
    expect(w.get('[data-testid=tool-capsule]').text()).toContain('查名单')
    expect(w.findComponent(InsightTable).exists()).toBe(true)
    expect(w.text()).toContain('张三')
    expect(w.text()).toContain('结论')
  })

  it('shows feature-off state on 404', async () => {
    api.listAssistantConversations.mockRejectedValue(Object.assign(new Error('x'), { httpStatus: 404 }))
    const w = mount(TeacherAssistant)
    await flushPromises()
    expect(w.get('[data-testid=flag-off]').text()).toBe('功能未开启')
  })

  it('shows stream error', async () => {
    api.assistantChatStream.mockImplementation(async (p, cb) => {
      cb.onError('boom')
    })
    const w = mount(TeacherAssistant)
    await flushPromises()
    await w.get('[data-testid=starter]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid=error]').text()).toBe('boom')
  })

  it('aborts the in-flight stream on course switch and unmount', async () => {
    let signal
    api.assistantChatStream.mockImplementation((p, cb, opts) => {
      signal = opts.signal
      return new Promise(() => {})
    })
    const w = mount(TeacherAssistant)
    await flushPromises()
    await w.get('[data-testid=starter]').trigger('click')
    expect(signal.aborted).toBe(false)
    course.courseId.value = 6
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(api.listAssistantConversations.mock.calls.at(-1)[0]).toBe(6)

    let signal2
    api.assistantChatStream.mockImplementation((p, cb, opts) => {
      signal2 = opts.signal
      return new Promise(() => {})
    })
    await w.get('[data-testid=starter]').trigger('click')
    w.unmount()
    expect(signal2.aborted).toBe(true)
  })
})

describe('InsightTable', () => {
  const card = { title: 'T', columns: ['a', 'b'], rows: [['1', '2']], note: '注' }
  it('renders accessible table and copies CSV', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const w = mount(InsightTable, { props: { card } })
    expect(w.findAll('th[scope=col]')).toHaveLength(2)
    expect(w.find('caption').text()).toBe('T')
    expect(w.text()).toContain('注')
    await w.get('[data-testid=copy-csv]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('a,b\r\n1,2')
  })
})
