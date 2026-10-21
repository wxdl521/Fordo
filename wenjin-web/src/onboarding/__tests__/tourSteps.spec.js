import { describe, it, expect } from 'vitest'
import { STUDENT_STEPS, TEACHER_STEPS, storageKey, TOUR_STORAGE_PREFIX } from '../tourSteps.js'

describe('tourSteps', () => {
  it('student and teacher steps have unique ids', () => {
    const s = STUDENT_STEPS.map((x) => x.id)
    const t = TEACHER_STEPS.map((x) => x.id)
    expect(new Set(s).size).toBe(s.length)
    expect(new Set(t).size).toBe(t.length)
    expect(s).toContain('map-canvas')
    expect(s).toContain('map-legend')
    expect(s).toContain('map-review')
    expect(s).toContain('map-root')
    expect(s).toContain('nav-diagnostic')
    expect(s).toContain('nav-companion')
    expect(t).toContain('nav-graph')
    expect(t).toContain('teacher-course')
    expect(t).toContain('teacher-graph-ops')
    expect(t).toContain('teacher-link')
    expect(t).toContain('nav-questions')
    expect(t).toContain('nav-dashboard')
  })

  it('storage keys split by role and user', () => {
    expect(storageKey(8, false)).toBe(`${TOUR_STORAGE_PREFIX}.student.8`)
    expect(storageKey(8, true)).toBe(`${TOUR_STORAGE_PREFIX}.teacher.8`)
    expect(storageKey(null, false)).toBe(`${TOUR_STORAGE_PREFIX}.student.anon`)
  })
})
