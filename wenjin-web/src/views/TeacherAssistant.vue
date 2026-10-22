<template>
  <div class="assistant-page">
    <aside class="side" aria-label="会话列表">
      <button type="button" class="btn primary" data-testid="new-conv" :disabled="!courseId" @click="newConversation">
        新建对话
      </button>
      <ul class="conv-list">
        <li v-for="c in conversations" :key="c.id" :class="{ active: c.id === conversationId }">
          <button type="button" class="conv-pick" data-testid="conv-item" @click="selectConversation(c.id)">
            {{ c.title || `对话 #${c.id}` }}
          </button>
          <button type="button" class="conv-del" data-testid="conv-del" :aria-label="`删除对话 ${c.title || c.id}`" @click="removeConversation(c.id)">×</button>
        </li>
      </ul>
    </aside>

    <section class="main">
      <header class="toolbar">
        <span class="title">AI 助教</span>
        <span v-if="courseId" class="meta">课程 #{{ courseId }}</span>
      </header>

      <div v-if="!courseId" class="empty">请先在「图谱审核」选择课程</div>
      <div v-else-if="flagOff" class="empty" role="alert" data-testid="flag-off">功能未开启</div>
      <div v-else class="chat">
        <div ref="listEl" class="messages" aria-live="polite">
          <div v-if="!messages.length" class="starters">
            <p class="muted">问我关于本班学情的问题：</p>
            <button v-for="q in STARTERS" :key="q" type="button" class="starter" data-testid="starter" @click="send(q)">{{ q }}</button>
          </div>
          <template v-for="(m, i) in messages" :key="i">
            <div v-if="m.role === 'user'" class="bubble user">{{ m.content }}</div>
            <div v-else-if="m.role === 'assistant'" class="bubble ai">{{ m.content }}</div>
            <div v-else-if="m.role === 'tool'" class="capsule" data-testid="tool-capsule">
              <span v-if="m.ok == null">⏳</span><span v-else-if="m.ok">✓</span><span v-else>✗</span>
              {{ m.label }}
            </div>
            <InsightTable v-else-if="m.role === 'insight'" :card="m.card" />
          </template>
        </div>
        <div v-if="error" class="err" role="alert" data-testid="error">{{ error }}</div>
        <form class="composer" @submit.prevent="send(input)">
          <textarea v-model="input" rows="2" placeholder="输入问题，回车发送" aria-label="问题" @keydown.enter.exact.prevent="send(input)"></textarea>
          <button type="submit" class="btn primary" data-testid="send" :disabled="streaming || !input.trim()">发送</button>
        </form>
      </div>
    </section>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import InsightTable from '../components/InsightTable.vue'
import { useTeacherCourse } from '../composables/useTeacherCourse.js'
import { isAbortError } from '../api/companion.js'
import {
  assistantChatStream,
  deleteAssistantConversation,
  fetchAssistantMessages,
  listAssistantConversations
} from '../api/teacherAssistant.js'
import { appendAssistantToken, applyToolResult, createToolMessage } from '../utils/companionMessages.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const STARTERS = ['这周哪些学生需要关注？', '哪个知识点全班最薄弱？', '错误率最高的题目是哪些？']

const { courseId } = useTeacherCourse()

const conversations = ref([])
const conversationId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const flagOff = ref(false)
const error = ref('')
const listEl = ref(null)

const streamReq = createLatestRequest()
const loadReq = createLatestRequest()
let disposed = false

function isNotFound(err) {
  return err?.httpStatus === 404 || err?.code === 404
}

function errMessage(err) {
  return err?.response?.data?.message || err?.message || '请求失败'
}

function scrollToEnd() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

function resetConversation() {
  streamReq.cancel()
  streaming.value = false
  conversationId.value = null
  messages.value = []
  error.value = ''
}

async function loadConversations() {
  const req = loadReq.next()
  try {
    const list = await listAssistantConversations(courseId.value, { signal: req.signal })
    if (!req.isCurrent() || disposed) return
    conversations.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (isAbortError(e) || !req.isCurrent() || disposed) return
    if (isNotFound(e)) flagOff.value = true
    else error.value = errMessage(e)
    conversations.value = []
  }
}

function newConversation() {
  resetConversation()
}

