import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AdminPager from '../AdminPager.vue'

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)

function mountPager(props = {}) {
  return mount(AdminPager, { props: { testidPrefix: 'admin-demo', total: 45, page: 2, pageSize: 20, ...props } })
}

describe('AdminPager', () => {
  it('renders nothing when there are no records', () => {
    const wrapper = mountPager({ total: 0 })
    expect(wrapper.find('[data-testid="admin-demo-pagination"]').exists()).toBe(false)
  })

  it('shows total and "page / pages" under the given testid prefix', () => {
    const wrapper = mountPager()
    expect(tid(wrapper, 'admin-demo-total').text()).toBe('共 45 条')
    expect(tid(wrapper, 'admin-demo-page-info').text()).toBe('第 2 / 3 页')
    expect(tid(wrapper, 'admin-demo-prev').attributes('disabled')).toBeUndefined()
    expect(tid(wrapper, 'admin-demo-next').attributes('disabled')).toBeUndefined()
  })

  it('emits change with the neighbouring page', async () => {
    const wrapper = mountPager()
    await tid(wrapper, 'admin-demo-prev').trigger('click')
    await tid(wrapper, 'admin-demo-next').trigger('click')
    expect(wrapper.emitted('change')).toEqual([[1], [3]])
  })

  it('disables prev on the first page, next on the last page, and both while loading', () => {
    const first = mountPager({ page: 1 })
    expect(tid(first, 'admin-demo-prev').attributes('disabled')).toBeDefined()
    expect(tid(first, 'admin-demo-next').attributes('disabled')).toBeUndefined()

    const last = mountPager({ page: 3 })
    expect(tid(last, 'admin-demo-prev').attributes('disabled')).toBeUndefined()
    expect(tid(last, 'admin-demo-next').attributes('disabled')).toBeDefined()

    const busy = mountPager({ loading: true })
    expect(tid(busy, 'admin-demo-prev').attributes('disabled')).toBeDefined()
    expect(tid(busy, 'admin-demo-next').attributes('disabled')).toBeDefined()
  })
})
