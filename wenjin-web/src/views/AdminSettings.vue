<template>
  <div data-testid="admin-settings-page" :style="pageStyle">
    <AdminPageHeader
      title="系统配置"
      subtitle="在线调整 AI 连接与功能开关等运行参数。改动立即生效，无需重启；恢复默认后回到启动配置（yml / 环境变量）的取值。"
    />

    <!-- 加载 -->
    <div v-if="loadError" data-testid="admin-settings-error" role="alert" :style="errorStyle">
      {{ loadError }}
      <button type="button" data-testid="admin-settings-retry" class="wj-admin-btn" :style="{ ...ghostBtnStyle, marginLeft: '10px', height: '28px' }" @click="load">重新加载</button>
    </div>
    <div v-if="loading && groups.length === 0" data-testid="admin-settings-loading" :style="hintStyle">加载中…</div>
    <div v-else-if="!loadError && groups.length === 0" data-testid="admin-settings-empty" :style="hintStyle">暂无可编辑的配置项。</div>

    <!-- 分组卡片 -->
    <section v-for="group in groups" :key="group.group" :aria-label="group.group" :style="cardStyle">
      <h2 :style="groupTitleStyle">{{ group.group }}</h2>

      <div v-for="item in group.items" :key="item.key" :data-testid="`admin-config-${item.key}`" :style="itemStyle">
        <div :style="itemHeadStyle">
          <div :style="infoStyle">
            <div :style="labelRowStyle">
              <span>{{ item.label }}</span>
              <span v-if="item.overridden" :data-testid="`admin-config-overridden-${item.key}`" :style="overriddenTagStyle">已覆盖</span>
            </div>
            <div v-if="item.description" :style="descStyle">{{ item.description }}</div>
            <div :style="metaStyle">
              <span>当前值：<b :style="valueStrongStyle">{{ currentText(item) }}</b></span>
              <span v-if="item.type === 'secret'">默认值：不展示（密钥）</span>
              <span v-else>默认值：{{ defaultText(item) }}</span>
              <span v-if="rangeText(item)">取值范围：{{ rangeText(item) }}</span>
            </div>
          </div>

          <div :style="controlStyle">
            <!-- bool：开关，改动即保存 -->
            <label v-if="item.type === 'bool'" :style="switchStyle">
              <input
                type="checkbox"
                :data-testid="`admin-config-input-${item.key}`"
                :aria-label="item.label"
                :checked="item.value === true"
                :disabled="busyKey !== null"
                :style="{ accentColor: 'var(--acc)' }"
                @change="onToggle(item, $event)"
              />
              <span>{{ item.value ? '开启' : '关闭' }}</span>
            </label>

            <!-- secret：只显示是否已配置与掩码；输入框永远是空的 -->
            <template v-else-if="item.type === 'secret'">
              <input
                v-model="drafts[item.key]"
                type="password"
                autocomplete="new-password"
                :data-testid="`admin-config-input-${item.key}`"
                :aria-label="`${item.label}（输入新值）`"
                placeholder="输入新的密钥"
                class="wj-admin-field"
                :style="inputStyle(220)"
                :disabled="busyKey !== null"
                @keydown.enter="onEnter($event, () => save(item))"
              />
              <button
                type="button"
                :data-testid="`admin-config-save-${item.key}`"
                class="wj-admin-btn"
                :style="primaryBtnStyle"
                :disabled="busyKey !== null"
                @click="save(item)"
              >{{ busyKey === item.key ? '更换中…' : '更换' }}</button>
            </template>

            <!-- number / string / enum：输入或下拉 + 保存 -->
            <template v-else>
              <select
                v-if="item.type === 'enum'"
                v-model="drafts[item.key]"
                :data-testid="`admin-config-input-${item.key}`"
                :aria-label="item.label"
                class="wj-admin-field"
                :style="inputStyle(200)"
                :disabled="busyKey !== null"
              >
                <option v-for="opt in optionsOf(item)" :key="String(opt.value)" :value="String(opt.value)">{{ opt.label }}</option>
              </select>
              <input
                v-else-if="item.type === 'number'"
                v-model="drafts[item.key]"
                type="number"
                step="any"
                :min="typeof item.min === 'number' ? item.min : undefined"
                :max="typeof item.max === 'number' ? item.max : undefined"
                :data-testid="`admin-config-input-${item.key}`"
                :aria-label="item.label"
                class="wj-admin-field"
                :style="inputStyle(160)"
                :disabled="busyKey !== null"
                @keydown.enter="onEnter($event, () => save(item))"
              />
              <input
                v-else
                v-model="drafts[item.key]"
                type="text"
                autocomplete="off"
                :data-testid="`admin-config-input-${item.key}`"
                :aria-label="item.label"
                class="wj-admin-field"
                :style="inputStyle(260)"
                :disabled="busyKey !== null"
                @keydown.enter="onEnter($event, () => save(item))"
              />
              <button
                type="button"
                :data-testid="`admin-config-save-${item.key}`"
                class="wj-admin-btn"
                :style="primaryBtnStyle"
                :disabled="busyKey !== null"
                @click="save(item)"
              >{{ busyKey === item.key ? '保存中…' : '保存' }}</button>
            </template>

            <button
              type="button"
              :data-testid="`admin-config-reset-${item.key}`"
              class="wj-admin-act"
              :style="actBtnStyle"
              :disabled="busyKey !== null || !item.overridden"
              :title="item.overridden ? undefined : '当前已是默认值'"
              @click="restore(item)"
            >恢复默认</button>
          </div>
        </div>

        <div v-if="itemErrors[item.key]" :data-testid="`admin-config-error-${item.key}`" role="alert" :style="itemErrorStyle">{{ itemErrors[item.key] }}</div>
      </div>

      <!-- AI 两组：测试连接 -->
      <div v-if="aiTargetOf(group)" :style="aiFooterStyle">
        <button
          type="button"
          :data-testid="`admin-ai-test-${aiTargetOf(group)}`"
          class="wj-admin-btn"
          :style="ghostBtnStyle"
          :disabled="aiTest[aiTargetOf(group)].loading"
          @click="runAiTest(aiTargetOf(group))"
        >{{ aiTest[aiTargetOf(group)].loading ? '测试中…' : '测试连接' }}</button>
        <span :style="aiHintStyle">用当前生效的配置发一个极小请求，不会回显密钥。</span>
        <div
          v-if="aiTest[aiTargetOf(group)].result"
          :data-testid="`admin-ai-test-result-${aiTargetOf(group)}`"
          :role="aiTest[aiTargetOf(group)].result.ok ? 'status' : 'alert'"
          :style="aiTest[aiTargetOf(group)].result.ok ? resultOkStyle : resultFailStyle"
        >
          <span>{{ aiTest[aiTargetOf(group)].result.ok ? '连接成功' : '连接失败' }}</span>
          <span v-if="aiTest[aiTargetOf(group)].result.latencyMs != null">耗时 {{ aiTest[aiTargetOf(group)].result.latencyMs }} ms</span>
          <span v-if="aiTest[aiTargetOf(group)].result.model">模型 {{ aiTest[aiTargetOf(group)].result.model }}</span>
          <span v-if="!aiTest[aiTargetOf(group)].result.ok && aiTest[aiTargetOf(group)].result.error">{{ aiTest[aiTargetOf(group)].result.error }}</span>
        </div>
      </div>
    </section>

    <!-- 轻提示：保存 / 恢复成功 -->
    <div v-if="notice" data-testid="admin-settings-notice" role="status" :style="toastStyle">{{ notice }}</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import AdminPageHeader from '../components/admin/AdminPageHeader.vue'
