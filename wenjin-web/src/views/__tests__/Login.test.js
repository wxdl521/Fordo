import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import Login from '../Login.vue'
import * as userApi from '../../api/user.js'
import * as courseApi from '../../api/course.js'
import { useSessionStore } from '../../store/session.js'

vi.mock('../../api/user.js', () => ({
  login: vi.fn(),
  register: vi.fn(),
  me: vi.fn(),
  sendEmailCode: vi.fn()
}))

vi.mock('../../api/course.js', () => ({
  getMyCourses: vi.fn().mockResolvedValue([]),
  getAvailableCourses: vi.fn().mockResolvedValue([]),
  enroll: vi.fn()
}))

vi.mock('../../composables/useTheme.js', () => ({
  useTheme: () => ({
    theme: { value: 'paper' },
    setInk: vi.fn(),
    setPaper: vi.fn()
  })
}))

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function loginResult(token, id, realName) {
  return {
    token,
    user: { id, username: `student-${id}`, realName, role: 2, status: 1 }
  }
}

function buttonWithText(wrapper, text) {
  return wrapper.findAll('button').find((button) => button.text().includes(text))
}

async function mountLogin() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: Login },
      { path: '/teacher/graph', name: 'teacher', component: { template: '<div />' } },
      { path: '/admin', name: 'admin', redirect: '/admin/overview' },
      { path: '/admin/overview', name: 'admin-overview', component: { template: '<div />' } },
      { path: '/map', name: 'map', component: { template: '<div />' } },
      { path: '/diagnostic', name: 'diagnostic', component: { template: '<div />' } },
      { path: '/growth', name: 'growth', component: { template: '<div />' } }
    ]
  })
  await router.push('/')
  await router.isReady()
  return mount(Login, {
    global: {
      plugins: [pinia, router]
    }
  })
}

describe('Login registration security', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    userApi.register.mockResolvedValue({
      id: 10,
      username: 'new_student',
      realName: '新学生',
      role: 2,
      status: 1
    })
    userApi.login.mockResolvedValue({
      token: 'test-token',
      user: {
        id: 10,
        username: 'new_student',
        realName: '新学生',
        role: 2,
        status: 1
      }
    })
    courseApi.getMyCourses.mockResolvedValue([])
    courseApi.getAvailableCourses.mockResolvedValue([])
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('register form has no teacher registration option', async () => {
    const wrapper = await mountLogin()
    const switchBtn = wrapper.findAll('button').find((b) => b.text().includes('没有账号？注册'))
    expect(switchBtn).toBeTruthy()
    await switchBtn.trigger('click')
    await nextTick()

    const text = wrapper.text()
    expect(text).toContain('注册账号')
    expect(text).not.toContain('教师注册')
    expect(text).not.toMatch(/角色/)
    // 不得出现可选的教师/学生角色切换
    const labels = wrapper.findAll('label').map((l) => l.text())
    expect(labels).not.toContain('角色')
    expect(wrapper.findAll('.wj-role-btn')).toHaveLength(0)
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('教师')
    wrapper.unmount()
  })

  it('register request body contains username, password, realName, email and code', async () => {
    const wrapper = await mountLogin()
    const switchBtn = wrapper.findAll('button').find((b) => b.text().includes('没有账号？注册'))
    await switchBtn.trigger('click')
    await nextTick()

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('new_student')
    await inputs[1].setValue('safe-password-123')
    await inputs[2].setValue('safe-password-123')
    await inputs[3].setValue('新学生')
    await inputs[4].setValue('new@example.com')
    await inputs[5].setValue('123456')

    const registerBtn = wrapper.findAll('button').find((b) => b.text().includes('注 册'))
    await registerBtn.trigger('click')
    await flushPromises()

    expect(userApi.register).toHaveBeenCalledTimes(1)
    const payload = userApi.register.mock.calls[0][0]
    expect(payload).toEqual({
      username: 'new_student',
      password: 'safe-password-123',
      realName: '新学生',
      email: 'new@example.com',
      code: '123456'
    })
    expect(Object.keys(payload).sort()).toEqual(['code', 'email', 'password', 'realName', 'username'])
    expect(payload).not.toHaveProperty('role')
    wrapper.unmount()
  })

  it('sends email code before register', async () => {
    userApi.sendEmailCode.mockResolvedValue({})
    const wrapper = await mountLogin()
    const switchBtn = wrapper.findAll('button').find((b) => b.text().includes('没有账号？注册'))
    await switchBtn.trigger('click')
    await nextTick()
    const inputs = wrapper.findAll('input')
    await inputs[4].setValue('new@example.com')
    const sendBtn = wrapper.findAll('button').find((b) => b.text().includes('发送验证码'))
    await sendBtn.trigger('click')
    await flushPromises()
    expect(userApi.sendEmailCode).toHaveBeenCalledWith({ email: 'new@example.com' })
    wrapper.unmount()
  })

  it('keeps the newest session when an older login succeeds late', async () => {
    const staleLogin = deferred()
    userApi.login
      .mockImplementationOnce(() => staleLogin.promise)
      .mockResolvedValueOnce(loginResult('token-b', 2, 'Student B'))

    const wrapper = await mountLogin()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('student-a')
    await inputs[1].setValue('password-a')
    await wrapper.find('.wj-btn-acc').trigger('click')
    await buttonWithText(wrapper, '演示账号').trigger('click')
    await flushPromises()

    const session = useSessionStore()
    expect(session.token).toBe('token-b')
    expect(session.user.id).toBe(2)

    staleLogin.resolve(loginResult('token-a', 1, 'Student A'))
    await flushPromises()

    expect(session.token).toBe('token-b')
    expect(session.user.id).toBe(2)
    wrapper.unmount()
  })

  it('does not revive a logged-out session from a late login success', async () => {
    const staleLogin = deferred()
    userApi.login
      .mockImplementationOnce(() => staleLogin.promise)
      .mockResolvedValueOnce(loginResult('token-b', 2, 'Student B'))

    const wrapper = await mountLogin()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('student-a')
    await inputs[1].setValue('password-a')
    await wrapper.find('.wj-btn-acc').trigger('click')
    await buttonWithText(wrapper, '演示账号').trigger('click')
    await flushPromises()
    await buttonWithText(wrapper, '退出登录').trigger('click')

    const session = useSessionStore()
    expect(session.token).toBeNull()

    staleLogin.resolve(loginResult('token-a', 1, 'Student A'))
    await flushPromises()

    expect(session.token).toBeNull()
    expect(session.user).toBeNull()
    expect(wrapper.text()).toContain('欢迎回来')
    wrapper.unmount()
  })

  it('ignores stale course data and keeps the latest load pending', async () => {
    const coursesA = deferred()
    const coursesB = deferred()
    userApi.login
      .mockResolvedValueOnce(loginResult('token-a', 1, 'Student A'))
      .mockResolvedValueOnce(loginResult('token-b', 2, 'Student B'))
    courseApi.getMyCourses
      .mockImplementationOnce(() => coursesA.promise)
      .mockImplementationOnce(() => coursesB.promise)
    courseApi.getAvailableCourses.mockResolvedValue([])

    const wrapper = await mountLogin()
    let inputs = wrapper.findAll('input')
    await inputs[0].setValue('student-a')
    await inputs[1].setValue('password-a')
    await wrapper.find('.wj-btn-acc').trigger('click')
    await flushPromises()
    await buttonWithText(wrapper, '退出登录').trigger('click')

    inputs = wrapper.findAll('input')
    await inputs[0].setValue('student-b')
    await inputs[1].setValue('password-b')
    await wrapper.find('.wj-btn-acc').trigger('click')
    await flushPromises()

    coursesA.resolve([{
      courseId: 101,
      name: 'Course A',
      masteredCount: 1,
      weakCount: 0,
      unlearnedCount: 0
    }])
    await flushPromises()

    expect(courseApi.getAvailableCourses).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('加载中...')
    expect(wrapper.text()).not.toContain('Course A')

    coursesB.resolve([{
      courseId: 202,
      name: 'Course B',
      masteredCount: 1,
      weakCount: 0,
      unlearnedCount: 0
    }])
    await flushPromises()

    expect(courseApi.getAvailableCourses).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Course B')
    expect(wrapper.text()).not.toContain('Course A')
    wrapper.unmount()
  })
})

