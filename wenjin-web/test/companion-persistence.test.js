import { describe, it, expect } from 'vitest'
import { createDebouncedPersist } from '../src/utils/companionPersistence.js'

describe('createDebouncedPersist', () => {
  it('coalesces repeated snapshot writes until flush', () => {
    const writes = []
    const scheduled = []
    const cancelled = []
    const persist = createDebouncedPersist(
      () => writes.push('write'),
      250,
      (callback, delay) => {
        scheduled.push({ callback, delay })
        return scheduled.length
      },
      (id) => cancelled.push(id)
    )

    persist.schedule()
    persist.schedule()
    persist.schedule()

    expect(writes).toEqual([])
    expect(cancelled).toEqual([1, 2])
    expect(scheduled).toHaveLength(3)
    expect(scheduled[2].delay).toBe(250)

    persist.flush()
    expect(writes).toEqual(['write'])

    scheduled[2].callback()
    expect(writes).toEqual(['write'])
  })

  it('can cancel a pending snapshot write', () => {
    const writes = []
    const cancelled = []
    const persist = createDebouncedPersist(
      () => writes.push('write'),
      250,
      () => 7,
      (id) => cancelled.push(id)
    )

    persist.schedule()
    persist.cancel()

    expect(cancelled).toEqual([7])
    expect(writes).toEqual([])
  })

  it('flushes the scheduled snapshot arguments', () => {
    const writes = []
    const scheduled = []
    const persist = createDebouncedPersist(
      (key, snapshot) => writes.push([key, snapshot]),
      250,
      (callback) => {
        scheduled.push(callback)
        return scheduled.length
      },
      () => {}
    )

    persist.schedule('conversation-A', { messages: ['A'] })
    persist.flush()

    expect(writes).toEqual([['conversation-A', { messages: ['A'] }]])
    scheduled[0]()
    expect(writes).toEqual([['conversation-A', { messages: ['A'] }]])
  })
})
