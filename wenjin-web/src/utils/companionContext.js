export function readCompanionNodeCode(queryValue) {
  const raw = Array.isArray(queryValue) ? queryValue[0] : queryValue
  if (typeof raw !== 'string') return null

  const normalized = raw.trim()
  return normalized || null
}

/** 构建伴侣对话请求体；studentId 不发送，由服务端 principal 派生 */
export function buildCompanionChatPayload({ courseId, conversationId, nodeCode, message }) {
  const payload = {
    courseId,
    message
  }

  if (conversationId) payload.conversationId = conversationId

  const normalizedNodeCode = readCompanionNodeCode(nodeCode)
  if (normalizedNodeCode) payload.nodeCode = normalizedNodeCode

  return payload
}
