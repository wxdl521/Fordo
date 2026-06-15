<template>
  <div class="admin-page">
    <h1 class="page-title">管理操作台</h1>

    <!-- ── Block A: AI 出题 ── -->
    <section class="block">
      <h2 class="block-title">A · AI 出题（generate）</h2>
      <div class="row">
        <label class="field-label">知识点编码</label>
        <input v-model="genNodeCode" class="field-input" placeholder="e.g. KT07" />
      </div>
      <div class="row">
        <label class="field-label">出题数量</label>
        <input v-model.number="genCount" type="number" min="1" max="20" class="field-input field-input--short" />
      </div>
      <button
        class="btn btn--primary"
        :disabled="genLoading"
        @click="handleGenerate"
      >{{ genLoading ? '生成中…' : '生成' }}</button>
      <p v-if="genError" class="msg msg--error">{{ genError }}</p>
      <div v-if="genResult" class="result-box">
        <p class="summary">
          生成 <strong>{{ genResult.generated }}</strong> 题 ·
          去重丢弃 <strong>{{ genResult.dropped }}</strong> ·
          重复跳过 <strong>{{ genResult.duplicated }}</strong>
        </p>
        <p v-if="genResult.message" class="msg msg--info">{{ genResult.message }}</p>
        <details v-if="genResult.questionIds && genResult.questionIds.length">
          <summary class="details-toggle">题目 ID 列表（{{ genResult.questionIds.length }} 条）</summary>
          <pre class="pre-json">{{ JSON.stringify(genResult.questionIds, null, 2) }}</pre>
        </details>
      </div>
    </section>

    <!-- ── Block B: 标注 ── -->
    <section class="block">
      <h2 class="block-title">B · 标注知识点（annotate）</h2>
      <label class="field-label">AnnotateRequest JSON</label>
      <textarea v-model="annotateJson" class="field-textarea" rows="12" spellcheck="false" />
      <button
        class="btn btn--primary"
        :disabled="annotateLoading"
        @click="handleAnnotate"
      >{{ annotateLoading ? '标注中…' : '标注' }}</button>
      <p v-if="annotateError" class="msg msg--error">{{ annotateError }}</p>
      <div v-if="annotateResult" class="result-box">
        <p class="summary">共标注 <strong>{{ annotateResult.length }}</strong> 题</p>
        <div
          v-for="(item, idx) in annotateResult"
          :key="idx"
          class="annotate-item"
        >
          <p class="annotate-stem">{{ idx + 1 }}. {{ item.stem }}</p>
          <template v-if="item.mainPoint">
            <p class="annotate-field">
              主知识点：<strong>{{ item.mainPoint }}</strong>
            </p>
            <p v-if="item.subPoints && item.subPoints.length" class="annotate-field">
              子知识点：{{ item.subPoints.join('、') }}
            </p>
          </template>
          <p v-else class="annotate-field msg--warn">
            超纲未强制标注 — {{ item.reason || '原因未知' }}
          </p>
          <p class="annotate-field">
            已持久化：<span :class="item.persisted ? 'tag--yes' : 'tag--no'">{{ item.persisted ? '是' : '否' }}</span>
          </p>
        </div>
        <details class="raw-detail">
          <summary class="details-toggle">原始 JSON</summary>
          <pre class="pre-json">{{ JSON.stringify(annotateResult, null, 2) }}</pre>
        </details>
      </div>
    </section>

    <!-- ── Block C: 导入题库 ── -->
    <section class="block">
      <h2 class="block-title">C · 导入题库（import-bank）</h2>
      <p class="field-label">课程编码：<code class="code-inline">52015CC4B4</code>（固定）</p>
      <button
        class="btn btn--primary"
        :disabled="importLoading"
        @click="handleImport"
      >{{ importLoading ? '导入中…' : '导入题库' }}</button>
      <p v-if="importError" class="msg msg--error">{{ importError }}</p>
      <div v-if="importResult" class="result-box">
        <p class="summary">
          导入 <strong>{{ importResult.imported }}</strong> 题 ·
          跳过 <strong>{{ importResult.skipped }}</strong> 题
        </p>
        <pre class="pre-json">{{ JSON.stringify(importResult, null, 2) }}</pre>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { generateQuestions, annotateQuestions, importBank } from '../api/admin.js'

// ── Block A state ──
const genNodeCode = ref('KT07')
const genCount = ref(5)
const genLoading = ref(false)
const genError = ref('')
const genResult = ref(null)

