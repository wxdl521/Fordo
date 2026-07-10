import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { nextTick, ref } from 'vue'
import Diagnostic from '../Diagnostic.vue'
import * as diagnosticApi from '../../api/diagnostic.js'

vi.mock('../../api/diagnostic.js', () => ({
  fetchPaper: vi.fn(),
  submitPaper: vi.fn(),
  fetchResult: vi.fn(),
  startAdaptive: vi.fn(),
  answerAdaptive: vi.fn(),
  abandonAdaptive: vi.fn(),
  fetchEvidence: vi.fn()
}))

vi.mock('../../composables/useStudentCourse.js', () => ({
  useStudentCourse: () => ({
    courseId: ref(5)
  })
}))

const samplePaper = {
  courseId: 5,
  sessionToken: 'tok-abc-123',
  total: 2,
  questions: [
    {
      questionId: 11,
      stem: '题1',
      chapter: '章A',
      type: 1,
      options: [
        { key: 'A', text: '甲' },
        { key: 'B', text: '乙' }
      ]
    },
    {
      questionId: 12,
      stem: '题2',
      chapter: '章B',
      type: 1,
      options: [
        { key: 'A', text: '丙' },
        { key: 'B', text: '丁' }
      ]
    }
  ]
}

async function mountDiag() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/diagnostic', name: 'diagnostic', component: Diagnostic },
      { path: '/diagnostic/adaptive', name: 'adaptive', component: { template: '<div />' } },
      { path: '/map', name: 'map', component: { template: '<div />' } },
      { path: '/result', name: 'result', component: { template: '<div />' } }
    ]
  })
  await router.push('/diagnostic')
  await router.isReady()
  return mount(Diagnostic, {
    global: {
      plugins: [router],
      stubs: {
        QuestionAnswerCard: {
          name: 'QuestionAnswerCard',
          template: '<div class="q-card-stub" />',
          props: ['question', 'index', 'total', 'selected', 'isLast', 'lastLabel', 'hasSelection']
        },
        'router-link': { template: '<a><slot /></a>' }
      }
    }
  })
}

describe('Diagnostic fixed paper sessionToken', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('wj_user', JSON.stringify({ id: 2, role: 2 }))
    diagnosticApi.fetchPaper.mockResolvedValue(samplePaper)
    diagnosticApi.submitPaper.mockResolvedValue({
      total: 2,
      correctCount: 1,
      grades: [
        { questionId: 11, correct: true, correctKey: 'A' },
        { questionId: 12, correct: false, correctKey: 'A' }
      ]
    })
  })

  afterEach(() => {
    localStorage.clear()
    if (typeof window !== 'undefined') {
      delete window.__wjDiag
    }
  })

  it('load stores sessionToken from paper', async () => {
    const wrapper = await mountDiag()
    // 选完整卷进入固定流程（触发 load via courseId watch already; mode still null）
    const fullBtn = wrapper.findAll('button').find((b) => b.text().includes('完整诊断'))
    expect(fullBtn).toBeTruthy()
    await fullBtn.trigger('click')
    await flushPromises()

    // DEV hook 暴露 sessionToken
    expect(window.__wjDiag).toBeTruthy()
    // 确保 paper 已加载（watch courseId immediate）
    await flushPromises()
    expect(diagnosticApi.fetchPaper).toHaveBeenCalledWith(5)
    expect(window.__wjDiag.sessionToken.value).toBe('tok-abc-123')
  })

  it('submit sends only sessionToken + full answer set', async () => {
    const wrapper = await mountDiag()
    await flushPromises()
    const fullBtn = wrapper.findAll('button').find((b) => b.text().includes('完整诊断'))
    await fullBtn.trigger('click')
    await flushPromises()

    const diag = window.__wjDiag
    diag.pick(0) // 第 1 题选 A
    await nextTick()
    // 跳过第 2 题：直接 finish
    await diag.finish()
    await flushPromises()

    expect(diagnosticApi.submitPaper).toHaveBeenCalledTimes(1)
    const payload = diagnosticApi.submitPaper.mock.calls[0][0]
    expect(payload.sessionToken).toBe('tok-abc-123')
    expect(payload.studentId).toBeUndefined()
    expect(payload.answers).toHaveLength(2)
    expect(payload.answers[0]).toEqual({ questionId: 11, optionKey: 'A' })
    expect(payload.answers[1]).toEqual({ questionId: 12, optionKey: null })
  })

  it('missing sessionToken forces recompose via load', async () => {
    const wrapper = await mountDiag()
    await flushPromises()
    const fullBtn = wrapper.findAll('button').find((b) => b.text().includes('完整诊断'))
    await fullBtn.trigger('click')
    await flushPromises()

    const diag = window.__wjDiag
    // 模拟令牌丢失
    diag.sessionToken.value = null
    diagnosticApi.fetchPaper.mockClear()
    diagnosticApi.fetchPaper.mockResolvedValue({
      ...samplePaper,
      sessionToken: 'tok-new'
    })

    await diag.submitAnswers()
    await flushPromises()

    expect(diagnosticApi.submitPaper).not.toHaveBeenCalled()
    expect(diagnosticApi.fetchPaper).toHaveBeenCalled()
    expect(diag.sessionToken.value).toBe('tok-new')
  })
})
