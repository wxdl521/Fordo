import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminLayout from '../AdminLayout.vue'
import { useSessionStore, TOKEN_KEY, USER_KEY } from '../../store/session.js'

async function mountLayout(startPath = '/admin/users') {
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
      { path: '/admin', redirect: '/admin/overview' },
      { path: '/admin/overview', component: { template: '<div data-testid="child-page">总览页</div>' } },
      { path: '/admin/users', component: { template: '<div data-testid="child-page">用户页</div>' } },
      { path: '/admin/courses', component: { template: '<div data-testid="child-page">课程页</div>' } },
      { path: '/admin/clazzes', component: { template: '<div data-testid="child-page">班级页</div>' } },
      { path: '/admin/clazzes/:id', component: { template: '<div data-testid="child-page">班级详情页</div>' } },
      { path: '/admin/audit-logs', component: { template: '<div data-testid="child-page">操作日志页</div>' } },
      { path: '/admin/login-logs', component: { template: '<div data-testid="child-page">登录日志页</div>' } }
    ]
  })
  await router.push(startPath)
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

  it('lists the sidebar in order: overview, users, courses, classes, audit logs, login logs', async () => {
    const { wrapper } = await mountLayout('/admin/audit-logs')

    const links = wrapper.findAll('nav a')
    expect(links.map((a) => a.text())).toEqual(['平台总览', '用户管理', '课程管理', '班级管理', '操作日志', '登录日志'])
    expect(links.map((a) => a.attributes('href'))).toEqual([
      '/admin/overview',
      '/admin/users',
      '/admin/courses',
      '/admin/clazzes',
      '/admin/audit-logs',
      '/admin/login-logs'
    ])
    expect(links.map((a) => a.attributes('data-testid'))).toEqual([
      'admin-nav-overview',
      'admin-nav-users',
      'admin-nav-courses',
      'admin-nav-clazzes',
      'admin-nav-audit-logs',
      'admin-nav-login-logs'
    ])
    // 只有当前页高亮
    expect(links.map((a) => a.attributes('aria-current'))).toEqual([undefined, undefined, undefined, undefined, 'page', undefined])
    wrapper.unmount()
  })

  it('keeps the class nav item highlighted on a class detail page', async () => {
    const { wrapper } = await mountLayout('/admin/clazzes/12')

    expect(wrapper.get('[data-testid="child-page"]').text()).toBe('班级详情页')
    expect(wrapper.get('[data-testid="admin-nav-clazzes"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-testid="admin-nav-courses"]').attributes('aria-current')).toBeUndefined()
    wrapper.unmount()
  })

  it('wraps the shared theme toggle so the narrow top bar can keep it on one line', async () => {
    const { wrapper } = await mountLayout()

    const box = wrapper.get('.wj-admin-theme')
    expect(box.text()).toBe('墨夜暖纸')
    // flex: none —— 窄屏下不允许被压缩
    expect(box.element.style.flexShrink).toBe('0')
    wrapper.unmount()
  })

  it('the brand link and a bare /admin land on the overview page', async () => {
    const { wrapper, router } = await mountLayout('/admin')

    expect(router.currentRoute.value.path).toBe('/admin/overview')
    expect(wrapper.get('[data-testid="child-page"]').text()).toBe('总览页')
    expect(wrapper.get('[data-testid="admin-nav-overview"]').attributes('aria-current')).toBe('page')
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
