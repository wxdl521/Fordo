import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('../components/TopBar.vue', () => ({
  default: { name: 'TopBar', template: '<div data-testid="global-topbar" />' }
}))
// 新手导览依赖 Pinia，与本用例（只测顶栏显隐）无关
vi.mock('../components/SpotlightTour.vue', () => ({
  default: { name: 'SpotlightTour', template: '<div />' }
}))

import App from '../App.vue'

const Stub = { template: '<div />' }

async function mountAt(path) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Stub },
      { path: '/map', component: Stub },
      { path: '/teacher/graph', component: Stub },
      { path: '/admin', component: Stub },
      { path: '/admin/users', component: Stub }
    ]
  })
  await router.push(path)
  await router.isReady()
  return mount(App, { global: { plugins: [router] } })
}

describe('App global TopBar visibility', () => {
  it.each([
    ['/', false],
    ['/map', true],
    ['/teacher/graph', true],
    ['/admin', false],
    ['/admin/users', false]
  ])('route %s → TopBar shown: %s', async (path, shown) => {
    const wrapper = await mountAt(path)
    expect(wrapper.find('[data-testid="global-topbar"]').exists()).toBe(shown)
    wrapper.unmount()
  })
})
