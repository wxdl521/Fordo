import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminLayout from '../AdminLayout.vue'
import { useSessionStore, TOKEN_KEY, USER_KEY } from '../../store/session.js'

async function mountLayout() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.setSession({
    token: 'admin-token',
    user: { id: 1, username: 'root', realName: '系统管理员', role: 3, status: 1 }
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: { template: '<div data-testid="login-page" />' } },
      { path: '/teacher/graph', component: { template: '<div />' } },
      { path: '/admin', redirect: '/admin/users' },
      { path: '/admin/users', component: { template: '<div data-testid="child-page">用户页</div>' } }
    ]
  })
  await router.push('/admin/users')
  await router.isReady()
  const wrapper = mount(AdminLayout, { global: { plugins: [pinia, router] } })
  return { wrapper, router, session }
}

describe('AdminLayout', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders the standalone admin chrome around the routed page', async () => {
    const { wrapper } = await mountLayout()

    expect(wrapper.get('[data-testid="admin-topbar"]').text()).toContain('管理后台')
    expect(wrapper.get('[data-testid="admin-name"]').text()).toBe('系统管理员')
    expect(wrapper.get('[data-testid="child-page"]').text()).toBe('用户页')

    const usersNav = wrapper.get('[data-testid="admin-nav-users"]')
    expect(usersNav.text()).toBe('用户管理')
    expect(usersNav.attributes('href')).toBe('/admin/users')
    expect(usersNav.attributes('aria-current')).toBe('page')
    wrapper.unmount()
  })

  it('offers a link into the teacher side (admins keep teacher access)', async () => {
    const { wrapper } = await mountLayout()

    const link = wrapper.get('[data-testid="admin-enter-teacher"]')
    expect(link.text()).toBe('进入教师端')
    expect(link.attributes('href')).toBe('/teacher/graph')
    wrapper.unmount()
  })

  it('logs out like TopBar: clears the session and returns to the login page', async () => {
    const { wrapper, router, session } = await mountLayout()
    expect(localStorage.getItem(TOKEN_KEY)).toBe('admin-token')

    await wrapper.get('[data-testid="admin-logout"]').trigger('click')
    await flushPromises()

    expect(session.token).toBeNull()
    expect(session.user).toBeNull()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(USER_KEY)).toBeNull()
    expect(router.currentRoute.value.path).toBe('/')
    wrapper.unmount()
  })
})
