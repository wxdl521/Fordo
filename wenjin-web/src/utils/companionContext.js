export function readCompanionNodeCode(queryValue) {
  const raw = Array.isArray(queryValue) ? queryValue[0] : queryValue
  if (typeof raw !== 'string') return null

  const normalized = raw.trim()
  return normalized || null
}

export function buildCompanionChatPayload({ studentId, courseId, conversationId, nodeCode, message }) {
  const payload = {
    studentId,
    courseId,
    message
  }

  if (conversationId) payload.conversationId = conversationId

  const normalizedNodeCode = readCompanionNodeCode(nodeCode)
  if (normalizedNodeCode) payload.nodeCode = normalizedNodeCode

  return payload
}