async function selectConversation(id) {
  resetConversation()
  conversationId.value = id
  const req = loadReq.next()
  try {
    const list = await fetchAssistantMessages(id, { signal: req.signal })
    if (!req.isCurrent() || disposed) return
    messages.value = (Array.isArray(list) ? list : []).map((m) => ({
      role: m.role === 'ai' ? 'assistant' : 'user',
      content: m.content || ''
    }))
    scrollToEnd()
  } catch (e) {
    if (isAbortError(e) || !req.isCurrent() || disposed) return
    error.value = errMessage(e)
  }
}

async function removeConversation(id) {
  try {
    await deleteAssistantConversation(id)
  } catch (e) {
    error.value = errMessage(e)
    return
  }
  if (disposed) return
  conversations.value = conversations.value.filter((c) => c.id !== id)
  if (conversationId.value === id) resetConversation()
}

async function send(text) {
  const message = (text || '').trim()
  if (!message || streaming.value || !courseId.value) return
  input.value = ''
  error.value = ''
  messages.value = [...messages.value, { role: 'user', content: message }]
  streaming.value = true
  scrollToEnd()
  const req = streamReq.next()
  const live = () => req.isCurrent() && !disposed
  const apply = (fn) => {
    if (!live()) return
    messages.value = fn(messages.value)
    scrollToEnd()
  }
  let newId = null
  try {
    await assistantChatStream(
      { courseId: courseId.value, conversationId: conversationId.value || undefined, message },
      {
        onMeta: (e) => {
          if (live() && e?.conversationId != null) {
            newId = e.conversationId
            conversationId.value = e.conversationId
          }
        },
        onToolStart: (e) => apply((m) => [...m, createToolMessage(e)]),
        onToolResult: (e) => apply((m) => applyToolResult(m, e)),
        onInsightCard: (e) => apply((m) => [...m, { role: 'insight', card: e }]),
        onToken: (t) => apply((m) => appendAssistantToken(m, t)),
        onError: (msg) => {
          if (live()) error.value = msg
        }
      },
      { signal: req.signal }
    )
  } catch (e) {
    if (isAbortError(e) || !live()) return
    if (isNotFound(e)) flagOff.value = true
    else if (!error.value) error.value = errMessage(e)
  } finally {
    if (live()) {
      streaming.value = false
      if (newId != null) void loadConversations()
    }
  }
}

watch(courseId, (id) => {
  resetConversation()
  conversations.value = []
  flagOff.value = false
  if (id) void loadConversations()
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  streamReq.cancel()
  loadReq.cancel()
})
</script>

<style scoped>
.assistant-page { display: flex; flex: 1; min-height: 0; background: var(--bg); color: var(--text); }
.side { width: 220px; flex: none; border-right: 1px solid var(--line); padding: 12px; display: flex; flex-direction: column; gap: 10px; overflow: auto; }
.conv-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.conv-list li { display: flex; align-items: center; border-radius: 6px; }
.conv-list li.active { background: var(--line); }
.conv-pick { flex: 1; min-width: 0; text-align: left; background: none; border: none; color: inherit; padding: 6px 8px; font-size: 13px; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-del { background: none; border: none; color: var(--mut); cursor: pointer; padding: 4px 8px; }
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.toolbar { display: flex; align-items: baseline; gap: 12px; padding: 12px 20px; border-bottom: 1px solid var(--line); }
.title { font-weight: 600; font-size: 15px; }
.meta, .muted { font-size: 12px; color: var(--mut); }
.empty { padding: 40px 20px; text-align: center; color: var(--mut); font-size: 13px; }
.chat { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.messages { flex: 1; min-height: 0; overflow: auto; padding: 16px 20px; display: flex; flex-direction: column; gap: 10px; }
.starters { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.starter { font-size: 13px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 16px; background: transparent; color: var(--text); cursor: pointer; }
.bubble { max-width: 80%; padding: 8px 12px; border-radius: 10px; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.bubble.user { align-self: flex-end; background: var(--acc); color: #fffdf8; }
.bubble.ai { align-self: flex-start; border: 1px solid var(--line); }
.capsule { align-self: flex-start; font-size: 12px; color: var(--mut); border: 1px solid var(--line); border-radius: 12px; padding: 2px 10px; }
.err { padding: 8px 20px; color: #c44; font-size: 13px; }
.composer { display: flex; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--line); }
.composer textarea { flex: 1; resize: none; padding: 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: var(--text); font: inherit; }
.btn { font-size: 12px; padding: 6px 12px; border-radius: 6px; border: 1px solid var(--line); background: transparent; color: var(--text); cursor: pointer; }
.btn.primary { background: var(--acc); color: #fffdf8; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
@media (max-width: 720px) { .side { width: 140px; } }
</style>
