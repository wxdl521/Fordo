import { describe, expect, it, vi } from 'vitest'

const put = vi.hoisted(() => vi.fn())
vi.mock('../http.js', () => ({ http: { put, get: vi.fn(), post: vi.fn(), delete: vi.fn(), patch: vi.fn() }, clearSessionAndRedirect: vi.fn() }))

import { updateQuestionCode } from '../teacher.js'

describe('updateQuestionCode', () => {
  it('PUTs starterCode and cases only, scoped by courseId', () => {
    const cases = [{ seq: 1, stdin: 'a', expectedStdout: 'b', hidden: true }]
    updateQuestionCode(4, 9, { starterCode: 'S', cases, ignored: 1 })
    expect(put).toHaveBeenCalledWith('/teacher/questions/9/code', { starterCode: 'S', cases }, { params: { courseId: 4 } })
  })
})