describe('Login admin routing', () => {
  const adminUser = { id: 1, username: 'root', realName: '系统管理员', role: 3, status: 1 }

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    courseApi.getMyCourses.mockResolvedValue([])
    courseApi.getAvailableCourses.mockResolvedValue([])
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('sends an administrator straight to the admin overview after login, skipping the course page data', async () => {
    userApi.login.mockResolvedValue({ token: 'admin-token', user: adminUser })

    const wrapper = await mountLogin()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('root')
    await inputs[1].setValue('admin-password')
    await wrapper.find('.wj-btn-acc').trigger('click')
    await flushPromises()

    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/admin/overview')
    expect(courseApi.getMyCourses).not.toHaveBeenCalled()
    expect(useSessionStore().isAdmin).toBe(true)
    wrapper.unmount()
  })

  it('keeps students on the course page after login (no admin redirect)', async () => {
    userApi.login.mockResolvedValue(loginResult('student-token', 7, '小明'))

    const wrapper = await mountLogin()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('student-7')
    await inputs[1].setValue('student-password')
    await wrapper.find('.wj-btn-acc').trigger('click')
    await flushPromises()

    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/')
    expect(courseApi.getMyCourses).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it.each([
    ['administrator', { ...adminUser }, { admin: true, teacher: true }],
    ['teacher', { id: 2, username: 't', realName: '老师', role: 1, status: 1 }, { admin: false, teacher: true }],
    ['student', { id: 3, username: 's', realName: '同学', role: 2, status: 1 }, { admin: false, teacher: false }]
  ])('course page entries for a restored %s session', async (_label, user, expected) => {
    localStorage.setItem('wj_token', 'restored-token')
    localStorage.setItem('wj_user', JSON.stringify(user))
    userApi.me.mockResolvedValue(user)

    const wrapper = await mountLogin()
    await flushPromises()

    // 已登录状态回到 / → 课程页；管理员有「进入管理后台」入口，且不会被自动弹走
    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/')
    expect(wrapper.find('[data-testid="enter-admin"]').exists()).toBe(expected.admin)
    expect(wrapper.text().includes('进入教师端')).toBe(expected.teacher)
    if (expected.admin) {
      expect(wrapper.get('[data-testid="enter-admin"]').attributes('href')).toBe('/admin')
    }
    wrapper.unmount()
  })
})
