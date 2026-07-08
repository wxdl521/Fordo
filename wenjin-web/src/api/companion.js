import { http } from './http.js'

/**
 * 获取学生在某课程下的对话会话列表
 * @param {number} studentId
 * @param {number} courseId
 * @returns {Promise<Array>}
 */
export function listConversations(studentId, courseId) {
  return http.get('/companion/conversations', { params: { studentId, courseId } })
}

/**
 * 获取单个会话详情（包含所有消息）
 * @param {number} id
 * @returns {Promise<Object>}
 */
export function fetchConversation(id) {
  return http.get(`/companion/conversations/${id}`)
}

/**
 * 删除会话（含所有消息）
 * @param {number} id
 * @returns {Promise<void>}
 */
export function deleteConversation(id) {
  return http.delete(`/companion/conversations/${id}`)
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
 * @param {{ studentId: number, courseId: number, conversationId?: number, nodeCode?: string, message: string }} payload
 * @param {{
 *   onMeta?: (event: any) => void,
 *   onToken?: (token: string) => void,
 *   onDone?: (event?: any) => void,
 *   onError?: (message: string) => void,
 *   onToolStart?: (event: any) => void,
 *   onToolResult?: (event: any) => void,
 *   onPracticeCard?: (event: any) => void
 * }} callbacks
 * @returns {Promise<void>}
 */
export async function chatStream(payload, callbacks = {}) {
  try {
    // SSE 使用原生 fetch，绕过 http.js 的 axios 拦截器，需要手动补 Authorization。
    const headers = {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream'
    }
    const token = localStorage.getItem('wj_token')
    if (token) headers.Authorization = `Bearer ${token}`

    const response = await fetch('/api/companion/chat', {
      method: 'POST',
      headers,
      body: JSON.stringify(payload)
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let eventType = ''

    while (true) {
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
    callbacks.onError?.(error.message || '网络错误')
    throw error
  }
}
