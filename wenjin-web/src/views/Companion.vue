<template>
  <div class="wj-companion">
    <aside class="cp-sidebar">
      <div class="cp-sidebar-head">
        <h3 class="cp-sidebar-title">学习伴侣</h3>
        <button class="cp-btn-new" title="新对话" @click="newChat">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
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
          <button class="cp-conv-del" title="删除对话" @click.stop="handleDelete(conv.id)">×</button>
        </div>

        <div v-if="!conversations.length" class="cp-conv-empty">暂无对话</div>
      </div>
    </aside>

    <main class="cp-main">
      <div v-if="!msgs.length" class="cp-empty">
        <h2 class="cp-empty-title">你好，我是你的学习伴侣</h2>
        <p class="cp-empty-sub">可以随时问我知识点、学习进度，或者直接在这里完成节点练习。</p>
        <div class="cp-starters">
          <button
            v-for="starter in starters"
            :key="starter"
            class="cp-starter"
            @click="sendStarter(starter)"
          >
            {{ starter }}
          </button>
        </div>
      </div>

      <div v-else ref="scrollEl" class="cp-messages">
        <template v-for="(msg, index) in msgs" :key="index">
        <div
          v-if="shouldRenderMessage(msg)"
          class="cp-msg"
          :class="`is-${msg.role}`"
        >
          <div class="cp-msg-role">{{ roleLabel(msg.role) }}</div>

          <div class="cp-msg-main">
            <div v-if="isPlainMessage(msg) && msg.content" class="cp-msg-body">
              {{ msg.content }}
            </div>

            <div v-else-if="msg.role === 'tool'" class="cp-tool-capsule" :class="toolStateClass(msg)">
              <span class="cp-tool-dot"></span>
              <span class="cp-tool-label">{{ msg.label || msg.name }}</span>
              <span class="cp-tool-state">{{ toolStateText(msg) }}</span>
            </div>

            <div v-else-if="msg.role === 'practice'" class="cp-practice">
              <div class="cp-practice-head">
                <div class="cp-practice-title">{{ practiceTitle(msg) }}</div>
                <div class="cp-practice-meta">{{ practiceMeta(msg) }}</div>
              </div>

              <div v-if="practiceCurrentQuestion(msg) && msg.phase !== 'result'" class="cp-practice-body">
                <QuestionAnswerCard
                  :question="practiceCurrentQuestion(msg)"
                  :index="msg.currentIndex + 1"
                  :total="practiceQuestionCount(msg)"
                  :selected="practiceSelected(msg)"
                  :selected-set="practiceSelectedSet(msg)"
                  :text-value="practiceTextValue(msg)"
                  :is-last="practiceIsLast(msg)"
                  :last-label="practiceLastLabel(msg)"
                  :has-selection="practiceHasSelection(msg)"
                  :show-hint="false"
                  @pick="onPracticePick(msg, $event)"
                  @text-input="onPracticeTextInput(msg, $event)"
                  @prev="onPracticePrev(msg)"
                  @next="onPracticeNext(msg)"
                  @skip="onPracticeSkip(msg)"
                />

                <div v-if="msg.phase === 'submitting'" class="cp-practice-note">
                  正在提交本次作答…
                </div>
                <div v-else-if="msg.submitError" class="cp-practice-note is-error">
                  {{ msg.submitError }}
                </div>
              </div>

              <div v-else-if="msg.phase !== 'result'" class="cp-practice-empty">
                暂无可作答题目
              </div>

              <div v-if="msg.phase === 'result' && msg.submitResult" class="cp-practice-result">
                <div class="cp-practice-summary">
                  <div class="cp-practice-summary-main">
                    <span class="cp-summary-label">掌握度</span>
                    <strong class="cp-summary-value">
                      {{ formatPct(msg.submitResult.masteryBefore) }}
                      <span class="cp-summary-arrow">→</span>
                      {{ formatPct(msg.submitResult.masteryAfter) }}
                    </strong>
                    <span
                      v-if="practiceMasteryDelta(msg) !== null"
                      class="cp-summary-delta"
                      :class="deltaClass(practiceMasteryDelta(msg))"
                    >
                      {{ formatDelta(practiceMasteryDelta(msg)) }}
                    </span>
                  </div>
                  <div v-if="msg.submitResult.masteryLevel" class="cp-summary-badge">
                    {{ msg.submitResult.masteryLevel }}
                  </div>
                </div>

                <div class="cp-practice-lines">
                  <div class="cp-practice-line">{{ practiceStatusSummary(msg) }}</div>
                  <div
                    v-if="msg.submitResult.weakPrerequisites && msg.submitResult.weakPrerequisites.length"
                    class="cp-practice-line"
                  >
                    薄弱前置：{{ practiceWeakSummary(msg.submitResult.weakPrerequisites) }}
                  </div>
                </div>

                <div
                  v-if="msg.submitResult.graded && msg.submitResult.graded.length"
                  class="cp-grades"
                >
                  <div
                    v-for="(grade, gradeIndex) in msg.submitResult.graded"
                    :key="`${grade.questionId || gradeIndex}`"
                    class="cp-grade-item"
                    :class="grade.correct === true ? 'is-correct' : grade.correct === false ? 'is-wrong' : 'is-neutral'"
                  >
                    <div class="cp-grade-head">
                      <span class="cp-grade-title">{{ practiceGradeTitle(msg, grade, gradeIndex) }}</span>
                      <span class="cp-grade-state">{{ practiceGradeState(grade) }}</span>
                    </div>
                    <div v-if="grade.correctAnswer && grade.correct !== true" class="cp-grade-extra">
                      正确答案：{{ grade.correctAnswer }}
                    </div>
                    <div v-if="grade.analysis && grade.correct !== true" class="cp-grade-extra">
                      {{ grade.analysis }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        </template>

        <div v-if="typing" class="cp-typing">
          <span class="cp-typing-dot"></span>
          <span class="cp-typing-dot"></span>
          <span class="cp-typing-dot"></span>
        </div>
      </div>

      <div v-if="ctxNodeCode" class="cp-context-chip">
        <span class="cp-chip-label">上下文</span>
        <span class="cp-chip-code">{{ ctxNodeCode }}</span>
        <button class="cp-chip-close" title="清除上下文" @click="clearContextNodeCode">×</button>
      </div>

      <div class="cp-input-area">
        <textarea
          v-model="input"
          class="cp-input"
          placeholder="输入你的问题…"
          rows="1"
          @keydown="onKey"
        ></textarea>
        <button class="cp-btn-send" :disabled="!hasText || typing" @click="sendText">
          <svg v-if="!typing" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
            <path
              d="M16 2L8 10M16 2l-6 14-2-6-6-2 14-6z"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span v-else class="cp-sending">…</span>
        </button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QuestionAnswerCard from '../components/QuestionAnswerCard.vue'
import {
  listConversations,
  fetchConversation,
  deleteConversation,
  chatStream,
  isAbortError
} from '../api/companion.js'
import { submitPractice } from '../api/practice.js'
import { useStudentCourse } from '../composables/useStudentCourse.js'
import { buildCompanionChatPayload, readCompanionNodeCode } from '../utils/companionContext.js'
import { createDebouncedPersist } from '../utils/companionPersistence.js'
import { createLatestRequest } from '../utils/latestRequest.js'
import {
  appendAssistantToken,
  applyToolResult,
  buildPracticeAnswerList,
  conversationSnapshotKey,
  createPracticeMessage,
  createToolMessage,
  createTypedMessagesSnapshot,
  normalizeConversationMessage,
  restoreTypedMessagesFromSnapshot
} from '../utils/companionMessages.js'
import { useSessionStore } from '../store/session.js'

const route = useRoute()
const router = useRouter()
const { courseId } = useStudentCourse()
const session = useSessionStore()
/** 本地草稿键用；无身份时用 0（禁止 || 2 演示回落） */
const studentId = computed(() => session.userId)

const msgs = ref([])
const input = ref('')
const typing = ref(false)
const conversationId = ref(null)
const conversations = ref([])
const ctxNodeCode = ref(null)
const scrollEl = ref(null)
let snapshotPersist

/** 三闸：列表 / 详情 / SSE，各自 abort + isCurrent 防迟到写 */
const conversationListGate = createLatestRequest()
const conversationDetailGate = createLatestRequest()
const streamGate = createLatestRequest()

function cancelAllCompanionRequests() {
  conversationListGate.cancel()
  conversationDetailGate.cancel()
  streamGate.cancel()
}

/**
 * 课程切换 / 新对话 / 卸载统一清理顺序：
 * flush snapshot → cancel all → clear state（再由调用方 load list）
 */
function resetCompanionSessionState() {
  snapshotPersist?.flush()
  cancelAllCompanionRequests()
  conversationId.value = null
  msgs.value = []
  input.value = ''
  typing.value = false
}

const starters = computed(() => [
  '我在“数组”这里卡住了，应该先补哪一部分？',
  '帮我看看这门课最近的学习节奏是否合理。',
  '给我一组适合现在状态的节点练习。'
])

const hasText = computed(() => input.value.trim().length > 0)

/** Index of assistant in the current turn only (stop at user). */
function currentTurnAssistantIdx(list = msgs.value) {
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i]?.role === 'assistant') return i
    if (list[i]?.role === 'user') break
  }
  return -1
}

