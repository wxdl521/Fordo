function deepClone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value))
}

export function normalizeConversationMessage(message) {
  const role = message?.role === 'ai' ? 'assistant' : message?.role
  if (role === 'user' || role === 'assistant') {
    return { role, content: message?.content || '' }
  }
  return { role: 'assistant', content: message?.content || '' }
}

export function normalizePracticeCard(card) {
  return {
    sessionId: card?.sessionId,
    node: card?.node || null,
    questions: (card?.questions || []).map((question) => ({
      questionId: question.questionId,
      c: question.chapter,
      q: question.stem,
      o: (question.options || []).map((option) => option.text),
      optionKeys: (question.options || []).map((option) => option.key),
      type: question.type || 1
    }))
  }
}

export function createPracticeMessage(card) {
  return {
    role: 'practice',
    card: normalizePracticeCard(card),
    phase: 'answering',
    currentIndex: 0,
    answers: {
      single: {},
      multi: {},
      text: {}
    },
    skippedSet: {},
    submitResult: null,
    submitError: ''
  }
}

/**
 * Normalize probe_card SSE payload into a single-question card for QuestionAnswerCard.
 * @param {object} card ProbeStartVO
 */
export function normalizeProbeCard(card) {
  const q = card?.question || {}
  return {
    sessionId: card?.sessionId,
    nodeCode: card?.nodeCode || null,
    nodeName: card?.nodeName || null,
    source: card?.source || null,
    ephemeral: !!card?.ephemeral,
    question: {
      questionId: q.questionId,
      c: q.chapter || card?.nodeName || card?.nodeCode || '理解探针',
      q: q.stem || '',
      o: (q.options || []).map((option) => option.text),
      optionKeys: (q.options || []).map((option) => option.key),
      type: q.type || 4
    }
  }
}

export function createProbeMessage(card) {
  return {
    role: 'probe',
    card: normalizeProbeCard(card),
    phase: 'answering',
    answers: {
      single: null,
      multi: [],
      text: ''
    },
    submitResult: null,
    submitError: ''
  }
}

/**
 * Build studentAnswer string for probe submit from message answers.
 * @param {object} message probe message
 * @returns {string}
 */
export function buildProbeStudentAnswer(message) {
  const q = message?.card?.question
  if (!q) return ''
  if (q.type === 2) {
    const selected = [...(message.answers?.multi || [])].sort((a, b) => a - b)
    return selected
      .map((i) => q.optionKeys?.[i])
      .filter((v) => v !== undefined)
      .join(',')
  }
  if (q.type === 4) {
    return message.answers?.text != null ? String(message.answers.text) : ''
  }
  const idx = message.answers?.single
  if (typeof idx === 'number' && q.optionKeys?.[idx] !== undefined) {
    return q.optionKeys[idx]
  }
  return ''
}

/**
 * Split assistant text into plain / citation-chip segments.
 * Matches `资料 #123` patterns.
 * @param {string} text
 * @returns {Array<{ type: 'text' | 'citation', value: string, chunkId?: number }>}
 */
export function parseCitationSegments(text) {
  const source = text == null ? '' : String(text)
  if (!source) return []
  const re = /资料\s*#(\d+)/g
  const segments = []
  let last = 0
  let m
  while ((m = re.exec(source)) !== null) {
    if (m.index > last) {
      segments.push({ type: 'text', value: source.slice(last, m.index) })
    }
    const chunkId = Number(m[1])
    segments.push({
      type: 'citation',
      value: m[0],
      chunkId: Number.isFinite(chunkId) ? chunkId : undefined
    })
    last = m.index + m[0].length
  }
  if (last < source.length) {
    segments.push({ type: 'text', value: source.slice(last) })
  }
  return segments
}

export function createToolMessage(event, ok = null) {
  return {
    role: 'tool',
    name: event?.name || 'tool',
    label: event?.label || event?.name || '工具',
    ok
  }
}

/**
 * Append a streaming token to the current-turn assistant bubble.
 * If no assistant exists after the last user message, push a new one at the end
 * so tools/practice that arrived earlier stay above the final reply.
 * Stops scanning when a user message is hit (does not append to previous turns).
 */
export function appendAssistantToken(messages, token) {
  const next = Array.isArray(messages) ? messages.slice() : []
  const text = token == null ? '' : String(token)
  let idx = -1
  for (let i = next.length - 1; i >= 0; i--) {
    if (next[i]?.role === 'assistant') {
      idx = i
      break
    }
    if (next[i]?.role === 'user') break
  }
  if (idx < 0) {
    next.push({ role: 'assistant', content: text })
  } else {
    const current = next[idx]
    next[idx] = {
      ...current,
      content: (current.content || '') + text
    }
  }
  return next
}

export function applyToolResult(messages, event) {
  const pendingIndex = messages.findIndex((message) => (
    message?.role === 'tool'
    && message.name === event?.name
    && message.ok == null
  ))

  if (pendingIndex === -1) {
    return [...messages, createToolMessage(event, !!event?.ok)]
  }

  return messages.map((message, index) => (
    index === pendingIndex
      ? { ...message, ok: !!event?.ok }
      : message
  ))
}

export function buildPracticeAnswerList(message) {
  const list = []

  ;(message?.card?.questions || []).forEach((question, questionIndex) => {
    let studentAnswer = null

    if (question.type === 2) {
      const selected = [...(message.answers?.multi?.[questionIndex] || [])].sort((a, b) => a - b)
      if (selected.length) {
        studentAnswer = selected
          .map((selectedIndex) => question.optionKeys?.[selectedIndex])
          .filter((value) => value !== undefined)
          .join(',')
      }
    } else if (question.type === 4) {
      studentAnswer = message.answers?.text?.[questionIndex] !== undefined
        ? message.answers.text[questionIndex]
        : ''
    } else {
      const optionIndex = message.answers?.single?.[questionIndex]
      if (typeof optionIndex === 'number' && question.optionKeys?.[optionIndex] !== undefined) {
        studentAnswer = question.optionKeys[optionIndex]
      }
    }

    if (studentAnswer !== null) {
      list.push({
        questionId: question.questionId,
        studentAnswer
      })
    }
  })

  return list
}

export function buildPlainTranscript(messages) {
  return messages
    .filter((message) => message?.role === 'user' || message?.role === 'assistant')
    .map((message) => ({
      role: message.role,
      content: message.content || ''
    }))
}

export function createTypedMessagesSnapshot(messages) {
  return {
    transcript: buildPlainTranscript(messages),
    messages: deepClone(messages)
  }
}

export function restoreTypedMessagesFromSnapshot(serverMessages, snapshot) {
  const normalizedServerMessages = serverMessages.map(normalizeConversationMessage)

  if (!snapshot || !Array.isArray(snapshot.messages) || !Array.isArray(snapshot.transcript)) {
    return { usedSnapshot: false, messages: normalizedServerMessages }
  }

  const serverTranscript = buildPlainTranscript(normalizedServerMessages)
  const snapshotTranscript = buildPlainTranscript(snapshot.transcript)
  if (JSON.stringify(serverTranscript) !== JSON.stringify(snapshotTranscript)) {
    return { usedSnapshot: false, messages: normalizedServerMessages }
  }

  return {
    usedSnapshot: true,
    messages: deepClone(snapshot.messages)
  }
}

export function conversationSnapshotKey(studentId, courseId, conversationId) {
  return `wj_companion_typed:${studentId}:${courseId}:${conversationId}`
}
