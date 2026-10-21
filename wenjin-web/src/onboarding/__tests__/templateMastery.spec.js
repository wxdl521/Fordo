import { describe, it, expect } from 'vitest'
import { needsTemplateMastery, applyTemplateMastery, withTemplateGraph } from '../templateMastery.js'

describe('templateMastery', () => {
  it('detects all-unlearned graphs', () => {
    expect(needsTemplateMastery([])).toBe(false)
    expect(needsTemplateMastery([{ id: 'a', mastery: 'unlearned' }])).toBe(true)
    expect(needsTemplateMastery([{ id: 'a' }, { id: 'b', mastery: 'unlearned' }])).toBe(true)
    expect(needsTemplateMastery([{ id: 'a', mastery: 'weak' }])).toBe(false)
  })

  it('paints a mixed but deterministic template', () => {
    const nodes = ['n1', 'n2', 'n3', 'n4', 'n5', 'n6'].map((id) => ({ id, mastery: 'unlearned' }))
    const painted = applyTemplateMastery(nodes)
    const again = applyTemplateMastery(nodes)
    expect(painted.map((n) => n.mastery)).toEqual(again.map((n) => n.mastery))
    const kinds = new Set(painted.map((n) => n.mastery))
    expect(kinds.has('mastered') || kinds.has('weak') || kinds.has('unlearned')).toBe(true)
    expect(painted.some((n) => n.mastery !== 'unlearned')).toBe(true)
  })

  it('withTemplateGraph only wraps empty mastery', () => {
    const empty = { nodes: [{ id: 'a' }], edges: [] }
    const live = { nodes: [{ id: 'a', mastery: 'mastered' }], edges: [] }
    expect(withTemplateGraph(empty).template).toBe(true)
    expect(withTemplateGraph(live).template).toBeUndefined()
  })
})
