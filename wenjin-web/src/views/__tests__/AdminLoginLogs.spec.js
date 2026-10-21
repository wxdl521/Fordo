import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listLoginLogs: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)

import AdminLoginLogs from '../AdminLoginLogs.vue'

const LONG_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0'

const OK_LOGIN = {
  id: 1,
  userId: 9,
  username: 'root',
  success: true,
  failReason: null,
  ip: '10.0.0.8',
  userAgent: 'curl/8.4',
  createdAt: '2026-10-15T08:00:00'
}
const BAD_PASSWORD = {
  id: 2,
  userId: 7,
  username: 'stu1',
  success: false,
  failReason: 'BAD_CREDENTIALS',
  ip: '10.0.0.9',
  userAgent: LONG_UA,
  createdAt: '2026-10-15T08:05:00'
}
const DISABLED = {
  id: 3,
  userId: 6,
  username: 'gone',
  success: false,
  failReason: 'DISABLED',
  ip: null,
  userAgent: null,
  createdAt: '2026-10-15T08:10:00'
}
const UNKNOWN_REASON = {
  id: 4,
  userId: null,
  username: 'ghost',
  success: false,
  failReason: 'RATE_LIMITED',
  ip: '10.0.0.1',
  userAgent: 'x',
  createdAt: '2026-10-15T08:15:00'
}
const ROWS = [OK_LOGIN, BAD_PASSWORD, DISABLED, UNKNOWN_REASON]

function pageOf(records = ROWS, total = records.length) {
  return { records, total, page: 1, size: 20 }
}

async function mountPage() {
  const wrapper = mount(AdminLoginLogs)
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminLoginLogs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listLoginLogs.mockResolvedValue(pageOf())
  })

  it('loads page 1 on mount and renders every attempt', async () => {
    const wrapper = await mountPage()

    expect(api.listLoginLogs).toHaveBeenCalledTimes(1)
    expect(api.listLoginLogs).toHaveBeenCalledWith({ page: 1, size: 20 }, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(tid(wrapper, 'admin-login-table').findAll('tbody tr')).toHaveLength(4)

    const row = tid(wrapper, 'admin-login-row-1').text()
    expect(row).toContain('2026-10-15 08:00')
    expect(row).toContain('root')
    expect(row).toContain('10.0.0.8')
    expect(tid(wrapper, 'admin-login-result-1').text()).toBe('成功')
    expect(tid(wrapper, 'admin-login-total').text()).toBe('共 4 条')
    wrapper.unmount()
  })

  it('maps failure reasons to Chinese, leaves unknown codes as-is, and shows a dash for successes', async () => {
    const wrapper = await mountPage()

    expect(tid(wrapper, 'admin-login-reason-1').text()).toBe('—')
    expect(tid(wrapper, 'admin-login-result-2').text()).toBe('失败')
    expect(tid(wrapper, 'admin-login-reason-2').text()).toBe('用户名或密码错误')
    expect(tid(wrapper, 'admin-login-reason-3').text()).toBe('账号已禁用')
    expect(tid(wrapper, 'admin-login-reason-4').text()).toBe('RATE_LIMITED')
    wrapper.unmount()
  })

  it('truncates a long user agent but keeps the full text in the title', async () => {
    const wrapper = await mountPage()

    const ua = tid(wrapper, 'admin-login-ua-2')
    expect(ua.attributes('title')).toBe(LONG_UA)
    expect(ua.text().length).toBeLessThan(LONG_UA.length)
    expect(ua.text().endsWith('…')).toBe(true)
    expect(LONG_UA.startsWith(ua.text().slice(0, -1))).toBe(true)

    // 短 UA 原样；缺失给破折号、IP 同理
    expect(tid(wrapper, 'admin-login-ua-1').text()).toBe('curl/8.4')
    expect(tid(wrapper, 'admin-login-ua-3').text()).toBe('—')
    expect(tid(wrapper, 'admin-login-row-3').text()).toContain('—')
    wrapper.unmount()
  })

  it('search sends username / success (typed number) / date range and resets to page 1', async () => {
    const wrapper = await mountPage()

    await tid(wrapper, 'admin-login-username').setValue('  stu ')
    await tid(wrapper, 'admin-login-from').setValue('2026-10-10')
    await tid(wrapper, 'admin-login-to').setValue('2026-10-15')
    api.listLoginLogs.mockClear()
    await tid(wrapper, 'admin-login-success').setValue('0')
    await flushPromises()

    expect(api.listLoginLogs).toHaveBeenCalledTimes(1)
    expect(api.listLoginLogs).toHaveBeenLastCalledWith(
      { username: 'stu', success: 0, from: '2026-10-10', to: '2026-10-15', page: 1, size: 20 },
      expect.anything()
    )
    wrapper.unmount()
  })

  it('rejects an inverted date range without calling the API', async () => {
    const wrapper = await mountPage()
    await tid(wrapper, 'admin-login-from').setValue('2026-10-20')
    await tid(wrapper, 'admin-login-to').setValue('2026-10-01')
    api.listLoginLogs.mockClear()
    await tid(wrapper, 'admin-login-search').trigger('click')
    await flushPromises()

    expect(api.listLoginLogs).not.toHaveBeenCalled()
    expect(tid(wrapper, 'admin-login-range-error').text()).toBe('起始日期不能晚于结束日期')
    wrapper.unmount()
  })

  it('paginates with the applied filters and reset returns to the unfiltered first page', async () => {
    api.listLoginLogs.mockResolvedValue(pageOf(ROWS, 45))
    const wrapper = await mountPage()

    await tid(wrapper, 'admin-login-username').setValue('applied')
    await tid(wrapper, 'admin-login-search').trigger('click')
    await flushPromises()
    api.listLoginLogs.mockClear()
    await tid(wrapper, 'admin-login-next').trigger('click')
    await flushPromises()
    expect(api.listLoginLogs).toHaveBeenLastCalledWith({ username: 'applied', page: 2, size: 20 }, expect.anything())

    await tid(wrapper, 'admin-login-reset').trigger('click')
    await flushPromises()
    expect(api.listLoginLogs).toHaveBeenLastCalledWith({ page: 1, size: 20 }, expect.anything())
    expect(tid(wrapper, 'admin-login-username').element.value).toBe('')
    wrapper.unmount()
  })

  it('shows the empty state, and the backend message when loading fails', async () => {
    api.listLoginLogs.mockResolvedValueOnce(pageOf([], 0))
    const empty = await mountPage()
    expect(has(empty, 'admin-login-empty')).toBe(true)
    expect(has(empty, 'admin-login-pagination')).toBe(false)
    empty.unmount()

    api.listLoginLogs.mockRejectedValueOnce(new Error('无访问权限'))
    const failed = await mountPage()
    expect(tid(failed, 'admin-login-error').text()).toBe('无访问权限')
    failed.unmount()
  })
})
