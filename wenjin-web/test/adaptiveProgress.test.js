import { describe, it, expect } from 'vitest'
import { ringSegments } from '../src/utils/adaptiveProgress.js'

const C = 100

describe('ringSegments', () => {
  it('splits three non-zero arcs by proportion with cumulative offsets', () => {
    const segs = ringSegments({ tested: 2, inferred: 3, unknown: 5 }, C)
    expect(segs).toHaveLength(3)
    expect(segs.map((s) => s.key)).toEqual(['tested', 'inferred', 'unknown'])
    expect(segs[0].length).toBe(20)
    expect(segs[1].length).toBe(30)
    expect(segs[2].length).toBe(50)
    expect(segs[0].offset).toBe(0)
    expect(segs[1].offset).toBe(20)
    expect(segs[2].offset).toBe(50)
    const totalLen = segs.reduce((a, s) => a + s.length, 0)
    expect(Math.abs(totalLen - C)).toBeLessThan(1e-9)
  })

  it('fills the whole ring when only unknown remains', () => {
    const segs = ringSegments({ tested: 0, inferred: 0, unknown: 7 }, C)
    expect(segs).toHaveLength(1)
    expect(segs[0].key).toBe('unknown')
    expect(segs[0].length).toBe(C)
    expect(segs[0].offset).toBe(0)
  })

  it('skips zero-count segments without leaving offset gaps', () => {
    const segs = ringSegments({ tested: 4, inferred: 0, unknown: 4 }, C)
    expect(segs.map((s) => s.key)).toEqual(['tested', 'unknown'])
    expect(segs[0].length).toBe(50)
    expect(segs[1].offset).toBe(50)
  })

  it('returns empty when all counts are zero', () => {
    expect(ringSegments({ tested: 0, inferred: 0, unknown: 0 }, C)).toEqual([])
  })

  it('treats missing or negative values as zero', () => {
    expect(ringSegments(undefined, C)).toEqual([])
    expect(ringSegments({}, C)).toEqual([])
    const segs = ringSegments({ tested: -3, inferred: 2, unknown: undefined }, C)
    expect(segs).toHaveLength(1)
    expect(segs[0].key).toBe('inferred')
    expect(segs[0].length).toBe(C)
  })

  it('returns empty for non-positive circumference', () => {
    expect(ringSegments({ tested: 1, inferred: 1, unknown: 1 }, 0)).toEqual([])
    expect(ringSegments({ tested: 1, inferred: 1, unknown: 1 }, -5)).toEqual([])
  })

  it('preserves proportion under real 2πr circumference', () => {
    const circ = 2 * Math.PI * 70
    const segs = ringSegments({ tested: 1, inferred: 1, unknown: 2 }, circ)
    const totalLen = segs.reduce((a, s) => a + s.length, 0)
    expect(Math.abs(totalLen - circ)).toBeLessThan(1e-9)
    for (const s of segs) {
      expect(s.length).toBeLessThanOrEqual(circ + 1e-9)
    }
  })
})
