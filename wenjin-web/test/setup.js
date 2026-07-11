/**
 * Vitest global setup: localStorage / sessionStorage polyfills for happy-dom,
 * and light stubs used across unit tests.
 */
import { afterEach, vi } from 'vitest'

afterEach(() => {
  vi.restoreAllMocks()
  try {
    localStorage.clear()
    sessionStorage.clear()
  } catch {
    // ignore when storage is unavailable
  }
})