function roleLabel(role) {
  if (role === 'user') return '你'
  if (role === 'assistant') return 'AI'
  if (role === 'tool') return '工具'
  return '练习'
}

function isPlainMessage(message) {
  return message.role === 'user' || message.role === 'assistant'
}

/** Hide empty assistant placeholders so tools/practice appear first without a blank AI row. */
function shouldRenderMessage(message) {
  if (message?.role === 'assistant' && !message.content) return false
  return true
}

function toolStateClass(message) {
  if (message.ok === true) return 'is-ok'
  if (message.ok === false) return 'is-fail'
  return 'is-running'
}

function toolStateText(message) {
  if (message.ok === true) return '完成'
  if (message.ok === false) return '失败'
  return '运行中'
}

function practiceQuestionCount(message) {
  return message.card?.questions?.length || 0
}

function practiceCurrentQuestion(message) {
  return message.card?.questions?.[message.currentIndex] || null
}

function practiceSelected(message) {
  return message.answers.single[message.currentIndex]
}

function practiceSelectedSet(message) {
  return message.answers.multi[message.currentIndex] || []
}

function practiceTextValue(message) {
  return message.answers.text[message.currentIndex] || ''
}

function practiceIsMulti(message) {
  return practiceCurrentQuestion(message)?.type === 2
}