import {
  actBtnStyle,
  errorStyle,
  fieldStyle,
  ghostBtnStyle,
  hintStyle,
  modalErrorStyle,
  pageStyle,
  primaryBtnStyle,
  tagStyle
} from '../components/admin/adminStyle.js'
import { onEnter } from '../components/admin/adminUtils.js'
import { listConfigs, resetConfig, testAiConnection, updateConfig } from '../api/systemAdmin.js'

const groups = ref([])
const loading = ref(false)
const loadError = ref('')
const notice = ref('')
const busyKey = ref(null) // 正在保存 / 恢复的配置 key：同一时间只允许一个写操作
const itemErrors = reactive({}) // key → 行内错误文案（校验或后端 message）
const drafts = reactive({}) // key → 输入框草稿；secret 草稿永远不回填服务端值
const aiTest = reactive({
  text: { loading: false, result: null },
  vision: { loading: false, result: null }
})
let disposed = false
let noticeTimer = null

// ── 加载 ──
async function load() {
  if (disposed) return
  loading.value = true
  loadError.value = ''
  try {
    const list = await listConfigs()
    if (disposed) return
    groups.value = (Array.isArray(list) ? list : []).map((g) => ({
      group: g.group,
      items: Array.isArray(g.items) ? g.items : []
    }))
    // 首次加载时把服务端值灌进草稿；之后的刷新不覆盖正在编辑的输入
    for (const g of groups.value) {
      for (const item of g.items) {
        if (!(item.key in drafts)) syncDraft(item)
      }
    }
  } catch (err) {
    if (disposed) return
    loadError.value = err?.message || '加载系统配置失败'
  } finally {
    loading.value = false
  }
}

function findItem(key) {
  for (const g of groups.value) {
    const hit = g.items.find((i) => i.key === key)
    if (hit) return hit
  }
  return null
}

