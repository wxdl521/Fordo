import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import Login from '../Login.vue'
import * as userApi from '../../api/user.js'
import * as courseApi from '../../api/course.js'

vi.mock('../../api/user.js', () => ({
  login: vi.fn(),
  register: vi.fn(),
  me: vi.fn()
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

async function mountLogin() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: Login },
      { path: '/teacher/graph', name: 'teacher', component: { template: '<div />' } },
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

  it('register request body contains only username, password, realName', async () => {
    const wrapper = await mountLogin()
    const switchBtn = wrapper.findAll('button').find((b) => b.text().includes('没有账号？注册'))
    await switchBtn.trigger('click')
    await nextTick()

    const inputs = wrapper.findAll('input')
    // 用户名、密码、确认密码、真实姓名
    await inputs[0].setValue('new_student')
    await inputs[1].setValue('safe-password-123')
    await inputs[2].setValue('safe-password-123')
    await inputs[3].setValue('新学生')

    const registerBtn = wrapper.findAll('button').find((b) => b.text().includes('注 册'))
    await registerBtn.trigger('click')
    await flushPromises()

    expect(userApi.register).toHaveBeenCalledTimes(1)
    const payload = userApi.register.mock.calls[0][0]
    expect(payload).toEqual({
      username: 'new_student',
      password: 'safe-password-123',
      realName: '新学生'
    })
    expect(Object.keys(payload).sort()).toEqual(['password', 'realName', 'username'])
    expect(payload).not.toHaveProperty('role')
    wrapper.unmount()
  })
})
