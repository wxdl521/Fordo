import { http } from './http.js'

/**
 * 获取学生在某课程下的对话会话列表
 * @param {number} studentId
 * @param {number} courseId
 * @returns {Promise<Array>} 会话列表
 */
export function listConversations(studentId, courseId) {
  return http.get('/companion/conversations', { params: { studentId, courseId } })
}

/**
 * 获取单个会话详情（包含所有消息）
 * @param {number} id - 会话 ID
 * @returns {Promise<Object>} 会话详情
 */
export function fetchConversation(id) {
  return http.get(`/companion/conversations/${id}`)
}

/**
 * 流式对话接口（SSE）
 * @param {{ studentId: number, courseId: number, conversationId?: number, message: string }} payload
 * @param {{ onMeta: Function, onToken: Function, onDone: Function, onError: Function }} callbacks
 * @returns {Promise<void>}
 */
export async function chatStream(payload, { onMeta, onToken, onDone, onError }) {
  try {
    const response = await fetch('/api/companion/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
      },
      body: JSON.stringify(payload)
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() // 保留不完整的行

      for (const line of lines) {
        if (!line.trim() || line.startsWith(':')) continue

        if (line.startsWith('data: ')) {
          const data = line.slice(6)
          if (data === '[DONE]') {
            if (onDone) onDone()
            return
          }

          try {
            const event = JSON.parse(data)

            if (event.event === 'meta') {
              if (onMeta) onMeta(event.data)
            } else if (event.event === 'token') {
              if (onToken) onToken(event.data.token)
            } else if (event.event === 'done') {
              if (onDone) onDone()
            } else if (event.event === 'error') {
              if (onError) onError(event.data.message)
            }
          } catch (e) {
            console.warn('Failed to parse SSE data:', data, e)
          }
        }
      }
    }
  } catch (error) {
    if (onError) onError(error.message || '网络错误')
    throw error
  }
}