function replaceItem(next) {
  for (const g of groups.value) {
    const idx = g.items.findIndex((i) => i.key === next.key)
    if (idx >= 0) {
      g.items[idx] = next
      return
    }
  }
}

/** 草稿同步为服务端当前值（secret 清空）。 */
function syncDraft(item) {
  if (item.type === 'secret') {
    drafts[item.key] = ''
  } else if (item.value === null || item.value === undefined) {
    drafts[item.key] = ''
  } else {
    drafts[item.key] = String(item.value)
  }
}

/** 写操作成功后的收尾：用响应里的单项替换；响应缺失时整表重取。 */
async function commit(item, next) {
  if (next && typeof next.key === 'string') {
    replaceItem(next)
  } else {
    await load()
  }
  const fresh = findItem(item.key)
  if (fresh) syncDraft(fresh)
}

function showNotice(text) {
  notice.value = text
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    notice.value = ''
  }, 3500)
}

function clearRowFeedback(key) {
  itemErrors[key] = ''
}

// ── 展示 ──
function optionsOf(item) {
  const list = Array.isArray(item.options) ? item.options : []
  return list.map((o) =>
    o !== null && typeof o === 'object'
      ? { value: o.value, label: o.label ?? String(o.value) }
      : { value: o, label: String(o) }
  )
}

function displayValue(item, value) {
  if (value === null || value === undefined || value === '') return '未设置'
  if (item.type === 'bool') return value ? '开启' : '关闭'
  if (item.type === 'enum') {
    const opt = optionsOf(item).find((o) => String(o.value) === String(value))
    return opt ? opt.label : String(value)
  }
  return String(value)
}

function currentText(item) {
  if (item.type === 'secret') {
    if (!item.configured) return '未配置'
    return item.masked ? `已配置 ••••${item.masked}` : '已配置'
  }
  return displayValue(item, item.value)
}

function defaultText(item) {
  return displayValue(item, item.defaultValue)
}

function rangeText(item) {
  if (item.type !== 'number') return ''
  const hasMin = typeof item.min === 'number'
  const hasMax = typeof item.max === 'number'
  if (!hasMin && !hasMax) return ''
  return `${hasMin ? item.min : '不限'} – ${hasMax ? item.max : '不限'}`
}

/** AI 文本 / 视觉两组底部显示「测试连接」；按组内 key 前缀判定。 */
function aiTargetOf(group) {
  const keys = (group.items || []).map((i) => i.key)
  if (keys.some((k) => k.startsWith('wenjin.ai.vision.'))) return 'vision'
  if (keys.some((k) => k.startsWith('wenjin.ai.'))) return 'text'
  return ''
}

// ── 校验与保存 ──
function validateNumber(item, raw) {
  const text = String(raw ?? '').trim()
  if (text === '') return '请输入数字（想回到默认值请点「恢复默认」）'
  const n = Number(text)
  if (!Number.isFinite(n)) return '请输入有效的数字'
  if (typeof item.min === 'number' && n < item.min) return `不能小于 ${item.min}`
  if (typeof item.max === 'number' && n > item.max) return `不能大于 ${item.max}`
  return ''
}

function validateDraft(item) {
  const raw = drafts[item.key]
  if (item.type === 'number') return validateNumber(item, raw)
  if (item.type === 'secret') return String(raw ?? '').trim() ? '' : '请输入新的密钥'
  if (item.type === 'enum') return raw === '' || raw == null ? '请选择一个选项' : ''
  return String(raw ?? '').trim() ? '' : '请填写内容'
}

/** 把草稿转成提交给后端的类型化值（number 发数字，enum 发选项原值）。 */
function payloadOf(item) {
  const raw = drafts[item.key]
  if (item.type === 'number') return Number(String(raw).trim())
  if (item.type === 'enum') {
    const opt = optionsOf(item).find((o) => String(o.value) === String(raw))
    return opt ? opt.value : raw
  }
  return String(raw).trim()
}

async function save(item) {
  if (busyKey.value !== null) return
  const problem = validateDraft(item)
  itemErrors[item.key] = problem
  if (problem) return
  busyKey.value = item.key
  try {
    const next = await updateConfig(item.key, payloadOf(item))
    if (disposed) return
    await commit(item, next)
    showNotice(`已保存「${item.label}」`)
  } catch (err) {
    if (disposed) return
    itemErrors[item.key] = err?.message || '保存失败'
  } finally {
    busyKey.value = null
  }
}

