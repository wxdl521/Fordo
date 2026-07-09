export function createDebouncedPersist(
  writeSnapshot,
  delayMs = 250,
  schedule = setTimeout,
  cancelScheduled = clearTimeout
) {
  let pendingId = null
  let pendingArgs = null
  let generation = 0

  function cancel() {
    if (pendingId == null) return
    cancelScheduled(pendingId)
    pendingId = null
    pendingArgs = null
    generation += 1
  }

  function flush(...args) {
    const argsToWrite = args.length ? args : pendingArgs
    cancel()
    writeSnapshot(...(argsToWrite || []))
  }

  function scheduleWrite(...args) {
    cancel()
    const token = generation + 1
    generation = token
    pendingArgs = args
    pendingId = schedule(() => {
      if (token !== generation) return
      const argsToWrite = pendingArgs
      pendingId = null
      pendingArgs = null
      writeSnapshot(...(argsToWrite || []))
    }, delayMs)
  }

  return {
    schedule: scheduleWrite,
    flush,
    cancel
  }
}
