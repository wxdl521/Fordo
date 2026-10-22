import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({
  fetchRagSources: vi.fn(),
  fetchRagMappings: vi.fn(),
  approveRagMapping: vi.fn(),
  rejectRagMapping: vi.fn(),
  batchApproveRagMappings: vi.fn(),
  createNodeRefSource: vi.fn(),
  uploadRagSource: vi.fn()
}))

vi.mock('../../api/teacher.js', () => ({
  fetchRagSources: (...a) => mocks.fetchRagSources(...a),
  fetchRagMappings: (...a) => mocks.fetchRagMappings(...a),
  approveRagMapping: (...a) => mocks.approveRagMapping(...a),
  rejectRagMapping: (...a) => mocks.rejectRagMapping(...a),
  batchApproveRagMappings: (...a) => mocks.batchApproveRagMappings(...a),
  createNodeRefSource: (...a) => mocks.createNodeRefSource(...a),
  uploadRagSource: (...a) => mocks.uploadRagSource(...a)
}))

vi.mock('../../composables/useTeacherCourse.js', () => ({
  useTeacherCourse: () => ({ courseId: { __v_isRef: true, value: 7 }, currentCourse: null })
}))

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