/** bool 开关：改动即保存；失败时把开关拨回原状态。 */
async function onToggle(item, event) {
  const input = event.target
  const next = input.checked
  if (busyKey.value !== null) {
    input.checked = item.value === true
    return
  }
  busyKey.value = item.key
  clearRowFeedback(item.key)
  try {
    const res = await updateConfig(item.key, next)
    if (disposed) return
    await commit(item, res)
    showNotice(`已${next ? '开启' : '关闭'}「${item.label}」`)
  } catch (err) {
    if (disposed) return
    input.checked = item.value === true
    itemErrors[item.key] = err?.message || '保存失败'
  } finally {
    busyKey.value = null
  }
}

async function restore(item) {
  if (busyKey.value !== null || !item.overridden) return
  if (!window.confirm(`确定恢复「${item.label}」为默认值吗？当前的覆盖设置会被删除，并立即生效。`)) return
  busyKey.value = item.key
  clearRowFeedback(item.key)
  try {
    const res = await resetConfig(item.key)
    if (disposed) return
    await commit(item, res)
    showNotice(`已恢复「${item.label}」的默认值`)
  } catch (err) {
    if (disposed) return
    itemErrors[item.key] = err?.message || '恢复默认失败'
  } finally {
    busyKey.value = null
  }
}

// ── AI 连接测试 ──
async function runAiTest(target) {
  const state = aiTest[target]
  if (!state || state.loading) return
  state.loading = true
  state.result = null
  try {
    const res = await testAiConnection(target)
    if (disposed) return
    state.result = {
      ok: res?.ok === true,
      latencyMs: typeof res?.latencyMs === 'number' ? res.latencyMs : null,
      model: res?.model ? String(res.model) : '',
      error: res?.error ? String(res.error) : ''
    }
  } catch (err) {
    if (disposed) return
    state.result = { ok: false, latencyMs: null, model: '', error: err?.message || '测试失败' }
  } finally {
    state.loading = false
  }
}

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(noticeTimer)
})

void load()

// ── 样式（共用部分见 components/admin/adminStyle.js） ──
function inputStyle(width) {
  return { ...fieldStyle, width: `${width}px`, maxWidth: '100%' }
}

const cardStyle = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  padding: '14px 18px 16px',
  marginBottom: '16px',
  transition: 'background-color 0.35s, border-color 0.35s'
}

const groupTitleStyle = {
  margin: '0 0 4px',
  fontFamily: "'Noto Serif SC', serif",
  fontSize: '15px',
  fontWeight: 600,
  letterSpacing: '1px'
}

const itemStyle = {
  padding: '12px 0',
  borderTop: '1px solid var(--line)'
}

const itemHeadStyle = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: '10px 16px'
}

const infoStyle = {
  flex: '1 1 260px',
  minWidth: 0
}

const labelRowStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  flexWrap: 'wrap',
  fontSize: '13.5px',
  fontWeight: 500
}

const overriddenTagStyle = { ...tagStyle('warn') }

const descStyle = {
  fontSize: '12px',
  color: 'var(--mut)',
  lineHeight: 1.6,
  marginTop: '3px'
}

const metaStyle = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: '2px 14px',
  fontSize: '12px',
  color: 'var(--mut)',
  marginTop: '4px'
}

const valueStrongStyle = {
  fontWeight: 600,
  color: 'var(--ink)',
  wordBreak: 'break-all'
}

const controlStyle = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: '8px',
  marginLeft: 'auto',
  minWidth: 0
}

const switchStyle = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: '8px',
  minHeight: '34px',
  fontSize: '13px',
  color: 'var(--ink)',
  cursor: 'pointer'
}

const itemErrorStyle = {
  ...modalErrorStyle,
  marginTop: '8px'
}

const aiFooterStyle = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: '10px 12px',
  borderTop: '1px solid var(--line)',
  marginTop: '6px',
  paddingTop: '12px'
}

const aiHintStyle = {
  fontSize: '12px',
  color: 'var(--mut)'
}

const resultOkStyle = {
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: '2px 14px',
  fontSize: '12.5px',
  color: 'var(--ok)',
  background: 'var(--okSoft)',
  border: '1px solid var(--ok)',
  borderRadius: '8px',
  padding: '6px 10px',
  lineHeight: 1.6,
  wordBreak: 'break-all'
}

const resultFailStyle = {
  ...resultOkStyle,
  color: 'var(--acc)',
  background: 'var(--accSoft)',
  border: '1px solid var(--acc)'
}

const toastStyle = {
  position: 'fixed',
  right: '20px',
  bottom: '20px',
  zIndex: 900,
  maxWidth: 'min(360px, calc(100vw - 40px))',
  fontSize: '13px',
  color: 'var(--ok)',
  background: 'var(--card)',
  border: '1px solid var(--ok)',
  borderRadius: '8px',
  padding: '8px 14px',
  boxShadow: '0 6px 18px rgba(0, 0, 0, 0.18)'
}
</script>
