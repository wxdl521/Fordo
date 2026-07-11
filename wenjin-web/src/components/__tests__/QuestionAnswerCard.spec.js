import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import QuestionAnswerCard from '../QuestionAnswerCard.vue'

const singleQ = {
  questionId: 1,
  c: '第一章',
  q: '软件工程是什么？',
  o: ['工程学科', '编程语言', '操作系统', '数据库'],
  type: 1
}

const multiQ = {
  questionId: 2,
  c: '第二章',
  q: '下列哪些属于敏捷实践？',
  o: ['站会', '瀑布', '结对编程', '瀑布二'],
  type: 2
}

function mountCard(props = {}) {
  return mount(QuestionAnswerCard, {
    props: {
      question: singleQ,
      index: 1,
      total: 5,
      hasSelection: false,
      ...props
    }
  })
}

describe('QuestionAnswerCard a11y', () => {
  it('uses radiogroup/radio roles for single-choice', () => {
    const w = mountCard({ selected: 0 })
    const group = w.find('.qa-options')
    expect(group.attributes('role')).toBe('radiogroup')
    const opts = w.findAll('.qa-opt')
    expect(opts).toHaveLength(4)
    expect(opts[0].attributes('role')).toBe('radio')
    expect(opts[0].attributes('aria-checked')).toBe('true')
    expect(opts[1].attributes('aria-checked')).toBe('false')
    expect(opts[0].attributes('tabindex')).toBe('0')
    expect(opts[1].attributes('tabindex')).toBe('-1')
  })

  it('uses checkbox roles for multi-choice', () => {
    const w = mountCard({
      question: multiQ,
      selectedSet: [0, 2]
    })
    expect(w.find('.qa-options').attributes('role')).toBe('group')
    const opts = w.findAll('.qa-opt')
    expect(opts[0].attributes('role')).toBe('checkbox')
    expect(opts[0].attributes('aria-checked')).toBe('true')
    expect(opts[1].attributes('aria-checked')).toBe('false')
    expect(opts[2].attributes('aria-checked')).toBe('true')
    opts.forEach((o) => expect(o.attributes('tabindex')).toBe('0'))
  })

  it('emits pick on Space and Enter', async () => {
    const w = mountCard()
    const first = w.findAll('.qa-opt')[0]
    await first.trigger('keydown', { key: ' ' })
    expect(w.emitted('pick')?.[0]).toEqual([0])
    await first.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('pick')?.[1]).toEqual([0])
  })

  it('moves selection with arrow keys in radiogroup', async () => {
    const w = mountCard({ selected: 0 })
    const first = w.findAll('.qa-opt')[0]
    await first.trigger('keydown', { key: 'ArrowDown' })
    expect(w.emitted('pick')?.at(-1)).toEqual([1])
  })
})
