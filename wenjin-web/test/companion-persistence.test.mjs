import test from 'node:test'
import assert from 'node:assert/strict'

import { createDebouncedPersist } from '../src/utils/companionPersistence.js'

test('createDebouncedPersist coalesces repeated snapshot writes until flush', () => {
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

  assert.deepEqual(writes, [])
  assert.deepEqual(cancelled, [1, 2])
  assert.equal(scheduled.length, 3)
  assert.equal(scheduled[2].delay, 250)

  persist.flush()
  assert.deepEqual(writes, ['write'])

  scheduled[2].callback()
  assert.deepEqual(writes, ['write'])
})

test('createDebouncedPersist can cancel a pending snapshot write', () => {
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

  assert.deepEqual(cancelled, [7])
  assert.deepEqual(writes, [])
})
