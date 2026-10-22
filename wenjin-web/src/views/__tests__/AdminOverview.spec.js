import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  getOverview: vi.fn()
}))

// echarts 在 happy-dom 里没有 canvas：用假图表替身，只验证「按需加载 + 喂了正确的 option」
const trend = vi.hoisted(() => ({
  charts: []
}))

vi.mock('../../api/systemAdmin.js', () => api)
vi.mock('../../utils/echartsTrend.js', () => ({
  initTrendChart: vi.fn((el) => {
    const chart = { el, setOption: vi.fn(), resize: vi.fn(), getDom: () => el }
    trend.charts.push(chart)
    return chart
  }),
  disposeTrendChart: vi.fn((chart) => {
    chart.disposed = true
  })
}))

import AdminOverview from '../AdminOverview.vue'
import { initTrendChart, disposeTrendChart } from '../../utils/echartsTrend.js'

function overview(overrides = {}) {
  return {
    users: { total: 1200, teachers: 10, students: 1188, admins: 2, disabled: 3 },
    courses: { total: 8, published: 5, draft: 2, archived: 1 },
    activity: {
      todayActiveUsers: 12,
      todayLoginFailures: 2,
      daily: [
        { date: '2026-10-14', logins: 30, activeUsers: 11, failures: 1 },
        { date: '2026-10-15', logins: 41, activeUsers: 12, failures: 2 }
      ]
    },
    ai: {
      runs: 1234,
      failedRuns: 5,
      tokensIn: 1000000,
      tokensOut: 234567,
      avgDurationMs: 2400,
      daily: [
        { date: '2026-10-14', runs: 600, tokens: 700000 },
        { date: '2026-10-15', runs: 634, tokens: 534567 }
      ],
      byKind: [
        { kind: 'COMPANION_CHAT', runs: 1000, tokens: 1100000 },
        { kind: 'PRACTICE_HINT', runs: 234, tokens: 134567 }
      ],
      byModel: [{ model: 'deepseek-v4-pro', runs: 1234, tokens: 1234567 }],
      jobs: {
        total: 20,
        succeeded: 17,
        failed: 3,
        byKind: [
          { kind: 'QUESTION_GENERATE', count: 12 },
          { kind: 'GRAPH_EXTRACT', count: 8 }
        ]
      }
    },
    ...overrides
  }
}

