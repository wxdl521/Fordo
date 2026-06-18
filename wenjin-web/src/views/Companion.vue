<template>
  <div class="wj-companion">
    <!-- 侧栏：会话列表 -->
    <aside class="cp-sidebar">
      <div class="cp-sidebar-head">
        <h3 class="cp-sidebar-title">学习伴侣</h3>
        <button class="cp-btn-new" @click="newChat" title="新对话">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
      <div class="cp-conversations">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="cp-conv-item"
          :class="{ active: conv.id === conversationId }"
          @click="openConversation(conv.id)"
        >
          <div class="cp-conv-title">{{ conv.title || '新对话' }}</div>
          <div class="cp-conv-meta">{{ formatTime(conv.createdAt) }}</div>
          <button class="cp-conv-del" @click.stop="handleDelete(conv.id)" title="删除对话">×</button>
        </div>
        <div v-if="!conversations.length" class="cp-conv-empty">暂无对话</div>
      </div>
    </aside>

    <!-- 主区域：消息列表 + 输入框 -->
    <main class="cp-main">
      <!-- 空状态 / 消息列表 -->
      <div v-if="!msgs.length" class="cp-empty">
        <h2 class="cp-empty-title">你好，我是你的学习伴侣</h2>
        <p class="cp-empty-sub">我可以帮你解答知识点问题、分析学习进度、制定学习计划。</p>
        <div class="cp-starters">
          <button
            v-for="s in starters"
            :key="s"
            class="cp-starter"
            @click="input = s; sendText()"
          >
            {{ s }}
          </button>
        </div>
      </div>

      <div v-else class="cp-messages" ref="scrollEl">
        <div
          v-for="(msg, i) in msgs"
          :key="i"
          class="cp-msg"
          :class="{ user: msg.role === 'user', ai: msg.role === 'assistant' }"
        >
          <div class="cp-msg-role">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
          <div class="cp-msg-body">{{ msg.content }}</div>
        </div>
        <div v-if="typing" class="cp-typing">
          <span class="cp-typing-dot"></span>
          <span class="cp-typing-dot"></span>
          <span class="cp-typing-dot"></span>
        </div>
      </div>

      <!-- 上下文 chip -->
      <div v-if="ctxNodeCode" class="cp-context-chip">
        <span class="cp-chip-label">上下文：</span>
        <span class="cp-chip-code">{{ ctxNodeCode }}</span>
        <button class="cp-chip-close" @click="ctxNodeCode = null" title="清除上下文">×</button>
      </div>

      <!-- 输入区 -->
      <div class="cp-input-area">
        <textarea
          v-model="input"
          class="cp-input"
          placeholder="输入你的问题…"
          rows="1"
          @keydown="onKey"
        ></textarea>
        <button
          class="cp-btn-send"
          :disabled="!hasText || typing"
          @click="sendText"
        >
          <svg v-if="!typing" width="18" height="18" viewBox="0 0 18 18" fill="none">
            <path d="M16 2L8 10M16 2l-6 14-2-6-6-2 14-6z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span v-else class="cp-sending">…</span>
        </button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { listConversations, fetchConversation, deleteConversation, chatStream } from '../api/companion.js'

const route = useRoute()

// 从 localStorage 读取当前登录用户
function readUser() {
  try { return JSON.parse(localStorage.getItem('wj_user')) } catch { return null }
}
const currentUser = readUser()
const DEMO_STUDENT_ID = currentUser?.id || 2
const DEMO_COURSE_ID = (() => {
  const q = Number(route.query.courseId)
  return q > 0 ? q : 1
})()

const router = useRouter()

// 状态
const msgs = ref([])
const input = ref('')
const typing = ref(false)
const conversationId = ref(null)
const conversations = ref([])
const ctxNodeCode = ref(null)
const scrollEl = ref(null)

// 启动器示例
const starters = computed(() => [
  '我在「数组」上卡住了，怎么办？',
  '我的学习进度如何？',
  '帮我制定下一步的学习计划'
])

const hasText = computed(() => input.value.trim().length > 0)

// 找到最后一条 AI 消息的索引，用于流式追加
const lastAiIdx = computed(() => {
  for (let i = msgs.value.length - 1; i >= 0; i--) {
    if (msgs.value[i].role === 'assistant') return i
  }
  return -1
})

