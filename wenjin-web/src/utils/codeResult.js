/** Shared helpers for programming (CODE) questions. */
export const CODE_TYPE = 5

const VERDICT_LABELS = {
  ACCEPTED: '通过',
  WRONG_ANSWER: '答案错误',
  COMPILE_ERROR: '编译错误',
  RUNTIME_ERROR: '运行错误',
  TIMEOUT: '运行超时',
  RESOURCE_LIMIT: '资源超限',
  REJECTED: '代码被拒绝',
  RATE_LIMITED: '操作过于频繁',
  UNAVAILABLE: '判题服务暂不可用'
}

export function verdictLabel(verdict) {
  return VERDICT_LABELS[verdict] || verdict || '未知'
}

/** ok | bad | warn tone for a verdict badge */
export function verdictTone(verdict) {
  if (verdict === 'ACCEPTED') return 'ok'
  if (verdict === 'RATE_LIMITED' || verdict === 'UNAVAILABLE') return 'warn'
  return 'bad'
}

export function isCodeType(type) {
  return type === CODE_TYPE || type === 'CODE'
}

/** Student-facing question VO -> card question fields for CODE questions. */
export function codeQuestionFields(vo) {
  if (!isCodeType(vo?.type)) return {}
  return {
    starterCode: vo.starterCode ?? '',
    sampleCases: (vo.sampleCases || []).map((c) => ({
      seq: c.seq,
      stdin: c.stdin ?? '',
      expectedStdout: c.expectedStdout ?? ''
    }))
  }
}

/** Insert text at the textarea selection; returns { value, caret }. */
export function insertAtSelection(value, start, end, text) {
  return { value: value.slice(0, start) + text + value.slice(end), caret: start + text.length }
}

/** Enter key: keep the current line's leading whitespace (plus extra indent after an opening brace). */
export function newlineWithIndent(value, start, end, unit = '    ') {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1
  const line = value.slice(lineStart, start)
  const indent = /^[ \t]*/.exec(line)[0]
  const extra = line.trimEnd().endsWith('{') ? unit : ''
  return insertAtSelection(value, start, end, '\n' + indent + extra)
}
