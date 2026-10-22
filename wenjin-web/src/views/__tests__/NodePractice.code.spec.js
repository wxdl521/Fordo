import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ref } from 'vue'
import { useSessionStore } from '../../store/session.js'

const mocks = vi.hoisted(() => ({ startPractice: vi.fn(), submitPractice: vi.fn() }))
const courseState = vi.hoisted(() => ({ courseId: null }))

vi.mock('../../api/practice.js', () => ({
  startPractice: (...a) => mocks.startPractice(...a),
  submitPractice: (...a) => mocks.submitPractice(...a)
}))
vi.mock('../../composables/useStudentCourse.js', () => ({ useStudentCourse: () => courseState }))

import NodePractice from '../NodePractice.vue'

const STARTER = 'public class Main {\n    public static void main(String[] a) {}\n}'

async function mountPractice() {
  courseState.courseId = ref(1)
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 't', user: { id: 7, role: 2 } })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/practice', component: NodePractice }] })
  await router.push({ path: '/practice', query: { nodeId: '10' } })
  await router.isReady()
  const wrapper = mount(NodePractice, {
    global: { plugins: [pinia, router], stubs: { RouterLink: { template: '<a><slot /></a>' } } }
  })
  await flushPromises()
  return wrapper
}

describe('NodePractice CODE question', () => {
  beforeEach(() => {
    mocks.startPractice.mockReset()
    mocks.submitPractice.mockReset()
    mocks.startPractice.mockResolvedValue({
      sessionId: 5,
      node: { nodeId: 10, nodeCode: 'n', name: 'n' },
      questions: [{
        questionId: 77, chapter: 'c', stem: '计算 a+b', type: 5, options: [],
        starterCode: STARTER,
        sampleCases: [{ seq: 1, stdin: '1 2', expectedStdout: '3' }]
      }]
    })
  })

  it('prefills starter code, shows samples, submits the source as studentAnswer', async () => {
    mocks.submitPractice.mockResolvedValue({ graded: [] })
    const w = await mountPractice()
    const ta = w.find('textarea.cae-textarea')
    expect(ta.element.value).toBe(STARTER)
    expect(w.findAll('[data-testid="qa-sample"]')).toHaveLength(1)
    await ta.setValue(STARTER + '\n// edited')
    await w.find('.qa-btn-next').trigger('click')
    await flushPromises()
    const payload = JSON.stringify(mocks.submitPractice.mock.calls[0][1])
    expect(payload).toContain('// edited')
    expect(payload).toContain('"questionId":77')
  })

  it('submits unedited starter code when the student goes straight to submit', async () => {
    mocks.submitPractice.mockResolvedValue({ graded: [] })
    const w = await mountPractice()
    await w.find('.qa-btn-next').trigger('click')
    await flushPromises()
    expect(JSON.stringify(mocks.submitPractice.mock.calls[0][1])).toContain('public class Main')
  })

  it('renders codeResult verdict, counts and hides hidden-case details', async () => {
    mocks.submitPractice.mockResolvedValue({
      graded: [{
        questionId: 77, correct: false,
        codeResult: {
          verdict: 'WRONG_ANSWER', passedCases: 1, totalCases: 2, compileError: null, durationMs: 40,
          cases: [
            { index: 1, passed: true, hidden: false, verdict: 'ACCEPTED' },
            { index: 2, passed: false, hidden: true, verdict: 'WRONG_ANSWER', expectedPreview: 'SECRET' }
          ]
        }
      }]
    })
    const w = await mountPractice()
    await w.find('.qa-btn-next').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="code-verdict"]').text()).toBe('答案错误')
    expect(w.find('[data-testid="code-count"]').text()).toContain('1 / 2')
    expect(w.text()).not.toContain('SECRET')
  })

  it('shows an ungraded notice instead of right/wrong', async () => {
    mocks.submitPractice.mockResolvedValue({
      graded: [{ questionId: 77, correct: null, ungraded: true, message: '沙箱繁忙' }]
    })
    const w = await mountPractice()
    await w.find('.qa-btn-next').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="code-ungraded"]').text()).toContain('沙箱繁忙')
    expect(w.text()).toContain('未判分')
  })
})
