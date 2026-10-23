import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({ fetchTeacherReports: vi.fn() }))

vi.mock('../../api/teacher.js', () => ({
  fetchTeacherReports: (...args) => mocks.fetchTeacherReports(...args)
}))
vi.mock('../../composables/useTeacherCourse.js', async () => {
  const { ref } = await import('vue')
  const courseId = ref(7)
  return { useTeacherCourse: () => ({ courseId }) }
})

import TeacherReports from '../TeacherReports.vue'

const stubs = { 'router-link': { template: '<a><slot /></a>' } }

function report(payload) {
  return {
    id: 1,
    kind: 0,
    status: 1,
    periodStart: '2026-07-13',
    periodEnd: '2026-07-19',
    createdAt: '2026-07-20 03:15:00',
    payload
  }
}

async function open(payload) {
  mocks.fetchTeacherReports.mockResolvedValue([report(payload)])
  const wrapper = mount(TeacherReports, { global: { stubs } })
  await flushPromises()
  await wrapper.find('.run-row').trigger('click')
  return wrapper
}

describe('TeacherReports weekly payload', () => {
  beforeEach(() => {
    mocks.fetchTeacherReports.mockReset()
  })

  it('renders weak nodes, anomalies and engagement deltas as tables', async () => {
    const w = await open({
      summary: '本周期共 4 名学生。',
      rosterSize: 4,
      weakNodes: [{ nodeCode: 'N1', name: '循环', avgEffective: 0.3, sampleSize: 2 }],
      anomalies: [
        { type: 'EFFECTIVE_DROP', studentId: 1, studentName: '甲同学', reason: '「循环」近 7 天有效掌握度下降 30 个百分点' },
        { type: 'NO_PRACTICE', studentId: 3, studentName: '丙同学', reason: '尚无任何练习记录' }
      ],
      anomalyTotal: 2,
      engagement: {
        practiceThisWeek: 30, practiceLastWeek: 20, practiceDelta: 10,
        activeStudentsThisWeek: 3, activeStudentsLastWeek: 4, activeStudentsDelta: -1,
        dialogueThisWeek: 4, dialogueLastWeek: 4, dialogueDelta: 0
      },
      narrative: ''
    })
    expect(w.get('[data-testid="report-summary"]').text()).toContain('共 4 名学生')
    const weak = w.get('[data-testid="weak-table"]').text()
    expect(weak).toContain('循环')
    expect(weak).toContain('30%')
    expect(weak).toContain('2 人')
    const an = w.get('[data-testid="anomaly-table"]').text()
    expect(an).toContain('甲同学')
    expect(an).toContain('掌握度下降')
    expect(an).toContain('尚无任何练习记录')
    const eng = w.get('[data-testid="engagement-table"]').text()
    expect(eng).toContain('↑10')
    expect(eng).toContain('↓1')
    expect(eng).toContain('持平')
    expect(w.find('[data-testid="report-empty"]').exists()).toBe(false)
  })

  it('shows an empty state for a report without any data', async () => {
    const w = await open({
      summary: '本周期（2026-07-13 ~ 2026-07-19）该课程暂无选课学生。',
      rosterSize: 0,
      weakNodes: [],
      anomalies: [],
      anomalyTotal: 0,
      engagement: {},
      narrative: ''
    })
    expect(w.get('[data-testid="report-empty"]').exists()).toBe(true)
    expect(w.find('[data-testid="weak-table"]').exists()).toBe(false)
  })

  it('still tolerates legacy payloads without names / summary', async () => {
    const w = await open({
      weakNodes: [{ nodeCode: 'N1', avgEffective: 0.5, sampleSize: 3 }],
      anomalies: [{ type: 'NO_PRACTICE', studentId: 9, detail: 20 }],
      engagement: { dialogueThisWeek: 2, dialogueLastWeek: 1, practiceThisWeek: 3, practiceLastWeek: 3 }
    })
    expect(w.get('[data-testid="weak-table"]').text()).toContain('N1')
    expect(w.get('[data-testid="anomaly-table"]').text()).toContain('#9')
    expect(w.get('[data-testid="engagement-table"]').text()).toContain('↑1')
  })
})