function practiceIsShort(message) {
  return practiceCurrentQuestion(message)?.type === 4
}

function practiceIsLast(message) {
  return message.currentIndex >= practiceQuestionCount(message) - 1
}

function practiceHasSelection(message) {
  if (practiceIsShort(message)) return true
  if (practiceIsMulti(message)) return practiceSelectedSet(message).length > 0
  return typeof practiceSelected(message) === 'number'
}

function practiceTitle(message) {
  return message.card?.node?.name || '节点练习'
}

function practiceMeta(message) {
  if (message.phase === 'result') return `${practiceQuestionCount(message)} 题`
  return `${Math.min(message.currentIndex + 1, Math.max(practiceQuestionCount(message), 1))} / ${Math.max(practiceQuestionCount(message), 1)}`
}

function practiceLastLabel(message) {
  if (message.phase === 'submitting') return '提交中...'
  if (message.phase === 'submitError') return '重新提交'
  return '提交答案'
}

function lockPractice(message) {
  return message.phase === 'submitting' || message.phase === 'result'
}

function currentSnapshotKey(targetConversationId = conversationId.value) {
  if (!courseId.value || !targetConversationId) return null
  return conversationSnapshotKey(studentId.value ?? 0, courseId.value, targetConversationId)
}

function readConversationSnapshot(targetConversationId) {
  const key = currentSnapshotKey(targetConversationId)
  if (!key) return null

  try {
    return JSON.parse(localStorage.getItem(key) || 'null')
  } catch {
    return null
  }
}

function clearConversationSnapshot(targetConversationId = conversationId.value) {
  if (targetConversationId === conversationId.value) {
    snapshotPersist?.cancel()
  }
  const key = currentSnapshotKey(targetConversationId)
  if (!key) return
  localStorage.removeItem(key)
}

