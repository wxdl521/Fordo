import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { createLatestRequest } from '../../utils/latestRequest.js'
import { isAbortError } from './adminUtils.js'

/**
 * 管理后台分页列表的公共逻辑（用户 / 操作日志 / 登录日志共用）。
 *
 * - draft 是输入框里正在编辑的筛选；applied 是上一次点「搜索」时生效的筛选（翻页沿用它）。
 * - 只保留最新一次请求：新请求会 abort 上一次，迟到的响应被丢弃。
 * - 当前页被清空（如删除后少了一页）→ 自动退回最后一页重新加载。
 * - 必须在组件 setup 中调用（内部注册 onBeforeUnmount 取消在途请求）。
 *
 * @param {object} options
 * @param {object} options.initialFilters  筛选初值（浅拷贝成 draft / applied）
 * @param {(query: object, config: { signal: AbortSignal }) => Promise<{records?: any[], total?: number}>} options.fetchPage
 * @param {(applied: object) => object} options.toQuery  把生效筛选转成后端查询参数（去空、转数字、trim）
 * @param {number} [options.pageSize]
 * @param {string} [options.errorMessage]  后端未给出 message 时的兜底文案
 */
export function usePagedList({ initialFilters, fetchPage, toQuery, pageSize = 20, errorMessage = '加载失败' }) {
  const draft = reactive({ ...initialFilters })
  const applied = ref({ ...initialFilters })
  const page = ref(1)
  const data = ref(null)
  const loading = ref(false)
  const loadError = ref('')
  const latest = createLatestRequest()
  let disposed = false

  const records = computed(() => data.value?.records || [])
  const total = computed(() => data.value?.total || 0)
  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

  async function load() {
    if (disposed) return
    const request = latest.next()
    loading.value = true
    loadError.value = ''
    try {
      const query = { ...toQuery(applied.value), page: page.value, size: pageSize }
      const res = await fetchPage(query, { signal: request.signal })
      if (!request.isCurrent()) return
      data.value = {
        records: Array.isArray(res?.records) ? res.records : [],
        total: Number(res?.total) || 0
      }
      if (data.value.records.length === 0 && page.value > 1 && data.value.total > 0) {
        page.value = Math.max(1, Math.ceil(data.value.total / pageSize))
        await load()
        return
      }
    } catch (err) {
      if (!request.isCurrent() || isAbortError(err)) return
      loadError.value = err?.message || errorMessage
    } finally {
      if (request.isCurrent()) loading.value = false
    }
  }

  /** 以输入框里的筛选条件重新查询，回到第 1 页。 */
  function search() {
    applied.value = { ...draft }
    page.value = 1
    return load()
  }

  /** 清空筛选并查询。 */
  function reset() {
    Object.assign(draft, initialFilters)
    return search()
  }

  function goPage(next) {
    if (next < 1 || next > totalPages.value || next === page.value) return
    page.value = next
    return load()
  }

  onBeforeUnmount(() => {
    disposed = true
    latest.cancel()
  })

  return { draft, applied, page, loading, loadError, records, total, totalPages, load, search, reset, goPage }
}
