import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../api/user.js', () => ({ me: vi.fn() }))

import { router } from '../index.js'
import { pinia } from '../../store/index.js'
import { useSessionStore } from '../../store/session.js'

/** 直接放一个已「刷新完成」的会话，避免守卫去打 /api/me。 */
function signIn(user) {
  const session = useSessionStore(pinia)
  session.setSession({ token: 'test-token', user })
  session.hydrated = true
  session._refreshPromise = Promise.resolve(user)
  return session
}

async function visit(path) {
  await router.push(path)
  return router.currentRoute.value.path
}

describe('admin route guard', () => {
  beforeEach(async () => {
    localStorage.clear()
    useSessionStore(pinia).clearSession()
    await router.push('/')
  })

  afterEach(() => {
    const session = useSessionStore(pinia)
    session.clearSession()
    session._refreshPromise = null
  })

  it('registers /admin with nested overview / users / courses / classes / audit / login-log pages and a default redirect', () => {
    const routes = router.getRoutes()
    expect(routes.find((r) => r.path === '/admin')).toBeTruthy()
    const expected = [
      ['admin-overview', '/admin/overview', '平台总览'],
      ['admin-users', '/admin/users', '用户管理'],
      ['admin-courses', '/admin/courses', '课程管理'],
      ['admin-clazzes', '/admin/clazzes', '班级管理'],
      ['admin-clazz-detail', '/admin/clazzes/:id', '班级详情'],
      ['admin-audit-logs', '/admin/audit-logs', '操作日志'],
      ['admin-login-logs', '/admin/login-logs', '登录日志']
    ]
    for (const [name, path, title] of expected) {
      const route = routes.find((r) => r.name === name)
      expect(route, name).toBeTruthy()
      expect(route.path).toBe(path)
      expect(route.meta.title).toBe(title)
    }
  })

  it('sends an anonymous visitor back to the login page', async () => {
    useSessionStore(pinia).hydrated = true
    useSessionStore(pinia)._refreshPromise = Promise.resolve(null)
    expect(await visit('/admin/users')).toBe('/')
  })

  it.each([
    ['student', { id: 3, role: 2 }],
    ['teacher', { id: 2, role: 1 }]
  ])('kicks a %s out of every /admin page to /', async (_label, user) => {
    signIn(user)
    for (const path of [
      '/admin',
      '/admin/overview',
      '/admin/users',
      '/admin/courses',
      '/admin/clazzes',
      '/admin/clazzes/3',
      '/admin/audit-logs',
      '/admin/login-logs'
    ]) {
      expect(await visit(path), path).toBe('/')
    }
  })

  it('lets an administrator in; /admin lands on /admin/overview', async () => {
    signIn({ id: 1, role: 3, username: 'root', realName: '管理员' })
    expect(await visit('/admin')).toBe('/admin/overview')
    expect(router.currentRoute.value.name).toBe('admin-overview')
  })

  it('lets an administrator open the audit and login log pages directly', async () => {
    signIn({ id: 1, role: 3, username: 'root', realName: '管理员' })
    expect(await visit('/admin/audit-logs')).toBe('/admin/audit-logs')
    expect(router.currentRoute.value.name).toBe('admin-audit-logs')
    expect(await visit('/admin/login-logs')).toBe('/admin/login-logs')
    expect(router.currentRoute.value.name).toBe('admin-login-logs')
  })

  it('lets an administrator open the course and class pages (incl. the class detail route)', async () => {
    signIn({ id: 1, role: 3, username: 'root', realName: '管理员' })
    expect(await visit('/admin/courses')).toBe('/admin/courses')
    expect(router.currentRoute.value.name).toBe('admin-courses')
    expect(await visit('/admin/clazzes')).toBe('/admin/clazzes')
    expect(router.currentRoute.value.name).toBe('admin-clazzes')
    expect(await visit('/admin/clazzes/7')).toBe('/admin/clazzes/7')
    expect(router.currentRoute.value.name).toBe('admin-clazz-detail')
    expect(router.currentRoute.value.params.id).toBe('7')
  })

  it('keeps the existing teacher-side rule: admins and teachers pass, students do not', async () => {
    signIn({ id: 1, role: 3 })
    expect(await visit('/teacher/reports')).toBe('/teacher/reports')

    await router.push('/')
    signIn({ id: 2, role: 1 })
    expect(await visit('/teacher/reports')).toBe('/teacher/reports')

    await router.push('/')
    signIn({ id: 3, role: 2 })
    expect(await visit('/teacher/reports')).toBe('/')
  })
})