// 加载会话列表
async function loadConversations() {
  try {
    const data = await listConversations(DEMO_STUDENT_ID, DEMO_COURSE_ID)
    conversations.value = data || []
  } catch (e) {
    console.error('Failed to load conversations:', e)
  }
}

// 打开已有会话
async function openConversation(id) {
  conversationId.value = id
  try {
    const conv = await fetchConversation(id)
    msgs.value = Array.isArray(conv) ? conv : (conv.messages || [])
    await nextTick()
    scrollToEnd()
  } catch (e) {
    console.error('Failed to fetch conversation:', e)
    msgs.value = []
  }
}

// 新对话
function newChat() {
  conversationId.value = null
  msgs.value = []
  input.value = ''
  ctxNodeCode.value = null
}

// 删除会话
async function handleDelete(id) {
  try {
    await deleteConversation(id)
    // 如果删除的是当前会话，清空聊天
    if (conversationId.value === id) {
      conversationId.value = null
      msgs.value = []
    }
    await loadConversations()
  } catch (e) {
    console.error('删除会话失败:', e)
  }
}

// 发送消息
async function sendText() {
  const text = input.value.trim()
  if (!text || typing.value) return

  // 添加用户消息
  msgs.value.push({ role: 'user', content: text })
  input.value = ''

  // 添加空 AI 消息占位
  msgs.value.push({ role: 'assistant', content: '' })

  typing.value = true
  await nextTick()
  scrollToEnd()

  const payload = {
    studentId: DEMO_STUDENT_ID,
    courseId: DEMO_COURSE_ID,
    message: text
  }

  if (conversationId.value) {
    payload.conversationId = conversationId.value
  }

  if (ctxNodeCode.value) {
    payload.contextNodeCode = ctxNodeCode.value
  }

  try {
    await chatStream(payload, {
      onMeta: (meta) => {
        // 第一次流式响应时，设置 conversationId
        if (meta.conversationId && !conversationId.value) {
          conversationId.value = meta.conversationId
          loadConversations() // 刷新会话列表
        }
      },
      onToken: (token) => {
        // 追加 token 到最后一条 AI 消息
        const idx = lastAiIdx.value
        if (idx >= 0) {
          msgs.value[idx].content += token
        }
        scrollToEnd()
      },
      onDone: () => {
        typing.value = false
        scrollToEnd()
      },
      onError: (errMsg) => {
        typing.value = false
        const idx = lastAiIdx.value
        if (idx >= 0) {
          msgs.value[idx].content = `[错误] ${errMsg}`
        }
      }
    })
  } catch (e) {
    typing.value = false
    const idx = lastAiIdx.value
    if (idx >= 0) {
      msgs.value[idx].content = `[网络错误] ${e.message}`
    }
  }
}

// 键盘事件
function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendText()
  }
}

// 滚动到底部
function scrollToEnd() {
  if (!scrollEl.value) return
  nextTick(() => {
    scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
}

// 格式化时间
function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diff = now - d
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 7) return `${days} 天前`
  return d.toLocaleDateString('zh-CN')
}

onMounted(async () => {
  await loadConversations()

  // DEV 钩子
  if (import.meta.env.DEV) {
    window.__wjCompanion = {
      msgs,
      input,
      typing,
      conversationId,
      conversations,
      ctxNodeCode,
      scrollEl,
      loadConversations,
      openConversation,
      newChat,
      sendText
    }
  }
})
</script>

<style scoped>
.wj-companion {
  display: flex;
  flex: 1;
  min-height: 0;
  background: var(--bg);
  color: var(--ink);
  overflow: hidden;
}

/* ========== 侧栏 ========== */
.cp-sidebar {
  width: 260px;
  flex: none;
  background: var(--card);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.cp-sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px;
  border-bottom: 1px solid var(--line);
  flex: none;
}

.cp-sidebar-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}

