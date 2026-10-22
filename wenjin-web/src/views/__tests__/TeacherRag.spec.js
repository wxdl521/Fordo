import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({
  fetchRagSources: vi.fn(),
  fetchRagMappings: vi.fn(),
  approveRagMapping: vi.fn(),
  rejectRagMapping: vi.fn(),
  batchApproveRagMappings: vi.fn(),
  createNodeRefSource: vi.fn(),
  uploadRagSource: vi.fn(),
  reembedRagCourse: vi.fn()
}))
const course = vi.hoisted(() => ({ ref: null }))

vi.mock('../../api/teacher.js', () => ({
  fetchRagSources: (...a) => mocks.fetchRagSources(...a),
  fetchRagMappings: (...a) => mocks.fetchRagMappings(...a),
  approveRagMapping: (...a) => mocks.approveRagMapping(...a),
  rejectRagMapping: (...a) => mocks.rejectRagMapping(...a),
  batchApproveRagMappings: (...a) => mocks.batchApproveRagMappings(...a),
  createNodeRefSource: (...a) => mocks.createNodeRefSource(...a),
  uploadRagSource: (...a) => mocks.uploadRagSource(...a),
  reembedRagCourse: (...a) => mocks.reembedRagCourse(...a)
}))

vi.mock('../../composables/useTeacherCourse.js', async () => {
  const { ref } = await import('vue')
  course.ref = ref(7)
  return { useTeacherCourse: () => ({ courseId: course.ref, currentCourse: null }) }
})

import TeacherRag from '../TeacherRag.vue'

function source(over = {}) {
  return {
    id: 1, title: '讲义', kind: 0, status: 1, chunkCount: 5, pendingMapCount: 3,
    unlinkedChunkCount: 2, createdAt: '2026-10-22 10:00:00', ...over
  }
}

async function mountPage() {
  const w = mount(TeacherRag)
  await flushPromises()
  return w
}

function pickFile(w, name, size = 10) {
  const input = w.find('[data-testid="file-input"]')
  const file = new File([new Uint8Array(size)], name)
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  return input.trigger('change').then(() => file)
}