function persistTypedSnapshot(key = currentSnapshotKey(), snapshot = createTypedMessagesSnapshot(msgs.value)) {
  if (!key) return

  try {
    localStorage.setItem(key, JSON.stringify(snapshot))
  } catch (error) {
    console.warn('Failed to persist companion snapshot:', error)
  }
}

function scheduleTypedSnapshot() {
  const key = currentSnapshotKey()
  if (!key) return
  snapshotPersist.schedule(key, createTypedMessagesSnapshot(msgs.value))
}

snapshotPersist = createDebouncedPersist(persistTypedSnapshot, 250)

function clearPracticeError(message) {
  if (message.submitError) message.submitError = ''
  if (message.phase === 'submitError') message.phase = 'answering'
}

function onPracticePick(message, optionIndex) {
  if (lockPractice(message)) return
  clearPracticeError(message)

  if (practiceIsMulti(message)) {
    const current = [...practiceSelectedSet(message)]
    const existingIndex = current.indexOf(optionIndex)
    if (existingIndex >= 0) current.splice(existingIndex, 1)
    else current.push(optionIndex)

    message.answers.multi = {
      ...message.answers.multi,
      [message.currentIndex]: current
    }
    return
  }

  message.answers.single = {
    ...message.answers.single,
    [message.currentIndex]: optionIndex
  }
}

function onPracticeTextInput(message, text) {
  if (lockPractice(message)) return
  clearPracticeError(message)
  message.answers.text = {
    ...message.answers.text,
    [message.currentIndex]: text
  }
}

function onPracticePrev(message) {
  if (lockPractice(message)) return
  if (message.currentIndex > 0) {
    message.currentIndex -= 1
    scrollToEnd()
  }
}

function onPracticeSkip(message) {
  if (lockPractice(message)) return
  clearPracticeError(message)
  message.skippedSet = {
    ...message.skippedSet,
    [message.currentIndex]: true
  }

  if (practiceIsLast(message)) {
    submitPracticeMessage(message)
  } else {
    message.currentIndex += 1
    scrollToEnd()
  }
}

function onPracticeNext(message) {
  if (lockPractice(message)) return
  if (!practiceIsShort(message) && !practiceHasSelection(message)) return

  clearPracticeError(message)
  if (practiceIsLast(message)) {
    submitPracticeMessage(message)
  } else {
    message.currentIndex += 1
    scrollToEnd()
  }
}

async function submitPracticeMessage(message) {
  const answers = buildPracticeAnswerList(message)
  if (!answers.length) {
    message.submitError = '请至少回答一道题后再提交。'
    message.phase = 'submitError'
    return
  }

  message.submitError = ''
  message.phase = 'submitting'
  scrollToEnd()

  try {
    const result = await submitPractice(message.card.sessionId, {
      answers
    })
    message.submitResult = result
    message.phase = 'result'
  } catch (error) {
    message.submitError = error.message || '提交失败，请稍后重试。'
    message.phase = 'submitError'
  }

  scrollToEnd()
}

function practiceMasteryDelta(message) {
  if (!message.submitResult) return null
  const before = message.submitResult.masteryBefore
  const after = message.submitResult.masteryAfter
  if (before == null || after == null) return null
  return Math.round(after - before)
}

function practiceWeakSummary(items) {
  return items.map((item) => `${item.name}${item.hitCount > 1 ? ` ×${item.hitCount}` : ''}`).join('，')
}

function practiceStatusSummary(message) {
  const bits = []
  bits.push(message.submitResult?.itemCompleted ? '已完成当前路径项' : '已记录本次练习')
  if (message.submitResult?.pathRegenerated) bits.push('学习路径已调整')
  return bits.join(' · ')
}

function practiceGradeTitle(message, grade, gradeIndex) {
  const questionIndex = (message.card?.questions || []).findIndex((question) => question.questionId === grade.questionId)
  if (questionIndex >= 0) return `第 ${questionIndex + 1} 题`
  return `第 ${gradeIndex + 1} 题`
}

function practiceGradeState(grade) {
  if (grade.correct === true) return '正确'
  if (grade.correct === false) return '需复习'
  return '已提交'
}

function formatPct(value) {
  return value == null ? '--' : `${Math.round(value)}%`
}

