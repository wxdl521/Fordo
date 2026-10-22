import { describe, it, expect, afterEach, vi } from 'vitest'
import { chatStream, dispatchChatStreamEvent, streamSse } from '../src/api/companion.js'
import { assistantChatStream } from '../src/api/teacherAssistant.js'
import { toCsv } from '../src/utils/csv.js'

function sseResponse(text, status = 200) {
  const enc = new TextEncoder()
  return {
    ok: status < 400,
    status,
    body: {
      getReader: () => {
        let sent = false
        return {
          read: async () => {
            if (sent) return { done: true }
            sent = true
            return { done: false, value: enc.encode(text) }
          }
        }
      }
    }
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('streamSse / wrappers', () => {
  it('student chatStream still posts to companion URL and strips studentId', async () => {
    const f = vi.fn().mockResolvedValue(sseResponse('event: done\ndata: {}\n\n'))
    vi.stubGlobal('fetch', f)
    localStorage.setItem('wj_token', 'tok')
    const onDone = vi.fn()
    await chatStream({ courseId: 1, message: 'x', studentId: 9 }, { onDone })
    expect(f.mock.calls[0][0]).toBe('/api/companion/chat')
    expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ courseId: 1, message: 'x' })
    expect(f.mock.calls[0][1].headers.Authorization).toBe('Bearer tok')
    expect(onDone).toHaveBeenCalled()
  })

  it('teacher stream uses teacher URL and dispatches insight_card in order', async () => {
    const f = vi.fn().mockResolvedValue(
      sseResponse(
        'event: meta\ndata: {"conversationId":3}\n\n' +
          'event: tool_start\ndata: {"name":"a","label":"A"}\n\n' +
          'event: insight_card\ndata: {"title":"T","columns":["c"],"rows":[["1"]],"note":"n"}\n\n' +
          'event: token\ndata: {"t":"hi"}\n\n' +
          'event: done\ndata: {}\n\n'
      )
    )
    vi.stubGlobal('fetch', f)
    const calls = []
    await assistantChatStream(
      { courseId: 2, message: 'q' },
      {
        onMeta: (e) => calls.push(['meta', e.conversationId]),
        onToolStart: (e) => calls.push(['ts', e.name]),
        onInsightCard: (e) => calls.push(['card', e.title]),
        onToken: (t) => calls.push(['tok', t]),
        onDone: () => calls.push(['done'])
      }
    )
    expect(f.mock.calls[0][0]).toBe('/api/teacher/assistant/chat')
    expect(calls).toEqual([['meta', 3], ['ts', 'a'], ['card', 'T'], ['tok', 'hi'], ['done']])
  })

  it('404 surfaces httpStatus', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse('', 404)))
    await expect(streamSse('/x', {}, {})).rejects.toMatchObject({ httpStatus: 404 })
  })

  it('dispatch routes insight_card', () => {
    const onInsightCard = vi.fn()
    expect(dispatchChatStreamEvent('insight_card', { title: 't' }, { onInsightCard }).handled).toBe(true)
    expect(onInsightCard).toHaveBeenCalledWith({ title: 't' })
  })
})

describe('toCsv', () => {
  it('escapes quotes, commas and newlines', () => {
    expect(toCsv(['a', 'b,c'], [['x"y', 'l1\nl2'], [null, 5]])).toBe('a,"b,c"\r\n"x""y","l1\nl2"\r\n,5')
  })
})
