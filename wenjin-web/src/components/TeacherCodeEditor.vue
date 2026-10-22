<template>
  <div class="tce" data-testid="teacher-code">
    <template v-if="!editing">
      <div class="tce-cap">起始代码</div>
      <pre class="tce-pre" data-testid="tce-starter">{{ question.starterCode || '（无）' }}</pre>
      <div class="tce-cap">用例（教师可见全部）</div>
      <ul class="tce-list">
        <li v-for="c in cases" :key="c.seq" class="tce-case" data-testid="tce-case">
          <span class="tce-seq">#{{ c.seq }}</span>
          <span class="tce-flag" :class="{ 'is-hidden': c.hidden }">{{ c.hidden ? '隐藏' : '公开' }}</span>
          <pre class="tce-pre">输入：{{ c.stdin }}</pre>
          <pre class="tce-pre">期望：{{ c.expectedStdout }}</pre>
        </li>
      </ul>
      <button type="button" class="tce-btn" data-testid="tce-edit" @click="startEdit">编辑编程题</button>
    </template>
    <form v-else class="tce-form" @submit.prevent="save">
      <div class="tce-cap">起始代码</div>
      <CodeAnswerEditor
        v-model="draftStarter"
        :aria-label="`题目 ${question.id} 起始代码`"
      />
      <div class="tce-cap" role="heading" aria-level="4">测试用例</div>
      <div v-for="(c, i) in draft" :key="i" class="tce-edit-case" data-testid="tce-edit-case">
        <div class="tce-edit-head">
          <span class="tce-seq">用例 {{ i + 1 }}</span>
          <label class="tce-check">
            <input v-model="c.hidden" type="checkbox" :aria-label="`用例 ${i + 1} 隐藏`" data-testid="tce-hidden" />
            隐藏
          </label>
          <button type="button" class="tce-btn" :aria-label="`删除用例 ${i + 1}`" data-testid="tce-remove" @click="remove(i)">删除</button>
        </div>
        <textarea v-model="c.stdin" class="tce-ta" rows="2" :aria-label="`用例 ${i + 1} 输入`" spellcheck="false"></textarea>
        <textarea v-model="c.expectedStdout" class="tce-ta" rows="2" :aria-label="`用例 ${i + 1} 期望输出`" spellcheck="false"></textarea>
      </div>
      <div class="tce-actions">
        <button type="button" class="tce-btn" data-testid="tce-add" @click="add">添加用例</button>
        <button type="submit" class="tce-btn tce-primary" data-testid="tce-save" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        <button type="button" class="tce-btn" :disabled="saving" @click="editing = false">取消</button>
      </div>
      <div v-if="error" class="tce-error" role="alert">{{ error }}</div>
    </form>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import CodeAnswerEditor from './CodeAnswerEditor.vue'

const props = defineProps({
  /** { id, starterCode, cases:[{seq, stdin, expectedStdout, hidden}] } */
  question: { type: Object, required: true },
  /** async (payload:{starterCode, cases}) => void; throws on failure */
  saveFn: { type: Function, default: null }
})
const emit = defineEmits(['saved'])

const editing = ref(false)
const saving = ref(false)
const error = ref('')
const draftStarter = ref('')
const draft = ref([])

const cases = computed(() => props.question.cases || [])

function startEdit() {
  draftStarter.value = props.question.starterCode || ''
  draft.value = cases.value.map((c) => ({
    stdin: c.stdin ?? '',
    expectedStdout: c.expectedStdout ?? '',
    hidden: !!c.hidden
  }))
  error.value = ''
  editing.value = true
}
function add() {
  draft.value.push({ stdin: '', expectedStdout: '', hidden: false })
}
function remove(i) {
  draft.value.splice(i, 1)
}

/** Payload shape sent to the backend (seq renumbered 1..n). */
function buildPayload() {
  return {
    starterCode: draftStarter.value,
    cases: draft.value.map((c, i) => ({
      seq: i + 1,
      stdin: c.stdin,
      expectedStdout: c.expectedStdout,
      hidden: !!c.hidden
    }))
  }
}

async function save() {
  if (!draft.value.length) {
    error.value = '至少需要一个测试用例'
    return
  }
  const payload = buildPayload()
  saving.value = true
  error.value = ''
  try {
    if (props.saveFn) await props.saveFn(payload)
    editing.value = false
    emit('saved', payload)
  } catch (e) {
    error.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.tce { margin: 8px 0; font-size: 13px; color: var(--text, var(--ink)); }
.tce-cap { font-size: 12px; color: var(--text-mut, var(--mut)); margin: 6px 0 2px; display: block; }
.tce-pre { margin: 2px 0; padding: 6px 8px; background: var(--card2, rgba(0,0,0,.05)); border-radius: 6px; overflow: auto; font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; white-space: pre-wrap; word-break: break-word; }
.tce-list { list-style: none; margin: 0 0 6px; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.tce-case { border: 1px solid var(--line, #ddd); border-radius: 8px; padding: 6px 8px; }
.tce-seq { font-weight: 600; margin-right: 8px; }
.tce-flag { font-size: 12px; }
.tce-flag.is-hidden { font-weight: 600; }
.tce-edit-case { border: 1px solid var(--line, #ddd); border-radius: 8px; padding: 8px; margin-bottom: 8px; display: flex; flex-direction: column; gap: 6px; }
.tce-edit-head { display: flex; align-items: center; gap: 12px; }
.tce-check { display: inline-flex; align-items: center; gap: 4px; }
.tce-ta { width: 100%; box-sizing: border-box; font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; padding: 6px 8px; border: 1px solid var(--line, #ddd); border-radius: 6px; background: transparent; color: inherit; }
.tce-actions { display: flex; gap: 8px; }
.tce-btn { padding: 4px 12px; border: 1px solid var(--line, #ccc); border-radius: 6px; background: transparent; color: inherit; cursor: pointer; font: inherit; }
.tce-primary { border-color: var(--acc, #444); font-weight: 600; }
.tce-error { color: #9b2c2c; margin-top: 6px; }
</style>
