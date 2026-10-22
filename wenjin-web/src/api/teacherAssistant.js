import { http } from './http.js'
import { streamSse } from './companion.js'

/**
 * 教师 AI 助教流式对话（SSE）。功能关闭时后端 404 → 抛出带 httpStatus=404 的错误。
 * 事件：meta / tool_start / tool_result / insight_card / token / done / error
 * @param {{ courseId: number, conversationId?: number, message: string }} payload
 * @param {object} callbacks 见 dispatchChatStreamEvent（含 onInsightCard）
 * @param {{ signal?: AbortSignal }} [options]
 */
export function assistantChatStream(payload, callbacks = {}, options = {}) {
  return streamSse('/api/teacher/assistant/chat', { ...payload }, callbacks, options)
}

/** @returns {Promise<Array<{id:number,title:string,updatedAt:string}>>} */
export function listAssistantConversations(courseId, config = {}) {
  return http.get('/teacher/assistant/conversations', {
    ...config,
    params: { ...(config.params || {}), courseId }
  })
}

/** @returns {Promise<Array<{role:'user'|'ai',content:string,createdAt:string}>>} */
export function fetchAssistantMessages(id, config = {}) {
  return http.get(`/teacher/assistant/conversations/${id}/messages`, config)
}

export function deleteAssistantConversation(id, config = {}) {
  return http.delete(`/teacher/assistant/conversations/${id}`, config)
}
