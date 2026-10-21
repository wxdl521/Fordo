import { describe, it, expect, beforeEach } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '../../store/session.js'
import { useCourseStore } from '../../store/course.js'
import { useUiTour } from '../useUiTour.js'
import { storageKey } from '../tourSteps.js'

const Host = defineComponent({
  setup() {
    return useUiTour()
  },
  template: '<div />',
})

async function mountTour(path = '/') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore(pinia)
  session.setSession({
    token: 't',
    user: { id: 9, role: 2, realName: '学生' },
  })
  useCourseStore(pinia).setStudentCourseId(5)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/map', component: { template: '<div />' } },
      { path: '/diagnostic', component: { template: '<div />' } },
      { path: '/teacher/graph', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(Host, {
    global: { plugins: [pinia, router] },
  })
  await flushPromises()
  await nextTick()
  return { wrapper, router, session }
}

describe('useUiTour', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('starts and finishes to diagnostic when then=diagnostic', async () => {
    const { wrapper, router } = await mountTour('/')
    wrapper.vm.start({ teacher: false, then: 'diagnostic' })
    await nextTick()
    expect(wrapper.vm.open).toBe(true)
    expect(wrapper.vm.currentStep.id).toBe('map-canvas')
    wrapper.vm.finish()
    await flushPromises()
    expect(wrapper.vm.open).toBe(false)
    expect(localStorage.getItem(storageKey(9, false))).toBe('1')
    expect(router.currentRoute.value.path).toBe('/diagnostic')
    expect(router.currentRoute.value.query.courseId).toBe('5')
    wrapper.unmount()
  })

  it('teacher finish stays put', async () => {
    const { wrapper, router } = await mountTour('/')
    wrapper.vm.start({ teacher: true, then: 'close' })
    await nextTick()
    expect(wrapper.vm.isTeacherTour).toBe(true)
    expect(wrapper.vm.currentStep.id).toBe('nav-graph')
    wrapper.vm.finish()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(localStorage.getItem(storageKey(9, true))).toBe('1')
    wrapper.unmount()
  })

  it('student skipAll does nothing; must finish into diagnostic', async () => {
    const { wrapper, router } = await mountTour('/')
    wrapper.vm.start({ teacher: false, then: 'diagnostic' })
    await nextTick()
    wrapper.vm.skipAll()
    await flushPromises()
    expect(wrapper.vm.open).toBe(true)
    expect(router.currentRoute.value.path).toBe('/')
    wrapper.vm.finish()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/diagnostic')
    wrapper.unmount()
  })
})
