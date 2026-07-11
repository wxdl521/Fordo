import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useSessionStore } from '../src/store/session.js'
import { useCourseStore } from '../src/store/course.js'

const listConversations = vi.fn()
const fetchConversation = vi.fn()
const deleteConversation = vi.fn()
const chatStream = vi.fn()

vi.mock('../src/api/companion.js', async () => {
  const actual = await vi.importActual('../src/api/companion.js')
  return {
    ...actual,
    listConversations: (...args) => listConversations(...args),
    fetchConversation: (...args) => fetchConversation(...args),
    deleteConversation: (...args) => deleteConversation(...args),
    chatStream: (...args) => chatStream(...args)
  }
})

vi.mock('../src/api/practice.js', () => ({
  submitPractice: vi.fn()
}))

import Companion from '../src/views/Companion.vue'

function api() {
  return window.__wjCompanion
}

async function mountCompanion(query = { courseId: '5' }) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore(pinia)
  session.setSession({
    token: 't',
    user: { id: 2, role: 2, realName: '学生' }
  })
  if (query.courseId) {
    useCourseStore(pinia).setStudentCourseId(Number(query.courseId))
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: { template: '<div />' } },
      { path: '/companion', name: 'companion', component: Companion }
    ]
  })
  await router.push({ path: '/companion', query })
  await router.isReady()

  const wrapper = mount(Companion, {
    global: {
      plugins: [pinia, router],
      stubs: {
        QuestionAnswerCard: true
      }
    }
  })
  await flushPromises()
  return { wrapper, router, pinia }
}

