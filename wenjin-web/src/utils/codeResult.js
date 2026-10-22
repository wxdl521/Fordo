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

/**
 * Practice submit judges CODE answers synchronously in the sandbox, so the default 15s axios timeout
 * (api/http.js) is far too short. Budget per CODE answer mirrors the backend defaults
 * (application.yml wenjin.limits.code.*): queue-wait-seconds 10 + compile-timeout-seconds 15 + a few cases at
 * run-timeout-seconds 3 each (one container per case, fail-fast after the first timeout) + container overhead.
 * Keep in sync when those defaults change. A submit that outlives the client timeout still commits on the
 * server; re-submitting the same session replays the stored result.
 */
export const SUBMIT_BASE_TIMEOUT_MS = 15000
export const CODE_JUDGE_BUDGET_MS = 60000
export const SUBMIT_MAX_TIMEOUT_MS = 180000

/** Per-call timeout for POST /practice/{id}/submit given the number of CODE answers in it. */
export function practiceSubmitTimeoutMs(codeAnswers) {
  const n = Math.max(0, Math.floor(Number(codeAnswers) || 0))
  if (n === 0) return SUBMIT_BASE_TIMEOUT_MS
  return Math.min(SUBMIT_MAX_TIMEOUT_MS, SUBMIT_BASE_TIMEOUT_MS + n * CODE_JUDGE_BUDGET_MS)
}

/** Number of submitted answers whose question is a CODE question. */
export function codeAnswerCount(questions, answers) {
  if (!Array.isArray(questions) || !Array.isArray(answers)) return 0
  const codeIds = new Set(questions.filter((q) => isCodeType(q?.type)).map((q) => Number(q.questionId)))
  return answers.filter((a) => codeIds.has(Number(a?.questionId))).length
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
