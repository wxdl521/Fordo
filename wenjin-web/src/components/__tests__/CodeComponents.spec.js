import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CodeAnswerEditor from '../CodeAnswerEditor.vue'
import CodeResultView from '../CodeResultView.vue'
import TeacherCodeEditor from '../TeacherCodeEditor.vue'
import QuestionAnswerCard from '../QuestionAnswerCard.vue'
import { insertAtSelection, newlineWithIndent, verdictLabel, codeQuestionFields } from '../../utils/codeResult.js'

describe('codeResult utils', () => {
  it('inserts text at selection', () => {
    expect(insertAtSelection('ab', 1, 1, '  ')).toEqual({ value: 'a  b', caret: 3 })
  })
  it('keeps indentation and adds one level after an opening brace', () => {
    expect(newlineWithIndent('    foo();', 10, 10).value).toBe('    foo();\n    ')
    expect(newlineWithIndent('  if (x) {', 10, 10).value).toBe('  if (x) {\n      ')
  })
  it('labels verdicts and maps question fields only for CODE', () => {
    expect(verdictLabel('TIMEOUT')).toBe('运行超时')
    expect(codeQuestionFields({ type: 1 })).toEqual({})
    expect(codeQuestionFields({ type: 5, starterCode: 'x', sampleCases: [{ seq: 1, stdin: 'i', expectedStdout: 'o' }] }).sampleCases).toHaveLength(1)
  })
})

describe('CodeAnswerEditor', () => {
  it('has an accessible label and Tab inserts four spaces', async () => {
    const w = mount(CodeAnswerEditor, { props: { modelValue: 'x' } })
    const ta = w.find('textarea')
    expect(ta.attributes('aria-label')).toBeTruthy()
    ta.element.setSelectionRange(1, 1)
    await ta.trigger('keydown', { key: 'Tab' })
    expect(w.emitted('update:modelValue').at(-1)[0]).toBe('x    ')
  })
  it('Escape then Tab does not trap focus', async () => {
    const w = mount(CodeAnswerEditor, { props: { modelValue: 'x' } })
    const ta = w.find('textarea')
    await ta.trigger('keydown', { key: 'Escape' })
    await ta.trigger('keydown', { key: 'Tab' })
    expect(w.emitted('update:modelValue')).toBeUndefined()
  })
  it('Enter preserves indentation', async () => {
    const w = mount(CodeAnswerEditor, { props: { modelValue: '  a' } })
    const ta = w.find('textarea')
    ta.element.setSelectionRange(3, 3)
    await ta.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('update:modelValue').at(-1)[0]).toBe('  a\n  ')
  })
})

describe('CodeResultView', () => {
  it('shows compile error and hides diff for hidden cases', () => {
    const w = mount(CodeResultView, {
      props: {
        result: {
          verdict: 'COMPILE_ERROR', compileError: 'Main.java:1: error', passedCases: 0, totalCases: 2,
          cases: [
            { index: 1, passed: false, hidden: false, verdict: 'WRONG_ANSWER', stdinPreview: 'in', expectedPreview: 'E1', actualPreview: 'A1' },
            { index: 2, passed: false, hidden: true, verdict: 'WRONG_ANSWER', expectedPreview: 'E2', actualPreview: 'A2' }
          ]
        }
      }
    })
    expect(w.get('[data-testid="code-compile-error"]').text()).toContain('error')
    expect(w.text()).toContain('E1')
    expect(w.text()).not.toContain('E2')
    expect(w.text()).toContain('隐藏')
  })
  it('renders RUN output', () => {
    const w = mount(CodeResultView, { props: { result: { mode: 'RUN', verdict: 'ACCEPTED', stdout: 'hello' } } })
    expect(w.text()).toContain('试运行')
    expect(w.get('[data-testid="code-stdout"]').text()).toBe('hello')
  })
})

describe('QuestionAnswerCard CODE', () => {
  it('renders editor + samples and emits textInput', async () => {
    const w = mount(QuestionAnswerCard, {
      props: {
        question: { questionId: 1, c: 'c', q: '题', o: [], type: 5, sampleCases: [{ seq: 1, stdin: '1', expectedStdout: '2' }] },
        index: 1, total: 1, textValue: 'code', hasSelection: true
      }
    })
    expect(w.find('.qa-options').exists()).toBe(false)
    expect(w.findAll('[data-testid="qa-sample"]')).toHaveLength(1)
    await w.find('textarea').setValue('new')
    expect(w.emitted('textInput')[0]).toEqual(['new'])
  })
})

describe('TeacherCodeEditor', () => {
  const question = { id: 9, starterCode: 'S', cases: [{ seq: 1, stdin: 'a', expectedStdout: 'b', hidden: true }] }
  it('lists all cases including hidden ones', () => {
    const w = mount(TeacherCodeEditor, { props: { question } })
    expect(w.findAll('[data-testid="tce-case"]')).toHaveLength(1)
    expect(w.text()).toContain('隐藏')
  })
  it('adds, toggles hidden, removes and saves renumbered cases', async () => {
    const calls = []
    const w = mount(TeacherCodeEditor, { props: { question, saveFn: async (p) => { calls.push(p) } } })
    await w.get('[data-testid="tce-edit"]').trigger('click')
    await w.get('[data-testid="tce-add"]').trigger('click')
    const rows = w.findAll('[data-testid="tce-edit-case"]')
    expect(rows).toHaveLength(2)
    await rows[1].findAll('textarea')[0].setValue('x')
    await rows[1].findAll('textarea')[1].setValue('y')
    await rows[1].get('[data-testid="tce-hidden"]').setValue(true)
    await rows[0].get('[data-testid="tce-remove"]').trigger('click')
    await w.get('form').trigger('submit')
    await new Promise((r) => setTimeout(r))
    expect(calls[0].cases).toEqual([{ seq: 1, stdin: 'x', expectedStdout: 'y', hidden: true }])
    expect(w.emitted('saved')).toBeTruthy()
  })
  it('refuses to save zero cases', async () => {
    const w = mount(TeacherCodeEditor, { props: { question: { id: 1, cases: [] }, saveFn: async () => {} } })
    await w.get('[data-testid="tce-edit"]').trigger('click')
    await w.get('form').trigger('submit')
    expect(w.get('[role="alert"]').text()).toContain('至少')
  })
})
