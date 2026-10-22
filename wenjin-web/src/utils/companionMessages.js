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

/** Same marker the server writes into persisted text for an out-of-set citation (CitationGuard.INVALID_MARK). */
export const INVALID_CITATION_MARK = '（引用无效）'

/**
 * How a citation segment is rendered once the turn's `citations` event is known.
 * - no event (history messages / rag off): legacy behaviour, every id is a chip;
 * - with event: only ids the server validated stay clickable, everything else is struck through.
 * @param {number} chunkId
 * @param {{ valid?: number[], invalid?: number[] } | undefined} citations
 * @returns {'chip' | 'invalid'}
 */
export function citationRenderKind(chunkId, citations) {
  if (!citations) return 'chip'
  return (citations.valid || []).includes(chunkId) ? 'chip' : 'invalid'
}

/**
 * Replace `资料 #N` for the given invalid ids with the server's marker (mirrors CitationGuard.neutralize).
 * @param {string} text
 * @param {number[]} invalid
 */
export function neutralizeInvalidCitations(text, invalid) {
  const source = text == null ? '' : String(text)
  if (!source || !invalid?.length) return source
  return source.replace(/资料\s*#(\d+)/g, (match, id) => (
    invalid.includes(Number(id)) ? INVALID_CITATION_MARK : match
  ))
}

/**
 * Attach the `citations` event to every assistant bubble of the current turn (a turn may render several
 * assistant bubbles: text before / after tool capsules). Walks back from the end and stops at the user
 * message, so earlier turns are untouched.
 * @param {Array<object>} messages
 * @param {{ valid?: number[], invalid?: number[] }} event
 */
export function applyCitations(messages, event) {
  const next = Array.isArray(messages) ? messages.slice() : []
  const citations = {
    valid: Array.isArray(event?.valid) ? [...event.valid] : [],
    invalid: Array.isArray(event?.invalid) ? [...event.invalid] : []
  }
  for (let i = next.length - 1; i >= 0; i--) {
    if (next[i]?.role === 'user') break
    if (next[i]?.role === 'assistant') {
      next[i] = { ...next[i], citations }
    }
  }
  return next
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
 * Append a streaming token to the newest assistant bubble.
 * Only the very last message counts: if it is an assistant text bubble the token
 * extends it; otherwise (user / tool capsule / practice / probe card ...) a new
 * assistant bubble is created after it, so text following a tool or card in the
 * same turn renders below it, in arrival order.
 */
export function appendAssistantToken(messages, token) {
  const next = Array.isArray(messages) ? messages.slice() : []
  const text = token == null ? '' : String(token)
  const last = next.length - 1
  if (last >= 0 && next[last]?.role === 'assistant') {
    next[last] = { ...next[last], content: (next[last].content || '') + text }
  } else {
    next.push({ role: 'assistant', content: text })
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
      // The server persists invalid citations as a marker; mirror that so a live turn's transcript
      // still equals the reloaded one and the typed snapshot (practice cards...) keeps restoring.
      content: neutralizeInvalidCitations(message.content || '', message.citations?.invalid)
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