async function mountPage() {
  const wrapper = mount(AdminOverview)
  await flushPromises()
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()
const cardValue = (wrapper, key) => tid(wrapper, `admin-overview-card-${key}-value`).text()

describe('AdminOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    trend.charts.length = 0
    api.getOverview.mockResolvedValue(overview())
  })

  it('loads the 7-day overview on mount', async () => {
    const wrapper = await mountPage()

    expect(api.getOverview).toHaveBeenCalledTimes(1)
    expect(api.getOverview).toHaveBeenCalledWith({ days: 7 }, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(has(wrapper, 'admin-overview')).toBe(true)
    expect(tid(wrapper, 'admin-overview-days-7').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })

  it('renders the number cards for users, courses, activity and AI usage', async () => {
    const wrapper = await mountPage()

    expect(cardValue(wrapper, 'users-total')).toBe('1,200')
    expect(tid(wrapper, 'admin-overview-card-users-total').text()).toContain('含管理员 2')
    expect(cardValue(wrapper, 'users-teachers')).toBe('10')
    expect(cardValue(wrapper, 'users-students')).toBe('1,188')
    expect(cardValue(wrapper, 'users-disabled')).toBe('3')

    expect(cardValue(wrapper, 'courses-total')).toBe('8')
    expect(cardValue(wrapper, 'courses-published')).toBe('5')
    expect(cardValue(wrapper, 'courses-draft')).toBe('2')
    expect(cardValue(wrapper, 'courses-archived')).toBe('1')

    expect(cardValue(wrapper, 'today-active')).toBe('12')
    expect(cardValue(wrapper, 'today-failures')).toBe('2')

    expect(cardValue(wrapper, 'ai-runs')).toBe('1,234')
    expect(cardValue(wrapper, 'ai-failed')).toBe('5')
    expect(cardValue(wrapper, 'ai-tokens')).toBe('1,234,567')
    expect(tid(wrapper, 'admin-overview-card-ai-tokens').text()).toContain('输入 1,000,000 · 输出 234,567')
    expect(cardValue(wrapper, 'ai-avg')).toBe('2.4 秒')
    wrapper.unmount()
  })

  it('renders the AI by-kind / by-model tables, the jobs summary and the daily detail tables', async () => {
    const wrapper = await mountPage()

    expect(tid(wrapper, 'admin-overview-card-ai-runs').text()).toContain('教师助教')

    const kindRows = tid(wrapper, 'admin-overview-ai-kind-table').findAll('tbody tr')
    expect(kindRows).toHaveLength(2)
    expect(kindRows[0].text()).toContain('COMPANION_CHAT')
    expect(kindRows[0].text()).toContain('1,000')
    expect(kindRows[0].text()).toContain('1,100,000')

    const modelRows = tid(wrapper, 'admin-overview-ai-model-table').findAll('tbody tr')
    expect(modelRows).toHaveLength(1)
    expect(modelRows[0].text()).toContain('deepseek-v4-pro')

    expect(tid(wrapper, 'admin-overview-jobs-summary').text().replace(/\s+/g, ' ')).toBe('共 20 个 · 成功 17 · 失败 3')
    const jobRows = tid(wrapper, 'admin-overview-jobs-table').findAll('tbody tr')
    expect(jobRows.map((r) => r.text())).toEqual(['QUESTION_GENERATE12', 'GRAPH_EXTRACT8'])

    expect(tid(wrapper, 'admin-overview-activity-table').findAll('tbody tr')).toHaveLength(2)
    expect(tid(wrapper, 'admin-overview-ai-daily-table').findAll('tbody tr')).toHaveLength(2)
    wrapper.unmount()
  })

  it('shows dashes / empty states when there is no AI activity', async () => {
    api.getOverview.mockResolvedValue(
      overview({
        ai: {
          runs: 0, failedRuns: 0, tokensIn: 0, tokensOut: 0, avgDurationMs: 0,
          daily: [], byKind: [], byModel: [], jobs: { total: 0, succeeded: 0, failed: 0, byKind: [] }
        }
      })
    )
    const wrapper = await mountPage()

    expect(cardValue(wrapper, 'ai-runs')).toBe('0')
    expect(cardValue(wrapper, 'ai-avg')).toBe('—')
    expect(has(wrapper, 'admin-overview-ai-kind-empty')).toBe(true)
    expect(has(wrapper, 'admin-overview-ai-model-empty')).toBe(true)
    expect(has(wrapper, 'admin-overview-jobs-empty')).toBe(true)
    wrapper.unmount()
  })

  it('draws the two trend charts lazily with the daily series', async () => {
    const wrapper = await mountPage()

    expect(initTrendChart).toHaveBeenCalledTimes(2)
    const [activity, ai] = trend.charts
    expect(activity.el).toBe(tid(wrapper, 'admin-overview-chart-activity').element)
    expect(ai.el).toBe(tid(wrapper, 'admin-overview-chart-ai').element)

    const activityOption = activity.setOption.mock.calls[0][0]
    expect(activityOption.xAxis.data).toEqual(['2026-10-14', '2026-10-15'])
    expect(activityOption.series.map((s) => s.name)).toEqual(['登录次数', '活跃用户', '登录失败'])
    expect(activityOption.series[0].data).toEqual([30, 41])

    const aiOption = ai.setOption.mock.calls[0][0]
    expect(aiOption.series.map((s) => s.name)).toEqual(['调用次数', 'Tokens'])
    expect(aiOption.series[1].data).toEqual([700000, 534567])
    wrapper.unmount()
  })

  it('switching to 14 / 30 days refetches, updates the cards and redraws on the same chart instances', async () => {
    const wrapper = await mountPage()
    api.getOverview.mockResolvedValue(overview({ users: { total: 5, teachers: 1, students: 3, admins: 1, disabled: 0 } }))

    await tid(wrapper, 'admin-overview-days-14').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(api.getOverview).toHaveBeenLastCalledWith({ days: 14 }, expect.anything())
    expect(tid(wrapper, 'admin-overview-days-14').attributes('aria-pressed')).toBe('true')
    expect(tid(wrapper, 'admin-overview-days-7').attributes('aria-pressed')).toBe('false')
    expect(cardValue(wrapper, 'users-total')).toBe('5')
    expect(initTrendChart).toHaveBeenCalledTimes(2) // 复用同一实例
    expect(trend.charts[0].setOption).toHaveBeenCalledTimes(2)

    await tid(wrapper, 'admin-overview-days-30').trigger('click')
    await flushPromises()
    expect(api.getOverview).toHaveBeenLastCalledWith({ days: 30 }, expect.anything())

    // 点当前已选中的天数不重复请求
    api.getOverview.mockClear()
    await tid(wrapper, 'admin-overview-days-30').trigger('click')
    expect(api.getOverview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the backend message with a retry button when loading fails', async () => {
    api.getOverview.mockRejectedValueOnce(new Error('无访问权限'))
    const wrapper = await mountPage()

    expect(tid(wrapper, 'admin-overview-error').text()).toContain('无访问权限')
    expect(has(wrapper, 'admin-overview-card-users-total')).toBe(false)
    expect(initTrendChart).not.toHaveBeenCalled()

    await tid(wrapper, 'admin-overview-retry').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(has(wrapper, 'admin-overview-error')).toBe(false)
    expect(cardValue(wrapper, 'users-total')).toBe('1,200')
    wrapper.unmount()
  })

  it('disposes both charts on unmount', async () => {
    const wrapper = await mountPage()
    wrapper.unmount()

    expect(disposeTrendChart).toHaveBeenCalledTimes(2)
    expect(trend.charts.every((c) => c.disposed)).toBe(true)
  })

  it('keeps the cards when drawing fails and points to the detail tables', async () => {
    initTrendChart.mockImplementationOnce(() => {
      throw new Error('canvas unavailable')
    })
    const wrapper = await mountPage()

    expect(tid(wrapper, 'admin-overview-chart-error').text()).toContain('趋势图加载失败')
    expect(cardValue(wrapper, 'users-total')).toBe('1,200')
    expect(has(wrapper, 'admin-overview-activity-table')).toBe(true)
    wrapper.unmount()
  })
})