describe('Companion race hardening', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listConversations.mockResolvedValue([])
    fetchConversation.mockResolvedValue({ messages: [] })
    deleteConversation.mockResolvedValue(undefined)
    chatStream.mockResolvedValue(undefined)
    localStorage.clear()
  })

  afterEach(() => {
    delete window.__wjCompanion
    vi.restoreAllMocks()
  })

  it('open session 10→20: resolve 20 then 10 shows only 20', async () => {
    let resolve10
    let resolve20
    fetchConversation.mockImplementation((id) => {
      if (Number(id) === 10) {
        return new Promise((resolve) => {
          resolve10 = resolve
        })
      }
      if (Number(id) === 20) {
        return new Promise((resolve) => {
          resolve20 = resolve
        })
      }
      return Promise.resolve({ messages: [] })
    })

    const { wrapper } = await mountCompanion({ courseId: '5' })
    const c = api()
    expect(c).toBeTruthy()

    const p10 = c.openConversation(10)
    await nextTick()
    const p20 = c.openConversation(20)
    await nextTick()

    expect(fetchConversation).toHaveBeenCalledWith(10, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(fetchConversation).toHaveBeenCalledWith(20, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    // Newer session resolves first
    resolve20({
      messages: [{ role: 'user', content: 'from-20' }, { role: 'assistant', content: 'reply-20' }]
    })
    await p20
    await flushPromises()
    expect(c.conversationId.value).toBe(20)
    expect(c.msgs.value.some((m) => m.content === 'from-20')).toBe(true)
    expect(c.msgs.value.some((m) => m.content === 'from-10')).toBe(false)

    // Stale session 10 must not overwrite
    resolve10({
      messages: [{ role: 'user', content: 'from-10' }, { role: 'assistant', content: 'reply-10' }]
    })
    await p10
    await flushPromises()
    expect(c.conversationId.value).toBe(20)
    expect(c.msgs.value.some((m) => m.content === 'from-20')).toBe(true)
    expect(c.msgs.value.some((m) => m.content === 'from-10')).toBe(false)

    wrapper.unmount()
  })

  it('send then newChat: late onToken must not write', async () => {
    let capturedCallbacks = null
    let rejectStream = null
    chatStream.mockImplementation((_payload, callbacks, options) => {
      capturedCallbacks = callbacks
      return new Promise((_resolve, reject) => {
        rejectStream = reject
        options?.signal?.addEventListener('abort', () => {
          const err = new Error('The operation was aborted.')
          err.name = 'AbortError'
          reject(err)
        })
      })
    })

    const { wrapper } = await mountCompanion({ courseId: '5' })
    const c = api()

    c.input.value = 'hello race'
    const sendPromise = c.sendText()
    await flushPromises()
    expect(chatStream).toHaveBeenCalled()
    expect(capturedCallbacks).toBeTruthy()
    expect(c.typing.value).toBe(true)
    expect(c.msgs.value.some((m) => m.role === 'user' && m.content === 'hello race')).toBe(true)

    c.newChat()
    await flushPromises()
    try {
      await sendPromise
    } catch {
      /* aborted stream */
    }
    expect(c.conversationId.value).toBe(null)
    expect(c.msgs.value).toEqual([])
    expect(c.typing.value).toBe(false)

    // Late SSE token after newChat must not re-populate messages
    capturedCallbacks.onToken('stale-token')
    await nextTick()
    expect(c.msgs.value).toEqual([])
    expect(c.msgs.value.some((m) => String(m.content || '').includes('stale-token'))).toBe(false)

    // silence hanging rejection if any
    rejectStream?.()
    wrapper.unmount()
  })

  it('course 5→6 immediately clears conversationId/messages/typing', async () => {
    listConversations.mockImplementation(() => new Promise(() => {}))
    fetchConversation.mockResolvedValue({
      messages: [{ role: 'user', content: 'kept-until-switch' }]
    })

    const { wrapper, router } = await mountCompanion({ courseId: '5' })
    const c = api()

    c.conversationId.value = 99
    c.msgs.value = [{ role: 'user', content: 'kept-until-switch' }]
    c.typing.value = true
    await nextTick()

    await router.push({ path: '/companion', query: { courseId: '6' } })
    await flushPromises()
    await nextTick()

    expect(c.conversationId.value).toBe(null)
    expect(c.msgs.value).toEqual([])
    expect(c.typing.value).toBe(false)

    wrapper.unmount()
  })

  it('course 5 list late must not write course 6', async () => {
    let resolve5
    let resolve6
    listConversations.mockImplementation((courseId) => {
      if (Number(courseId) === 5) {
        return new Promise((resolve) => {
          resolve5 = resolve
        })
      }
      if (Number(courseId) === 6) {
        return new Promise((resolve) => {
          resolve6 = resolve
        })
      }
      return Promise.resolve([])
    })

    const { wrapper, router } = await mountCompanion({ courseId: '5' })
    const c = api()
    expect(listConversations).toHaveBeenCalledWith(5, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    await router.push({ path: '/companion', query: { courseId: '6' } })
    await flushPromises()
    await nextTick()
    expect(listConversations).toHaveBeenCalledWith(6, expect.objectContaining({ signal: expect.any(AbortSignal) }))

    // Stale course-5 list
    resolve5([{ id: 501, title: 'course-5-only' }])
    await flushPromises()
    expect(c.conversations.value.some((x) => x.id === 501)).toBe(false)

    resolve6([{ id: 601, title: 'course-6-only' }])
    await flushPromises()
    expect(c.conversations.value.map((x) => x.id)).toEqual([601])

    wrapper.unmount()
  })

  it('unmount aborts list/detail/SSE; abort is not network error; ordinary rejection adds error once', async () => {
    let listSignal = null
    let detailSignal = null
    let streamSignal = null
    let streamCallbacks = null

    listConversations.mockImplementation((_id, config) => {
      listSignal = config?.signal
      return new Promise(() => {})
    })
    fetchConversation.mockImplementation((_id, config) => {
      detailSignal = config?.signal
      return new Promise(() => {})
    })
    chatStream.mockImplementation((_payload, callbacks, options) => {
      streamCallbacks = callbacks
      streamSignal = options?.signal
      return new Promise((_resolve, reject) => {
        options?.signal?.addEventListener('abort', () => {
          const err = new Error('The operation was aborted.')
          err.name = 'AbortError'
          reject(err)
        })
      })
    })

    const { wrapper } = await mountCompanion({ courseId: '5' })
    const c = api()

    // Start detail + stream so all three gates are live
    c.openConversation(42)
    await nextTick()
    c.input.value = 'ping'
    const sendPromise = c.sendText()
    await flushPromises()

    expect(listSignal).toBeTruthy()
    expect(detailSignal).toBeTruthy()
    expect(streamSignal).toBeTruthy()
    expect(listSignal.aborted).toBe(false)
    expect(detailSignal.aborted).toBe(false)
    expect(streamSignal.aborted).toBe(false)

    wrapper.unmount()
    await flushPromises()
    try {
      await sendPromise
    } catch {
      /* abort may reject */
    }

    expect(listSignal.aborted).toBe(true)
    expect(detailSignal.aborted).toBe(true)
    expect(streamSignal.aborted).toBe(true)

    // Abort path must not have written a network/error bubble (msgs cleared or no [网络错误])
    const errBubbles = (c.msgs?.value || []).filter(
      (m) => typeof m.content === 'string' && (m.content.includes('[网络错误]') || m.content.includes('[错误]'))
    )
    expect(errBubbles).toEqual([])

    // Ordinary rejection adds error once (fresh mount)
    chatStream.mockImplementation(async (_payload, callbacks) => {
      callbacks.onError?.('boom-once')
      const err = new Error('boom-once')
      throw err
    })
    listConversations.mockResolvedValue([])

    const second = await mountCompanion({ courseId: '5' })
    const c2 = api()
    c2.input.value = 'fail please'
    await c2.sendText()
    await flushPromises()

    const errorMsgs = c2.msgs.value.filter(
      (m) => typeof m.content === 'string' && (m.content.includes('boom-once') || m.content.includes('[错误]') || m.content.includes('[网络错误]'))
    )
    expect(errorMsgs.length).toBe(1)
    expect(errorMsgs[0].content).toContain('boom-once')

    // Late abort-style callback must not add another
    streamCallbacks?.onToken?.('after-unmount')
    second.wrapper.unmount()
  })
})
