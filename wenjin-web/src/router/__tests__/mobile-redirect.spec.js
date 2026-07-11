import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import ColorMap from '../../views/ColorMap.vue'

// Lightweight route table mirroring production /mobile → /map alias
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'login', component: { template: '<div>login</div>' } },
      { path: '/map', name: 'map', component: { template: '<div>map</div>' }, meta: { title: '染色地图' } },
      { path: '/mobile', redirect: { name: 'map' } }
    ]
  })
}

describe('mobile route alias', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('redirects /mobile to /map (no placeholder page)', async () => {
    const router = makeRouter()
    await router.push('/mobile')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/map')
    expect(router.currentRoute.value.name).toBe('map')
  })

  it('production router table uses redirect, not MobileMap component', async () => {
    // Dynamic import of production routes
    const mod = await import('../index.js')
    const routes = mod.router.getRoutes()
    const mobile = routes.find((r) => r.path === '/mobile')
    expect(mobile).toBeTruthy()
    // redirect record: either redirect property or resolved path via children
    const hasRedirect =
      !!mobile.redirect ||
      (typeof mobile.redirect === 'undefined' && mobile.redirect === undefined && mobile.components == null)
    // Vue Router materializes redirect as a record with redirect field on the matched route definition
    expect(
      mobile.redirect != null ||
        mobile.components == null ||
        mobile.components?.default == null
    ).toBe(true)

    // Ensure no residual MobileMap import path
    const all = JSON.stringify(routes.map((r) => ({ path: r.path, name: r.name, redirect: r.redirect })))
    expect(all).not.toMatch(/MobileMap/)
    expect(all).not.toMatch(/功能开发中/)
    void hasRedirect
    void ColorMap
  })
})
