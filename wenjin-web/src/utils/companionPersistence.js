export function createDebouncedPersist(
  writeSnapshot,
  delayMs = 250,
  schedule = setTimeout,
  cancelScheduled = clearTimeout
) {
  let pendingId = null
  let generation = 0

  function cancel() {
    if (pendingId == null) return
    cancelScheduled(pendingId)
    pendingId = null
    generation += 1
  }

  function flush() {
    cancel()
    writeSnapshot()
  }

  function scheduleWrite() {
    cancel()
    const token = generation + 1
    generation = token
    pendingId = schedule(() => {
      if (token !== generation) return
      pendingId = null
      writeSnapshot()
    }, delayMs)
  }

  return {
    schedule: scheduleWrite,
    flush,
    cancel
  }
}