.cp-btn-new {
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: transparent;
  color: var(--ink);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.cp-btn-new:hover {
  background: var(--hover);
}

.cp-conversations {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.cp-conv-item {
  position: relative;
  padding: 12px 32px 12px 14px;
  border-radius: 9px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.15s;
}

.cp-conv-item:hover {
  background: var(--hover);
}

.cp-conv-del {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--mut);
  font-size: 15px;
  cursor: pointer;
  display: none;
  align-items: center;
  justify-content: center;
  line-height: 1;
}

.cp-conv-item:hover .cp-conv-del {
  display: flex;
}

.cp-conv-del:hover {
  background: var(--card2);
  color: var(--acc);
}

.cp-conv-item.active {
  background: var(--card2);
  border: 1px solid var(--line);
}

.cp-conv-title {
  font-size: 13.5px;
  font-weight: 500;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-conv-meta {
  font-size: 11.5px;
  color: var(--mut);
}

.cp-conv-empty {
  text-align: center;
  color: var(--mut);
  font-size: 13px;
  padding: 40px 16px;
}

/* ========== 主区域 ========== */
.cp-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

/* 空状态 */
.cp-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  text-align: center;
}

.cp-empty-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 26px;
  font-weight: 600;
  margin: 0 0 12px;
}

.cp-empty-sub {
  font-size: 14px;
  color: var(--mut);
  line-height: 1.7;
  max-width: 480px;
  margin: 0 0 32px;
}

.cp-starters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  max-width: 420px;
}

.cp-starter {
  padding: 14px 20px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 10px;
  font-size: 13.5px;
  color: var(--ink);
  cursor: pointer;
  text-align: left;
  transition: background 0.15s, border-color 0.15s;
  font-family: inherit;
}

.cp-starter:hover {
  background: var(--hover);
  border-color: var(--acc);
}

/* 消息列表 */
.cp-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px;
  scroll-behavior: smooth;
}

.cp-msg {
  display: flex;
  gap: 14px;
  margin-bottom: 24px;
}

.cp-msg-role {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  background: var(--card2);
  color: var(--mut);
}

.cp-msg.user .cp-msg-role {
  background: var(--acc);
  color: #fffdf8;
}

.cp-msg-body {
  flex: 1;
  font-size: 14px;
  line-height: 1.75;
  padding-top: 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 输入中提示 */
.cp-typing {
  display: flex;
  gap: 6px;
  padding-left: 52px;
  margin-top: -12px;
}

.cp-typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--mut);
  animation: typing-bounce 1.4s infinite ease-in-out;
}

.cp-typing-dot:nth-child(1) {
  animation-delay: -0.32s;
}

.cp-typing-dot:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes typing-bounce {
  0%, 80%, 100% {
    transform: translateY(0);
    opacity: 0.5;
  }
  40% {
    transform: translateY(-6px);
    opacity: 1;
  }
}

/* 上下文 chip */
.cp-context-chip {
  position: absolute;
  bottom: 100px;
  left: 24px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 8px;
  font-size: 12.5px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.cp-chip-label {
  color: var(--mut);
}

.cp-chip-code {
  font-weight: 500;
  color: var(--ink);
}

.cp-chip-close {
  width: 18px;
  height: 18px;
  border: none;
  background: transparent;
  color: var(--mut);
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: background 0.15s;
}

.cp-chip-close:hover {
  background: var(--hover);
}

/* 输入区 */
.cp-input-area {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--line);
  background: var(--card);
}

.cp-input {
  flex: 1;
  min-height: 42px;
  max-height: 120px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--bg);
  color: var(--ink);
  font-size: 14px;
  font-family: inherit;
  line-height: 1.5;
  resize: none;
  outline: none;
  transition: border-color 0.15s;
}

.cp-input:focus {
  border-color: var(--acc);
}

.cp-input::placeholder {
  color: var(--mut);
}

.cp-btn-send {
  width: 42px;
  height: 42px;
  flex: none;
  border: none;
  border-radius: 9px;
  background: var(--acc);
  color: #fffdf8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s;
}

.cp-btn-send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.cp-btn-send:not(:disabled):hover {
  opacity: 0.9;
}

.cp-sending {
  font-size: 18px;
  font-weight: 600;
}

/* 响应式 */
@media (max-width: 760px) {
  .cp-sidebar {
    width: 220px;
  }

  .cp-empty-title {
    font-size: 22px;
  }

  .cp-messages {
    padding: 16px;
  }
}
</style>