function deltaClass(delta) {
  if (delta > 0) return 'is-up'
  if (delta < 0) return 'is-down'
  return 'is-flat'
}

function formatDelta(delta) {
  if (delta == null) return ''
  if (delta > 0) return `+${delta}`
  return `${delta}`
}

async function loadConversations() {
  if (!courseId.value) return
  const { signal, isCurrent } = conversationListGate.next()
  try {
    const data = await listConversations(courseId.value, { signal })
    if (!isCurrent()) return
    conversations.value = data || []
  } catch (error) {
    if (!isCurrent() || isAbortError(error)) return
    console.error('Failed to load conversations:', error)
  }
}

async function openConversation(id) {
  snapshotPersist.flush()
  // 打开另一会话时取消进行中的 SSE，避免 token 写到错误会话
  streamGate.cancel()
  typing.value = false
  const { signal, isCurrent } = conversationDetailGate.next()
  conversationId.value = id
  try {
    const conversation = await fetchConversation(id, { signal })
    if (!isCurrent()) return
    const serverMessages = (Array.isArray(conversation) ? conversation : (conversation?.messages || []))
      .map(normalizeConversationMessage)
    const snapshot = readConversationSnapshot(id)
    const restored = restoreTypedMessagesFromSnapshot(serverMessages, snapshot)

    msgs.value = restored.messages
    if (!restored.usedSnapshot && snapshot) {
      clearConversationSnapshot(id)
    }

    await nextTick()
    if (!isCurrent()) return
    scrollToEnd()
  } catch (error) {
    if (!isCurrent() || isAbortError(error)) return
    console.error('Failed to fetch conversation:', error)
    msgs.value = []
  }
}

function newChat() {
  // flush → cancel stream/detail → clear state（列表保留，属同课程）
  snapshotPersist.flush()
  streamGate.cancel()
  conversationDetailGate.cancel()
  conversationId.value = null
  msgs.value = []
  input.value = ''
  typing.value = false
}

function clearContextNodeCode() {
  const query = { ...route.query }
  delete query.nodeCode
  router.replace({ query })
}

async function handleDelete(id) {
  try {
    await deleteConversation(id)
    clearConversationSnapshot(id)
    if (conversationId.value === id) {
      streamGate.cancel()
      conversationDetailGate.cancel()
      conversationId.value = null
      msgs.value = []
      typing.value = false
    }
    await loadConversations()
  } catch (error) {
    if (isAbortError(error)) return
    console.error('删除会话失败:', error)
  }
}

function appendToAssistant(content) {
  msgs.value = appendAssistantToken(msgs.value, content)
}

function sendStarter(starter) {
  input.value = starter
  sendText()
}

async function sendText() {
  const text = input.value.trim()
  if (!text || typing.value) return

  // Do not pre-push empty assistant — tools/practice must land before final AI reply.
  msgs.value.push({ role: 'user', content: text })
  input.value = ''
  typing.value = true

  await nextTick()
  scrollToEnd()

  const payload = buildCompanionChatPayload({
    courseId: courseId.value,
    conversationId: conversationId.value,
    nodeCode: ctxNodeCode.value,
    message: text
  })

  const { signal, isCurrent } = streamGate.next()
  /** 避免 onError + catch 双写错误气泡 */
  let errorReported = false

  try {
    await chatStream(payload, {
      onMeta: async (meta) => {
        if (!isCurrent()) return
        if (meta?.conversationId && !conversationId.value) {
          conversationId.value = meta.conversationId
          snapshotPersist.flush()
          await loadConversations()
        }
      },
      onToolStart: (event) => {
        if (!isCurrent()) return
        msgs.value.push(createToolMessage(event))
        scrollToEnd()
      },
      onToolResult: (event) => {
        if (!isCurrent()) return
        msgs.value = applyToolResult(msgs.value, event)
        scrollToEnd()
      },
      onPracticeCard: (event) => {
        if (!isCurrent()) return
        msgs.value.push(createPracticeMessage(event))
        scrollToEnd()
      },
      onToken: (token) => {
        if (!isCurrent()) return
        appendToAssistant(token)
        scrollToEnd()
      },
      onDone: async () => {
        if (!isCurrent()) return
        typing.value = false
        snapshotPersist.flush()
        await loadConversations()
        scrollToEnd()
      },
      onError: (message) => {
        if (!isCurrent()) return
        if (errorReported) return
        errorReported = true
        typing.value = false
        snapshotPersist.flush()
        const idx = currentTurnAssistantIdx()
        const prefix = idx >= 0 && msgs.value[idx].content ? '\n\n' : ''
        appendToAssistant(`${prefix}[错误] ${message}`)
        scrollToEnd()
      }
    }, { signal })
  } catch (error) {
    // Abort（新对话/切课/卸载）不是网络错误，不写消息
    if (!isCurrent() || isAbortError(error)) {
      if (isCurrent()) typing.value = false
      return
    }
    if (errorReported) {
      if (isCurrent()) typing.value = false
      return
    }
    errorReported = true
    typing.value = false
    snapshotPersist.flush()
    const idx = currentTurnAssistantIdx()
    const prefix = idx >= 0 && msgs.value[idx].content ? '\n\n' : ''
    appendToAssistant(`${prefix}[网络错误] ${error.message}`)
    scrollToEnd()
  }
}

