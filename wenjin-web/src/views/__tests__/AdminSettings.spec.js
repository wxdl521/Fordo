import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  listConfigs: vi.fn(),
  updateConfig: vi.fn(),
  resetConfig: vi.fn(),
  testAiConnection: vi.fn()
}))

vi.mock('../../api/systemAdmin.js', () => api)

import AdminSettings from '../AdminSettings.vue'

// 与后端契约一致：secret 的 value / defaultValue 恒为 null，只给 configured 与 masked
const MODEL = {
  key: 'wenjin.ai.model',
  label: '模型名',
  description: '文本模型的名字',
  type: 'string',
  group: 'AI 文本',
  value: 'deepseek-v4-pro',
  defaultValue: 'deepseek-v4-pro',
  overridden: true,
  updatedAt: '2026-10-20T10:00:00',
  updatedBy: 1
}
const API_KEY = {
  key: 'wenjin.ai.api-key',
  label: 'API Key',
  description: '文本模型密钥',
  type: 'secret',
  value: null,
  defaultValue: null,
  configured: true,
  masked: 'ab12',
  overridden: true
}
const TEMPERATURE = {
  key: 'wenjin.ai.temperature',
  label: '温度',
  description: '采样温度',
  type: 'number',
  value: 0.7,
  defaultValue: 0.7,
  min: 0,
  max: 2,
  overridden: false
}
const TEXT_ENABLED = {
  key: 'wenjin.ai.enabled',
  label: '启用 AI 文本',
  type: 'bool',
  value: true,
  defaultValue: true,
  overridden: false
}
const VISION_API_KEY = {
  key: 'wenjin.ai.vision.api-key',
  label: '视觉 API Key',
  type: 'secret',
  value: null,
  defaultValue: null,
  configured: false,
  masked: null,
  overridden: false
}
const VISION_ENABLED = {
  key: 'wenjin.ai.vision.enabled',
  label: '启用 AI 视觉',
  type: 'bool',
  value: true,
  defaultValue: true,
  overridden: false
}
const MEMORY = {
  key: 'wenjin.features.memory-enabled',
  label: '长期记忆',
  type: 'bool',
  value: false,
  defaultValue: true,
  overridden: true
}

function groups() {
  return [
    { group: 'AI 文本', items: [TEXT_ENABLED, { ...MODEL }, { ...API_KEY }, { ...TEMPERATURE }] },
    { group: 'AI 视觉', items: [VISION_ENABLED, { ...VISION_API_KEY }] },
    { group: '功能开关', items: [{ ...MEMORY }] }
  ]
}

async function mountPage() {
  const wrapper = mount(AdminSettings)
  await flushPromises()
  return wrapper
}

