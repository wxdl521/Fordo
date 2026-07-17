import { clearSessionAndRedirect, http } from './http.js'

/** @param {unknown} err */
export function isAbortError(err) {
  if (!err) return false
  return (
    err.name === 'CanceledError' ||
    err.name === 'AbortError' ||
    err.code === 'ERR_CANCELED' ||
    err.message === 'canceled' ||
    err.message === 'The operation was aborted.'
  )
}

/**
 * 获取当前登录学生在某课程下的对话会话列表
 * @param {number} courseId
 * @param {{ signal?: AbortSignal, params?: Record<string, unknown> }} [config] axios 配置（支持 AbortSignal）
 * @returns {Promise<Array>}
 */
export function listConversations(courseId, config = {}) {
  const { params, ...rest } = config
  return http.get('/companion/conversations', {
    ...rest,
    params: { courseId, ...(params || {}) }
  })
}

/**
 * 获取单个会话详情（包含所有消息）
 * @param {number|string} id
 * @param {{ signal?: AbortSignal }} [config] axios 配置（支持 AbortSignal）
 * @returns {Promise<Object>}
 */
export function fetchConversation(id, config = {}) {
  return http.get(`/companion/conversations/${id}`, config)
}

/**
 * 删除会话（含所有消息）
 * @param {number|string} id
 * @param {{ signal?: AbortSignal }} [config] axios 配置（支持 AbortSignal）
 * @returns {Promise<void>}
 */
export function deleteConversation(id, config = {}) {
  return http.delete(`/companion/conversations/${id}`, config)
}

/**
 * Dispatch a parsed companion SSE event to optional callbacks.
 * Supported events: `meta`, `tool_start`, `tool_result`, `practice_card`, `token`, `done`, `error`.
 *
 * @param {string} eventType
 * @param {any} payload
 * @param {{
 *   onMeta?: (event: any) => void,
 *   onToken?: (token: string) => void,
 *   onDone?: (event?: any) => void,
 *   onError?: (message: string) => void,
 *   onToolStart?: (event: any) => void,
 *   onToolResult?: (event: any) => void,
 *   onPracticeCard?: (event: any) => void
 * }} callbacks
 * @returns {{ handled: boolean, done: boolean }}
 */
export function dispatchChatStreamEvent(eventType, payload, callbacks = {}) {
  switch (eventType) {
    case 'meta':
      callbacks.onMeta?.(payload)
      return { handled: true, done: false }
    case 'tool_start':
      callbacks.onToolStart?.(payload)
      return { handled: true, done: false }
    case 'tool_result':
      callbacks.onToolResult?.(payload)
      return { handled: true, done: false }
    case 'practice_card':
      callbacks.onPracticeCard?.(payload)
      return { handled: true, done: false }
    case 'token':
      callbacks.onToken?.(payload?.t ?? '')
      return { handled: true, done: false }
    case 'done':
      callbacks.onDone?.(payload)
      return { handled: true, done: true }
    case 'error':
      callbacks.onError?.(payload?.message || '网络错误')
      return { handled: true, done: false }
    default:
      return { handled: false, done: false }
  }
}

/**
 * 流式对话接口（SSE）
 * @param {{ courseId: number, conversationId?: number, nodeCode?: string, message: string }} payload
 * @param {{
 *   onMeta?: (event: any) => void,
 *   onToken?: (token: string) => void,
 *   onDone?: (event?: any) => void,
 *   onError?: (message: string) => void,
 *   onToolStart?: (event: any) => void,
 *   onToolResult?: (event: any) => void,
 *   onPracticeCard?: (event: any) => void
 * }} callbacks
 * @param {{ signal?: AbortSignal }} [options]
 * @returns {Promise<void>}
 */
export async function chatStream(payload, callbacks = {}, options = {}) {
  const { signal } = options
  try {
    // SSE 使用原生 fetch，绕过 http.js 的 axios 拦截器，需要手动补 Authorization。
    const headers = {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream'
    }
    const token = localStorage.getItem('wj_token')
    if (token) headers.Authorization = `Bearer ${token}`

    const body = { ...payload }
    delete body.studentId

    const response = await fetch('/api/companion/chat', {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal
    })

    if (response.status === 401) {
      clearSessionAndRedirect(token)
    }
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let eventType = ''

    while (true) {
      if (signal?.aborted) {
        const abortErr = new Error('The operation was aborted.')
        abortErr.name = 'AbortError'
        throw abortErr
      }

      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() || ''

      for (const rawLine of lines) {
        const line = rawLine.trim()
        if (!line || line.startsWith(':')) continue

        if (line.startsWith('event:')) {
          eventType = line.slice(6).trim()
          continue
        }

        if (!line.startsWith('data:')) continue

        const data = line.slice(5).trim()
        if (data === '[DONE]') {
          dispatchChatStreamEvent('done', {}, callbacks)
          return
        }

        try {
          const parsed = JSON.parse(data)
          const result = dispatchChatStreamEvent(eventType, parsed, callbacks)
          if (result.done) return
        } catch (error) {
          console.warn('SSE parse error:', data, error)
        }

        eventType = ''
      }
    }

    dispatchChatStreamEvent('done', {}, callbacks)
  } catch (error) {
    // Abort is not a network error — callers gate on isCurrent/isAbortError.
    if (isAbortError(error) || signal?.aborted) {
      throw error
    }
    // Report once via callback; rethrow so awaiters can observe failure without a second UI write.
    callbacks.onError?.(error.message || '网络错误')
    throw error
  }
}
