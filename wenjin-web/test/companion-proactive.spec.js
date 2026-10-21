import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '../src/store/session.js'
import { useCourseStore } from '../src/store/course.js'

const fetchOpener = vi.fn()
const dismissHint = vi.fn()
const chatStream = vi.fn()

vi.mock('../src/api/companion.js', async () => {
  const actual = await vi.importActual('../src/api/companion.js')
  return {
    ...actual,
    listConversations: vi.fn().mockResolvedValue([]),
    fetchConversation: vi.fn().mockResolvedValue({ messages: [] }),
    deleteConversation: vi.fn(),
    fetchOpener: (...a) => fetchOpener(...a),
    dismissHint: (...a) => dismissHint(...a),
    chatStream: (...a) => chatStream(...a)
  }
})
vi.mock('../src/api/practice.js', () => ({ submitPractice: vi.fn() }))

import Companion from '../src/views/Companion.vue'

const OPENER = {
  greeting: '晚上好，今天先从这里开始',
  actions: [
    {
      kind: 'REVIEW_MISTAKES', title: '复盘刚才的 2 道错题', reason: '上次练习 3 题错 2 题',
      nodeCode: 'N1', nodeName: '数组',
      cta: { prompt: '帮我复盘一下刚才那次练习的错题', reviewPracticeSessionId: 77 }
    },
    {
      kind: 'WEAK_NODE', title: '加强「链表」', reason: '掌握度 40%',
      nodeCode: 'N2', nodeName: '链表', cta: { prompt: '带我练练链表' }
    }
  ],
  hints: [
    { hintKey: 'DECAY:2026-10-21', hintType: 'DECAY', title: '有 3 个知识点正在遗忘', reason: '建议复习', cta: { prompt: '带我复习' } }
  ]
}

async function mountCompanion(query = { courseId: '5' }) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 't', user: { id: 2, role: 2, realName: '学生' } })
  useCourseStore(pinia).setStudentCourseId(5)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: { template: '<div />' } },
      { path: '/companion', name: 'companion', component: Companion }
    ]
  })
  await router.push({ path: '/companion', query })
  await router.isReady()
  const wrapper = mount(Companion, { global: { plugins: [pinia, router], stubs: { QuestionAnswerCard: true } } })
  await flushPromises()
  return { wrapper, router }
}

describe('Companion proactive opener / hints / review', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    chatStream.mockResolvedValue(undefined)
    dismissHint.mockResolvedValue(undefined)
    fetchOpener.mockResolvedValue(OPENER)
    localStorage.clear()
  })
  afterEach(() => { delete window.__wjCompanion })

  it('renders greeting and actions from opener', async () => {
    const { wrapper } = await mountCompanion()
    expect(fetchOpener).toHaveBeenCalledWith(5)
    expect(wrapper.find('[data-testid="opener-greeting"]').text()).toContain('晚上好')
    expect(wrapper.findAll('[data-testid="opener-action"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('上次练习 3 题错 2 题')
  })

  it('falls back to starters when opener fails (404)', async () => {
    fetchOpener.mockRejectedValue(new Error('404'))
    const { wrapper } = await mountCompanion()
    expect(wrapper.find('[data-testid="opener-action"]').exists()).toBe(false)
    expect(wrapper.findAll('.cp-starter')).toHaveLength(3)
  })

  it('clicking an action sends cta.prompt with reviewPracticeSessionId and nodeCode', async () => {
    const { wrapper } = await mountCompanion()
    await wrapper.findAll('[data-testid="opener-action-btn"]')[0].trigger('click')
    await flushPromises()
    expect(chatStream).toHaveBeenCalledTimes(1)
    expect(chatStream.mock.calls[0][0]).toMatchObject({
      courseId: 5, message: '帮我复盘一下刚才那次练习的错题', reviewPracticeSessionId: 77, nodeCode: 'N1'
    })
  })

  it('a plain starter still sends without review fields', async () => {
    fetchOpener.mockRejectedValue(new Error('404'))
    const { wrapper } = await mountCompanion()
    await wrapper.findAll('.cp-starter')[0].trigger('click')
    await flushPromises()
    const payload = chatStream.mock.calls[0][0]
    expect(payload.reviewPracticeSessionId).toBeUndefined()
  })

  it('?review=<id> auto-sends exactly once and strips review from the URL', async () => {
    const { router } = await mountCompanion({ courseId: '5', review: '123', nodeCode: 'N9' })
    await flushPromises()
    expect(chatStream).toHaveBeenCalledTimes(1)
    expect(chatStream.mock.calls[0][0]).toMatchObject({
      courseId: 5, message: '帮我复盘一下刚才那次练习的错题', reviewPracticeSessionId: 123, nodeCode: 'N9'
    })
    expect(router.currentRoute.value.query.review).toBeUndefined()
    expect(router.currentRoute.value.query.nodeCode).toBe('N9')
    await flushPromises()
    expect(chatStream).toHaveBeenCalledTimes(1)
  })

  it('ignores a non-positive-integer review param', async () => {
    await mountCompanion({ courseId: '5', review: 'abc' })
    expect(chatStream).not.toHaveBeenCalled()
  })

  it('hint bar dismiss calls the API and hides the hint', async () => {
    const { wrapper } = await mountCompanion()
    expect(wrapper.findAll('[data-testid="companion-hint"]')).toHaveLength(1)
    await wrapper.find('[data-testid="companion-hint-close"]').trigger('click')
    await flushPromises()
    expect(dismissHint).toHaveBeenCalledWith(5, 'DECAY:2026-10-21')
    expect(wrapper.find('[data-testid="companion-hint"]').exists()).toBe(false)
  })

  it('hint cta sends its prompt', async () => {
    const { wrapper } = await mountCompanion()
    await wrapper.find('[data-testid="companion-hint-go"]').trigger('click')
    await flushPromises()
    expect(chatStream.mock.calls[0][0].message).toBe('带我复习')
  })
})