function onKey(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendText()
  }
}

function scrollToEnd() {
  if (!scrollEl.value) return
  nextTick(() => {
    if (!scrollEl.value) return
    scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
}

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  const diff = now - date
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 7) return `${days} 天前`
  return date.toLocaleDateString('zh-CN')
}

watch(courseId, (value) => {
  // flush → cancel all → clear state → load list for new course
  resetCompanionSessionState()
  conversations.value = []
  if (value) loadConversations()
}, { immediate: true })

watch(() => route.query.nodeCode, (value) => {
  ctxNodeCode.value = readCompanionNodeCode(value)
}, { immediate: true })

watch(msgs, () => {
  scheduleTypedSnapshot()
}, { deep: true })

onMounted(() => {
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
      sendText,
      submitPracticeMessage,
      persistTypedSnapshot,
      clearConversationSnapshot,
      conversationListGate,
      conversationDetailGate,
      streamGate,
      cancelAllCompanionRequests,
      resetCompanionSessionState
    }
  }
})

onUnmounted(() => {
  // flush → cancel all in-flight list/detail/SSE
  snapshotPersist?.flush()
  cancelAllCompanionRequests()
  if (import.meta.env.DEV && window.__wjCompanion) {
    delete window.__wjCompanion
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

.cp-sidebar {
  width: 260px;
  flex: none;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: var(--card);
  overflow: hidden;
}

.cp-sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px;
  border-bottom: 1px solid var(--line);
}

.cp-sidebar-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.cp-btn-new {
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: transparent;
  color: var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.cp-btn-new:hover {
  background: var(--hover);
  border-color: var(--mut);
}

.cp-conversations {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.cp-conv-item {
  position: relative;
  padding: 12px 34px 12px 14px;
  border-radius: 9px;
  margin-bottom: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.cp-conv-item:hover {
  background: var(--hover);
}

.cp-conv-item.active {
  background: var(--card2);
  border: 1px solid var(--line);
}

.cp-conv-title {
  margin-bottom: 4px;
  font-size: 13.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-conv-meta {
  font-size: 11.5px;
  color: var(--mut);
}

.cp-conv-del {
  position: absolute;
  top: 50%;
  right: 6px;
  transform: translateY(-50%);
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--mut);
  display: none;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  line-height: 1;
}

.cp-conv-item:hover .cp-conv-del {
  display: flex;
}

.cp-conv-del:hover {
  background: var(--card2);
  color: var(--acc);
}

.cp-conv-empty {
  padding: 40px 16px;
  text-align: center;
  color: var(--mut);
  font-size: 13px;
}

.cp-main {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

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
  margin: 0 0 12px;
  font-family: 'Noto Serif SC', serif;
  font-size: 26px;
  font-weight: 600;
}

.cp-empty-sub {
  max-width: 480px;
  margin: 0 0 28px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--mut);
}

.cp-starters {
  width: 100%;
  max-width: 440px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cp-starter {
  width: 100%;
  padding: 14px 18px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  color: var(--ink);
  font-family: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.cp-starter:hover {
  background: var(--hover);
  border-color: var(--acc);
}

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
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 50%;
  background: var(--card2);
  color: var(--mut);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}

.cp-msg.is-user .cp-msg-role {
  background: var(--acc);
  color: #fffdf8;
}

.cp-msg-main {
  flex: 1;
  min-width: 0;
}

.cp-msg-body {
  padding-top: 8px;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.cp-tool-capsule {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  max-width: 100%;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--card);
  color: var(--mut);
  font-size: 12.5px;
  line-height: 1;
}

.cp-tool-capsule.is-running {
  border-color: rgba(84, 102, 137, 0.24);
  color: var(--ink);
}

.cp-tool-capsule.is-ok {
  border-color: rgba(46, 125, 50, 0.24);
  background: rgba(46, 125, 50, 0.06);
  color: #2e6a31;
}

.cp-tool-capsule.is-fail {
  border-color: rgba(198, 40, 40, 0.24);
  background: rgba(198, 40, 40, 0.05);
  color: #a53f3f;
}

.cp-tool-dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.75;
}

.cp-tool-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-tool-state {
  color: inherit;
  opacity: 0.78;
  white-space: nowrap;
}

.cp-practice {
  max-width: 760px;
  padding: 16px 18px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
}

.cp-practice-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.cp-practice-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.cp-practice-meta {
  flex: none;
  color: var(--mut);
  font-size: 12px;
  white-space: nowrap;
}

.cp-practice-note,
.cp-practice-empty,
.cp-practice-line,
.cp-grade-extra {
  font-size: 12.5px;
  line-height: 1.6;
}

.cp-practice-note {
  margin-top: 10px;
  color: var(--mut);
}

.cp-practice-note.is-error {
  color: #b24b4b;
}

.cp-practice-result {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cp-practice-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--bg);
}

.cp-practice-summary-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.cp-summary-label {
  color: var(--mut);
  font-size: 12px;
}

.cp-summary-value {
  font-size: 15px;
  font-weight: 600;
}

.cp-summary-arrow {
  padding: 0 4px;
  color: var(--mut);
}

.cp-summary-delta {
  font-size: 12.5px;
  font-weight: 600;
}

.cp-summary-delta.is-up {
  color: #2e6a31;
}

.cp-summary-delta.is-down {
  color: #a53f3f;
}

.cp-summary-delta.is-flat {
  color: var(--mut);
}

.cp-summary-badge {
  flex: none;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--card2);
  font-size: 12px;
  color: var(--ink);
}

.cp-practice-lines {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--mut);
}

.cp-grades {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cp-grade-item {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.cp-grade-item.is-correct {
  border-color: rgba(46, 125, 50, 0.18);
}

.cp-grade-item.is-wrong {
  border-color: rgba(198, 40, 40, 0.18);
}

.cp-grade-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.cp-grade-title {
  font-size: 13px;
  font-weight: 600;
}

.cp-grade-state {
  flex: none;
  color: var(--mut);
  font-size: 12px;
}

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
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.5;
  }
  40% {
    transform: translateY(-6px);
    opacity: 1;
  }
}

.cp-context-chip {
  position: absolute;
  left: 24px;
  bottom: 100px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--card);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  font-size: 12.5px;
}

.cp-chip-label {
  color: var(--mut);
}

.cp-chip-code {
  font-weight: 500;
}

.cp-chip-close {
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--mut);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.cp-chip-close:hover {
  background: var(--hover);
}

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
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
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

@media (max-width: 960px) {
  .cp-practice {
    max-width: 100%;
  }
}

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

  .cp-input-area {
    padding: 14px 16px;
  }

  .cp-context-chip {
    left: 16px;
    right: 16px;
    bottom: 92px;
    max-width: calc(100% - 32px);
  }
}

@media (max-width: 640px) {
  .wj-companion {
    flex-direction: column;
  }

  .cp-sidebar {
    width: 100%;
    height: 168px;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }

  .cp-msg {
    gap: 10px;
  }

  .cp-msg-role {
    width: 32px;
    height: 32px;
  }

  .cp-practice {
    padding: 14px;
  }

  .cp-practice-head,
  .cp-practice-summary,
  .cp-grade-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .cp-tool-capsule {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
