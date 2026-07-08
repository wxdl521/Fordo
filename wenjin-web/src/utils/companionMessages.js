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

export function createToolMessage(event, ok = null) {
  return {
    role: 'tool',
    name: event?.name || 'tool',
    label: event?.label || event?.name || '工具',
    ok
  }
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