describe('TeacherRag 文件入库', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.fetchRagSources.mockResolvedValue([source()])
    mocks.fetchRagMappings.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('来源列表显示「未关联 N 块」', async () => {
    const w = await mountPage()
    expect(w.get('[data-testid="unlinked-cell"]').text()).toBe('未关联 2 块')
    w.unmount()
  })

  it('上传文件：调用 uploadRagSource(课程/文件/标题/类型) 后刷新列表并关闭弹窗', async () => {
    mocks.uploadRagSource.mockResolvedValue({ sourceId: 2, jobId: 9 })
    const w = await mountPage()
    await w.get('[data-testid="open-upload"]').trigger('click')
    expect(w.get('[data-testid="submit-upload"]').attributes('disabled')).toBeDefined()
    const file = await pickFile(w, 'os.pdf')
    await w.get('[data-testid="kind-select"]').setValue('1')
    await w.get('input[type="text"]').setValue('操作系统')
    expect(w.get('[data-testid="submit-upload"]').attributes('disabled')).toBeUndefined()
    await w.get('[data-testid="submit-upload"]').trigger('click')
    await flushPromises()

    expect(mocks.uploadRagSource).toHaveBeenCalledWith(7, file, { title: '操作系统', kind: 1 })
    expect(w.find('[role="dialog"]').exists()).toBe(false)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(2)
    w.unmount()
  })

  it('409 → 提示已上传过且弹窗保留', async () => {
    mocks.uploadRagSource.mockRejectedValue(Object.assign(new Error('数据冲突'), { httpStatus: 409 }))
    const w = await mountPage()
    await w.get('[data-testid="open-upload"]').trigger('click')
    await pickFile(w, 'dup.md')
    await w.get('[data-testid="submit-upload"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="upload-error"]').text()).toContain('已上传过')
    expect(w.find('[role="dialog"]').exists()).toBe(true)
    w.unmount()
  })

  it('拒绝不支持的扩展名与超 10MB 文件', async () => {
    const w = await mountPage()
    await w.get('[data-testid="open-upload"]').trigger('click')
    await pickFile(w, 'a.exe')
    expect(w.get('[data-testid="upload-error"]').text()).toContain('仅支持')
    expect(w.get('[data-testid="submit-upload"]').attributes('disabled')).toBeDefined()
    await pickFile(w, 'big.pdf', 10 * 1024 * 1024 + 1)
    expect(w.get('[data-testid="upload-error"]').text()).toContain('10MB')
    expect(mocks.uploadRagSource).not.toHaveBeenCalled()
    w.unmount()
  })

  it('粘贴模式保留 NODE_REF 入口，占位符为 node_code/title/source_ref', async () => {
    mocks.createNodeRefSource.mockResolvedValue({ id: 3 })
    const w = await mountPage()
    await w.get('[data-testid="open-paste"]').trigger('click')
    const ta = w.get('textarea')
    expect(ta.attributes('placeholder')).toContain('node_code:')
    expect(ta.attributes('placeholder')).toContain('source_ref:')
    expect(ta.attributes('placeholder')).not.toContain('nodeCode:')
    await ta.setValue('---\nnode_code: A\ntitle: t\nsource_ref: s\n---\n## h\nbody')
    await w.get('[data-testid="submit-upload"]').trigger('click')
    await flushPromises()
    expect(mocks.createNodeRefSource).toHaveBeenCalledWith(
      expect.objectContaining({ courseId: 7, markdown: expect.stringContaining('node_code') })
    )
    expect(mocks.uploadRagSource).not.toHaveBeenCalled()
    w.unmount()
  })

  it('INGESTING 来源每 3s 静默轮询，转为 READY 后停止', async () => {
    vi.useFakeTimers()
    mocks.fetchRagSources
      .mockResolvedValueOnce([source({ status: 0, chunkCount: 0 })])
      .mockResolvedValueOnce([source({ status: 0, chunkCount: 0 })])
      .mockResolvedValue([source({ status: 1 })])
    const w = mount(TeacherRag)
    await flushPromises()
    expect(w.find('[data-testid="ingesting-hint"]').exists()).toBe(true)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(3000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(3000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(3)
    expect(w.find('[data-testid="ingesting-hint"]').exists()).toBe(false)
    expect(w.text()).toContain('就绪')

    await vi.advanceTimersByTimeAsync(10000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(3)
    w.unmount()
  })

  it('失败来源显示「失败」', async () => {
    mocks.fetchRagSources.mockResolvedValue([source({ status: 2, chunkCount: 0 })])
    const w = await mountPage()
    expect(w.find('tr[data-status="2"]').text()).toContain('失败')
    w.unmount()
  })
})

describe('TeacherRag 向量覆盖与重建（R2）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.fetchRagMappings.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('向量模式关（embeddedChunks 为 null）→ 不显示覆盖列与「重建向量」', async () => {
    mocks.fetchRagSources.mockResolvedValue([source({ embeddedChunks: null, totalChunks: 5 })])
    const w = await mountPage()
    expect(w.find('[data-testid="coverage-cell"]').exists()).toBe(false)
    expect(w.find('[data-testid="reembed"]').exists()).toBe(false)
    w.unmount()
  })

  it('向量模式开 → 覆盖列显示 已嵌入/总块数，未覆盖完整时标警示', async () => {
    mocks.fetchRagSources.mockResolvedValue([
      source({ id: 1, embeddedChunks: 3, totalChunks: 5, embeddingModel: 'hash-ngram@1024' }),
      source({ id: 2, embeddedChunks: 4, totalChunks: 4, embeddingModel: 'hash-ngram@1024' })
    ])
    const w = await mountPage()
    const cells = w.findAll('[data-testid="coverage-cell"]')
    expect(cells.map((c) => c.text())).toEqual(['3/5', '4/4'])
    expect(cells[0].classes()).toContain('warn')
    expect(cells[1].classes()).not.toContain('warn')
    expect(cells[0].attributes('title')).toContain('hash-ngram@1024')
    w.unmount()
  })

  it('点击「重建向量」→ reembedRagCourse(课程) 并提示作业数，随后刷新列表', async () => {
    mocks.fetchRagSources.mockResolvedValue([source({ embeddedChunks: 0, totalChunks: 5 })])
    mocks.reembedRagCourse.mockResolvedValue({ model: 'embedding-3@1024', sourceCount: 2, jobIds: [11, 12] })
    const w = await mountPage()
    await w.get('[data-testid="reembed"]').trigger('click')
    await flushPromises()
    expect(mocks.reembedRagCourse).toHaveBeenCalledWith(7)
    expect(w.get('[data-testid="reembed-notice"]').text()).toContain('2')
    expect(w.get('[data-testid="reembed-notice"]').text()).toContain('embedding-3@1024')
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(2)
    w.unmount()
  })

  it('重建向量 404/503 → 显示对应提示', async () => {
    mocks.fetchRagSources.mockResolvedValue([source({ embeddedChunks: 0, totalChunks: 5 })])
    mocks.reembedRagCourse.mockRejectedValueOnce(Object.assign(new Error('资源不存在'), { httpStatus: 404 }))
    const w = await mountPage()
    await w.get('[data-testid="reembed"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="reembed-notice"]').text()).toContain('向量检索未开启')

    mocks.reembedRagCourse.mockRejectedValueOnce(
      Object.assign(new Error('未配置可用的嵌入模型，无法重建向量'), { httpStatus: 503 }))
    await w.get('[data-testid="reembed"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="reembed-notice"]').text()).toContain('未配置可用的嵌入模型')
    w.unmount()
  })

  it('重建后向量未覆盖完整时每 3s 静默轮询，覆盖完成后停止', async () => {
    vi.useFakeTimers()
    mocks.fetchRagSources
      .mockResolvedValueOnce([source({ embeddedChunks: 0, totalChunks: 5 })])
      .mockResolvedValueOnce([source({ embeddedChunks: 2, totalChunks: 5 })])
      .mockResolvedValueOnce([source({ embeddedChunks: 5, totalChunks: 5 })])
    mocks.reembedRagCourse.mockResolvedValue({ model: 'm', sourceCount: 1, jobIds: [1] })
    const w = await mountPage()
    await w.get('[data-testid="reembed"]').trigger('click')
    await flushPromises()
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(3000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(9000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(3)
    w.unmount()
  })
})

