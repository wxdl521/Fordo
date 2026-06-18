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
 * 删除会话（含所有消息）
 * @param {number} id - 会话 ID
 * @returns {Promise<void>}
 */
export function deleteConversation(id) {
  return http.delete(`/companion/conversations/${id}`)
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
    let eventType = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      // 处理 \r\n 和 \n 两种换行
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() // 保留不完整的行

      for (const rawLine of lines) {
        const line = rawLine.trim()
        if (!line || line.startsWith(':')) continue

        // 标准 SSE: event: meta / token / done / error
        if (line.startsWith('event:')) {
          eventType = line.slice(6).trim()
          continue
        }

        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data === '[DONE]') {
            if (onDone) onDone()
            return
          }

          try {
            const parsed = JSON.parse(data)

            if (eventType === 'meta') {
              if (onMeta) onMeta(parsed)
            } else if (eventType === 'token') {
              if (onToken) onToken(parsed.t)
            } else if (eventType === 'done') {
              if (onDone) onDone()
              return
            } else if (eventType === 'error') {
              if (onError) onError(parsed.message)
            }
          } catch (e) {
            console.warn('SSE parse error:', data, e)
          }

          eventType = '' // 重置
        }
      }
    }

    // 流结束但没收到 done 事件
    if (onDone) onDone()
  } catch (error) {
    if (onError) onError(error.message || '网络错误')
    throw error
  }
}