const tid = (wrapper, id) => wrapper.get(`[data-testid="${id}"]`)
const has = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`).exists()

describe('AdminSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listConfigs.mockResolvedValue(groups())
    api.updateConfig.mockImplementation(async (key, value) => ({ key, value, overridden: true }))
    api.resetConfig.mockResolvedValue({})
    api.testAiConnection.mockResolvedValue({ ok: true, latencyMs: 0, model: '' })
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('list', () => {
    it('renders one card per group with overridden badges, current / default values and AI test buttons only on AI groups', async () => {
      const wrapper = await mountPage()

      expect(api.listConfigs).toHaveBeenCalledTimes(1)
      expect(wrapper.findAll('section')).toHaveLength(3)

      expect(tid(wrapper, 'admin-config-overridden-wenjin.ai.model').text()).toBe('已覆盖')
      expect(has(wrapper, 'admin-config-overridden-wenjin.ai.temperature')).toBe(false)

      const temp = tid(wrapper, 'admin-config-wenjin.ai.temperature').text()
      expect(temp).toContain('温度')
      expect(temp).toContain('采样温度')
      expect(temp).toContain('当前值：0.7')
      expect(temp).toContain('默认值：0.7')
      expect(temp).toContain('取值范围：0 – 2')

      // 恢复默认：未覆盖的项禁用，已覆盖的项可点
      expect(tid(wrapper, 'admin-config-reset-wenjin.ai.temperature').attributes('disabled')).toBeDefined()
      expect(tid(wrapper, 'admin-config-reset-wenjin.ai.model').attributes('disabled')).toBeUndefined()

      expect(has(wrapper, 'admin-ai-test-text')).toBe(true)
      expect(has(wrapper, 'admin-ai-test-vision')).toBe(true)
      expect(has(wrapper, 'admin-ai-test-result-text')).toBe(false)
      wrapper.unmount()
    })

    it('shows a load error with a retry button, and the retry reloads', async () => {
      api.listConfigs.mockRejectedValueOnce(new Error('无访问权限'))
      const wrapper = await mountPage()

      expect(tid(wrapper, 'admin-settings-error').text()).toContain('无访问权限')
      expect(has(wrapper, 'admin-config-wenjin.ai.model')).toBe(false)

      await tid(wrapper, 'admin-settings-retry').trigger('click')
      await flushPromises()

      expect(api.listConfigs).toHaveBeenCalledTimes(2)
      expect(has(wrapper, 'admin-settings-error')).toBe(false)
      expect(has(wrapper, 'admin-config-wenjin.ai.model')).toBe(true)
      wrapper.unmount()
    })
  })

  describe('secrets', () => {
    it('shows only the configured state and mask, and never renders plaintext', async () => {
      const wrapper = await mountPage()

      const row = tid(wrapper, 'admin-config-wenjin.ai.api-key').text()
      expect(row).toContain('已配置 ••••ab12')
      expect(row).toContain('默认值：不展示（密钥）')
      expect(tid(wrapper, 'admin-config-input-wenjin.ai.api-key').attributes('type')).toBe('password')
      expect(tid(wrapper, 'admin-config-input-wenjin.ai.api-key').element.value).toBe('')

      const unset = tid(wrapper, 'admin-config-wenjin.ai.vision.api-key').text()
      expect(unset).toContain('未配置')
      expect(unset).not.toContain('已配置')
      wrapper.unmount()
    })

    it('saves a typed secret, clears the input afterwards and never prints it on the page', async () => {
      const wrapper = await mountPage()
      const input = tid(wrapper, 'admin-config-input-wenjin.ai.api-key')
      await input.setValue('sk-SECRET-1234')
      expect(wrapper.html()).not.toContain('sk-SECRET')

      api.updateConfig.mockResolvedValueOnce({ ...API_KEY, configured: true, masked: '1234', overridden: true })
      await tid(wrapper, 'admin-config-save-wenjin.ai.api-key').trigger('click')
      await flushPromises()

      expect(api.updateConfig).toHaveBeenCalledWith('wenjin.ai.api-key', 'sk-SECRET-1234')
      expect(tid(wrapper, 'admin-config-input-wenjin.ai.api-key').element.value).toBe('')
      expect(tid(wrapper, 'admin-config-wenjin.ai.api-key').text()).toContain('已配置 ••••1234')
      expect(tid(wrapper, 'admin-settings-notice').text()).toBe('已保存「API Key」')
      expect(wrapper.html()).not.toContain('sk-SECRET')
      expect(wrapper.text()).not.toContain('sk-SECRET')
      wrapper.unmount()
    })

    it('does not send an empty secret', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-config-save-wenjin.ai.vision.api-key').trigger('click')
      await flushPromises()

      expect(api.updateConfig).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-config-error-wenjin.ai.vision.api-key').text()).toBe('请输入新的密钥')
      wrapper.unmount()
    })
  })

  describe('save and restore', () => {
    it('saves a number as a number and the response replaces the row', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-config-input-wenjin.ai.temperature').setValue('1.2')

      api.updateConfig.mockResolvedValueOnce({ ...TEMPERATURE, value: 1.2, overridden: true })
      await tid(wrapper, 'admin-config-save-wenjin.ai.temperature').trigger('click')
      await flushPromises()

      expect(api.updateConfig).toHaveBeenCalledWith('wenjin.ai.temperature', 1.2)
      expect(tid(wrapper, 'admin-config-overridden-wenjin.ai.temperature').text()).toBe('已覆盖')
      expect(tid(wrapper, 'admin-config-wenjin.ai.temperature').text()).toContain('当前值：1.2')
      expect(tid(wrapper, 'admin-settings-notice').text()).toBe('已保存「温度」')
      wrapper.unmount()
    })

    it('rejects an out-of-range number on the client without calling the API', async () => {
      const wrapper = await mountPage()
      const input = tid(wrapper, 'admin-config-input-wenjin.ai.temperature')

      await input.setValue('3')
      await tid(wrapper, 'admin-config-save-wenjin.ai.temperature').trigger('click')
      await flushPromises()
      expect(tid(wrapper, 'admin-config-error-wenjin.ai.temperature').text()).toBe('不能大于 2')

      await input.setValue('-0.5')
      await tid(wrapper, 'admin-config-save-wenjin.ai.temperature').trigger('click')
      await flushPromises()
      expect(tid(wrapper, 'admin-config-error-wenjin.ai.temperature').text()).toBe('不能小于 0')

      await input.setValue('')
      await tid(wrapper, 'admin-config-save-wenjin.ai.temperature').trigger('click')
      await flushPromises()
      expect(tid(wrapper, 'admin-config-error-wenjin.ai.temperature').text()).toContain('请输入数字')

      expect(api.updateConfig).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('refuses a blank string value without calling the API', async () => {
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-config-input-wenjin.ai.model').setValue('   ')
      await tid(wrapper, 'admin-config-save-wenjin.ai.model').trigger('click')
      await flushPromises()

      expect(api.updateConfig).not.toHaveBeenCalled()
      expect(tid(wrapper, 'admin-config-error-wenjin.ai.model').text()).toBe('请填写内容')
      wrapper.unmount()
    })

    it('shows the backend message inline when a save is rejected', async () => {
      api.updateConfig.mockRejectedValueOnce(new Error('模型名不合法'))
      const wrapper = await mountPage()
      await tid(wrapper, 'admin-config-input-wenjin.ai.model').setValue('bad model')
      await tid(wrapper, 'admin-config-save-wenjin.ai.model').trigger('click')
      await flushPromises()

      expect(api.updateConfig).toHaveBeenCalledWith('wenjin.ai.model', 'bad model')
      expect(tid(wrapper, 'admin-config-error-wenjin.ai.model').text()).toBe('模型名不合法')
      expect(has(wrapper, 'admin-settings-notice')).toBe(false)
      wrapper.unmount()
    })

    it('restores the default after confirmation and drops the badge from the response', async () => {
      api.resetConfig.mockResolvedValueOnce({ ...MODEL, value: 'deepseek-v4-pro', overridden: false })
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-config-reset-wenjin.ai.model').trigger('click')
      await flushPromises()

      expect(window.confirm).toHaveBeenCalledTimes(1)
      expect(api.resetConfig).toHaveBeenCalledWith('wenjin.ai.model')
      expect(has(wrapper, 'admin-config-overridden-wenjin.ai.model')).toBe(false)
      expect(tid(wrapper, 'admin-settings-notice').text()).toBe('已恢复「模型名」的默认值')
      wrapper.unmount()
    })

    it('does nothing when the restore confirmation is declined', async () => {
      window.confirm.mockReturnValueOnce(false)
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-config-reset-wenjin.ai.model').trigger('click')
      await flushPromises()

      expect(api.resetConfig).not.toHaveBeenCalled()
      expect(has(wrapper, 'admin-config-overridden-wenjin.ai.model')).toBe(true)
      wrapper.unmount()
    })

    it('toggles a bool immediately and saves the new value', async () => {
      api.updateConfig.mockResolvedValueOnce({ ...MEMORY, value: true, overridden: true })
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-config-input-wenjin.features.memory-enabled').setValue(true)
      await flushPromises()

      expect(api.updateConfig).toHaveBeenCalledWith('wenjin.features.memory-enabled', true)
      expect(tid(wrapper, 'admin-config-wenjin.features.memory-enabled').text()).toContain('开启')
      wrapper.unmount()
    })

    it('flips the switch back when the bool save fails', async () => {
      api.updateConfig.mockRejectedValueOnce(new Error('保存失败：数据库不可用'))
      const wrapper = await mountPage()
      const checkbox = tid(wrapper, 'admin-config-input-wenjin.features.memory-enabled')

      await checkbox.setValue(true)
      await flushPromises()

      expect(checkbox.element.checked).toBe(false)
      expect(tid(wrapper, 'admin-config-error-wenjin.features.memory-enabled').text()).toBe('保存失败：数据库不可用')
      wrapper.unmount()
    })
  })

  describe('AI connection test', () => {
    it('shows success with latency and model for the text target', async () => {
      api.testAiConnection.mockResolvedValueOnce({ ok: true, latencyMs: 842, model: 'deepseek-v4-pro' })
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-ai-test-text').trigger('click')
      await flushPromises()

      expect(api.testAiConnection).toHaveBeenCalledWith('text')
      const result = tid(wrapper, 'admin-ai-test-result-text')
      expect(result.text()).toContain('连接成功')
      expect(result.text()).toContain('842 ms')
      expect(result.text()).toContain('deepseek-v4-pro')
      wrapper.unmount()
    })

    it('shows the failure reason for the vision target and never echoes a key', async () => {
      api.testAiConnection.mockResolvedValueOnce({ ok: false, latencyMs: 15, model: 'glm-5v-turbo', error: '401 Unauthorized' })
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-ai-test-vision').trigger('click')
      await flushPromises()

      expect(api.testAiConnection).toHaveBeenCalledWith('vision')
      const result = tid(wrapper, 'admin-ai-test-result-vision')
      expect(result.attributes('role')).toBe('alert')
      expect(result.text()).toContain('连接失败')
      expect(result.text()).toContain('401 Unauthorized')
      expect(result.text()).toContain('glm-5v-turbo')
      expect(wrapper.text()).not.toMatch(/sk-/)
      wrapper.unmount()
    })

    it('reports a network failure of the test call itself as a failed result', async () => {
      api.testAiConnection.mockRejectedValueOnce(new Error('请求超时'))
      const wrapper = await mountPage()

      await tid(wrapper, 'admin-ai-test-text').trigger('click')
      await flushPromises()

      expect(tid(wrapper, 'admin-ai-test-result-text').text()).toContain('请求超时')
      wrapper.unmount()
    })
  })
})
