import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '../../store/session.js'
import { useCourseStore } from '../../store/course.js'

const fetchHintsCount = vi.fn()
vi.mock('../../api/companion.js', () => ({ fetchHintsCount: (...a) => fetchHintsCount(...a) }))

import TopBar from '../TopBar.vue'

async function mountBar() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useSessionStore(pinia).setSession({ token: 't', user: { id: 2, role: 2, realName: 's' } })
  useCourseStore(pinia).setStudentCourseId(5)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }]
  })
  await router.push('/map')
  await router.isReady()
  const wrapper = mount(TopBar, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('TopBar companion hint dot', () => {
  beforeEach(() => { fetchHintsCount.mockReset() })

  it('shows the dot when count > 0', async () => {
    fetchHintsCount.mockResolvedValue({ count: 2 })
    const { wrapper } = await mountBar()
    expect(fetchHintsCount).toHaveBeenCalledWith(5)
    expect(wrapper.find('[data-testid="companion-hint-dot"]').exists()).toBe(true)
  })

  it('hides the dot when count is 0', async () => {
    fetchHintsCount.mockResolvedValue({ count: 0 })
    const { wrapper } = await mountBar()
    expect(wrapper.find('[data-testid="companion-hint-dot"]').exists()).toBe(false)
  })

  it('hides the dot silently on failure and refreshes on route change', async () => {
    fetchHintsCount.mockRejectedValueOnce(new Error('404'))
    const { wrapper, router } = await mountBar()
    expect(wrapper.find('[data-testid="companion-hint-dot"]').exists()).toBe(false)
    fetchHintsCount.mockResolvedValue({ count: 1 })
    await router.push('/path')
    await flushPromises()
    expect(wrapper.find('[data-testid="companion-hint-dot"]').exists()).toBe(true)
  })
})