describe('TeacherRag 轮询健壮性（复核 M10）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    course.ref.value = 7
    mocks.fetchRagMappings.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('切换课程重置轮询预算：上一课程已耗尽 200 次后，新课程的入库轮询仍会继续', async () => {
    vi.useFakeTimers()
    mocks.fetchRagSources.mockImplementation(async () => [source({ status: 0, chunkCount: 0 })]) // 永远 INGESTING（每次新数组）
    const w = await mountPage()
    await vi.advanceTimersByTimeAsync(3000 * 205)
    const exhausted = mocks.fetchRagSources.mock.calls.length
    expect(exhausted).toBe(201) // 1 次初载 + 200 次轮询
    await vi.advanceTimersByTimeAsync(9000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(exhausted) // 预算耗尽后停止

    course.ref.value = 8
    await flushPromises()
    const afterSwitch = mocks.fetchRagSources.mock.calls.length
    expect(afterSwitch).toBe(exhausted + 1)
    await vi.advanceTimersByTimeAsync(3000)
    expect(mocks.fetchRagSources.mock.calls.length).toBe(afterSwitch + 1) // 新课程轮询继续
    w.unmount()
  })

  it('轮询出错：显示非阻塞提示并停止自动轮询，保留列表；点「刷新」恢复', async () => {
    vi.useFakeTimers()
    mocks.fetchRagSources
      .mockResolvedValueOnce([source({ status: 0, chunkCount: 0 })])
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValue([source({ status: 1 })])
    const w = await mountPage()
    await vi.advanceTimersByTimeAsync(3000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(2)
    const notice = w.get('[data-testid="poll-error"]')
    expect(notice.text()).toContain('自动刷新失败')
    expect(w.text()).toContain('讲义') // 列表未被清空
    expect(w.find('.empty.err').exists()).toBe(false) // 非阻塞：不替换整页

    await vi.advanceTimersByTimeAsync(9000)
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(2) // 不再自动重试

    await w.findAll('button').find((b) => b.text() === '刷新').trigger('click')
    await flushPromises()
    expect(mocks.fetchRagSources).toHaveBeenCalledTimes(3)
    expect(w.find('[data-testid="poll-error"]').exists()).toBe(false)
    expect(w.text()).toContain('就绪')
    w.unmount()
  })
})