async function handleGenerate() {
  if (!genNodeCode.value.trim()) {
    genError.value = '请填写知识点编码'
    return
  }
  genLoading.value = true
  genError.value = ''
  genResult.value = null
  try {
    genResult.value = await generateQuestions(genNodeCode.value.trim(), genCount.value)
  } catch (e) {
    genError.value = e.message || '出题失败'
  } finally {
    genLoading.value = false
  }
}

// ── Block B state ──
const defaultAnnotateJson = JSON.stringify(
  {
    items: [
      {
        stem: '以下关于栈（Stack）的说法，正确的是？',
        options: [
          { key: 'A', text: '栈是先进先出的数据结构', correct: false },
          { key: 'B', text: '栈是后进先出的数据结构', correct: true },
          { key: 'C', text: '栈只能用数组实现', correct: false }
        ]
      }
    ]
  },
  null,
  2
)

const annotateJson = ref(defaultAnnotateJson)
const annotateLoading = ref(false)
const annotateError = ref('')
const annotateResult = ref(null)

async function handleAnnotate() {
  annotateLoading.value = true
  annotateError.value = ''

  let parsed
  try {
    parsed = JSON.parse(annotateJson.value)
  } catch (_) {
    annotateError.value = 'JSON 格式有误，请检查输入'
    annotateLoading.value = false
    return   // 解析失败不清空上一条结果
  }

  annotateResult.value = null   // 仅在真正发起请求前清空
  try {
    annotateResult.value = await annotateQuestions(parsed)
  } catch (e) {
    annotateError.value = e.message || '标注失败'
  } finally {
    annotateLoading.value = false
  }
}

// ── Block C state ──
const IMPORT_COURSE = '52015CC4B4'
const importLoading = ref(false)
const importError = ref('')
const importResult = ref(null)

async function handleImport() {
  importLoading.value = true
  importError.value = ''
  importResult.value = null
  try {
    importResult.value = await importBank(IMPORT_COURSE)
  } catch (e) {
    importError.value = e.message || '导入失败'
  } finally {
    importLoading.value = false
  }
}
</script>

<style scoped>
.admin-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 28px 20px 60px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text);
  margin: 0 0 24px;
}

/* ── section block ── */
.block {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 20px 22px;
  margin-bottom: 20px;
}

.block-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  margin: 0 0 16px;
}

/* ── form fields ── */
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.field-label {
  display: block;
  font-size: 13px;
  color: var(--text-mut);
  margin-bottom: 6px;
  min-width: 90px;
  flex-shrink: 0;
}

.field-input {
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 14px;
  color: var(--text);
  outline: none;
  width: 240px;
}

.field-input--short {
  width: 80px;
}

.field-input:focus {
  border-color: var(--accent);
}

.field-textarea {
  display: block;
  width: 100%;
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 13px;
  font-family: 'Consolas', 'Fira Mono', monospace;
  color: var(--text);
  outline: none;
  resize: vertical;
  margin-bottom: 12px;
}

.field-textarea:focus {
  border-color: var(--accent);
}

/* ── button ── */
.btn {
  padding: 7px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
  margin-top: 4px;
}

.btn--primary {
  background: var(--accent);
  color: #fff;
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ── messages ── */
.msg {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.5;
}

.msg--error {
  color: #f07070;
}

.msg--info {
  color: var(--text-mut);
}

.msg--warn {
  color: var(--weak);
}

/* ── result box ── */
.result-box {
  margin-top: 14px;
  padding: 14px 16px;
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 8px;
}

.summary {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--text);
}

.pre-json {
  margin: 8px 0 0;
  padding: 10px;
  background: #1a1d24;
  border-radius: 6px;
  font-family: 'Consolas', 'Fira Mono', monospace;
  font-size: 12px;
  color: var(--text-mut);
  overflow-x: auto;
  white-space: pre;
}

.details-toggle {
  font-size: 12px;
  color: var(--text-mut);
  cursor: pointer;
  user-select: none;
}

.raw-detail {
  margin-top: 10px;
}

/* ── annotate items ── */
.annotate-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.annotate-item:last-of-type {
  border-bottom: none;
}

.annotate-stem {
  font-size: 13px;
  color: var(--text);
  margin: 0 0 4px;
  font-weight: 500;
}

.annotate-field {
  font-size: 12px;
  color: var(--text-mut);
  margin: 2px 0;
}

.tag--yes {
  color: var(--mastered);
  font-weight: 600;
}

.tag--no {
  color: var(--weak);
  font-weight: 600;
}

.code-inline {
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 1px 6px;
  font-family: 'Consolas', 'Fira Mono', monospace;
  font-size: 13px;
  color: var(--text);
}
</style>
