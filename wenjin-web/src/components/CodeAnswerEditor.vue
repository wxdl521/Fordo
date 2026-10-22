<template>
  <div class="cae">
    <textarea
      ref="el"
      class="cae-textarea"
      :value="modelValue"
      :disabled="disabled"
      :aria-label="ariaLabelledby ? undefined : ariaLabel"
      :aria-labelledby="ariaLabelledby || undefined"
      aria-describedby="cae-hint"
      spellcheck="false"
      autocapitalize="off"
      autocomplete="off"
      wrap="off"
      rows="14"
      @input="$emit('update:modelValue', $event.target.value)"
      @keydown="onKeydown"
    ></textarea>
    <div id="cae-hint" class="cae-hint">Tab 插入缩进；按 Esc 后再按 Tab 可离开编辑框。</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { insertAtSelection, newlineWithIndent } from '../utils/codeResult.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  ariaLabel: { type: String, default: '代码编辑区' },
  ariaLabelledby: { type: String, default: '' },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const el = ref(null)
let escaped = false
const INDENT = '    '

function apply(textarea, result) {
  emit('update:modelValue', result.value)
  // keep the DOM in sync immediately so the caret can be restored
  textarea.value = result.value
  textarea.setSelectionRange(result.caret, result.caret)
}

function onKeydown(e) {
  if (e.key === 'Escape') {
    escaped = true
    return
  }
  if (e.key === 'Tab') {
    if (escaped || e.shiftKey) {
      escaped = false
      return // let focus move away (keyboard-trap escape)
    }
    e.preventDefault()
    const t = e.target
    apply(t, insertAtSelection(props.modelValue, t.selectionStart, t.selectionEnd, INDENT))
    return
  }
  escaped = false
  if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey && !e.isComposing) {
    e.preventDefault()
    const t = e.target
    apply(t, newlineWithIndent(props.modelValue, t.selectionStart, t.selectionEnd, INDENT))
  }
}
</script>

<style scoped>
.cae-textarea {
  width: 100%;
  box-sizing: border-box;
  min-height: 240px;
  padding: 12px 14px;
  background: var(--card);
  border: 1.5px solid var(--line);
  border-radius: 10px;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 13.5px;
  line-height: 1.55;
  color: var(--ink);
  tab-size: 4;
  resize: vertical;
  white-space: pre;
  overflow: auto;
  outline: none;
}
.cae-textarea:focus { border-color: var(--acc); }
.cae-textarea:focus-visible { box-shadow: 0 0 0 3px var(--accSoft); }
.cae-hint { margin-top: 4px; font-size: 12px; color: var(--mut); }
</style>
