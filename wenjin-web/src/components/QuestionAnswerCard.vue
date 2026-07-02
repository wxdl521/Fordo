<template>
  <!-- 题号 + 章节标签 -->
  <div class="qa-meta">
    <span class="qa-num">第 {{ index }} 题<span class="qa-of"> · 共 {{ total }} 题</span></span>
    <span class="qa-chapter">{{ question.c }}</span>
  </div>

  <!-- 题内进度条 -->
  <div class="qa-progress-track">
    <div class="qa-progress-fill" :style="{ width: progressPct }"></div>
  </div>

  <!-- 多选提示 -->
  <div v-if="isMulti" class="qa-multi-hint">（多选题，请选择所有正确选项）</div>

  <!-- 题干 -->
  <div class="qa-stem">{{ question.q }}</div>

  <!-- 选项（单选 / 多选 / 判断） -->
  <div v-if="!isShortAnswer" class="qa-options">
    <div
      v-for="(opt, i) in (question.o || [])"
      :key="i"
      class="qa-opt"
      :class="{ selected: isOptSelected(i) }"
      @click="$emit('pick', i)"
    >
      <span class="qa-opt-letter" :class="{ selected: isOptSelected(i) }">{{ letters[i] }}</span>
      <span class="qa-opt-text">{{ opt }}</span>
    </div>
  </div>

  <!-- 简答题输入框 -->
  <div v-else class="qa-short-wrap">
    <textarea
      class="qa-textarea"
      :value="textValue"
      placeholder="请写下你的答案…"
      rows="5"
      @input="$emit('textInput', $event.target.value)"
    ></textarea>
  </div>

  <!-- 导航按钮 -->
  <div class="qa-nav">
    <button
      class="qa-btn-ghost"
      :style="{ visibility: index === 1 ? 'hidden' : 'visible' }"
      @click="$emit('prev')"
    >上一题</button>
    <button class="qa-btn-skip" @click="$emit('skip')">不确定，跳过</button>
    <button
      class="qa-btn-next"
      :class="{ active: hasSelection }"
      @click="$emit('next')"
    >{{ isLast ? lastLabel : '下一题' }}</button>
  </div>

  <!-- 提示文字 -->
  <div v-if="showHint" class="qa-hint">这不是考试——答错不扣分，跳过也是有用的信号。</div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 题目对象：{ questionId, c(chapter), q(stem), o(option texts[]), optionKeys[], type } */
  question: { type: Object, required: true },
  /** 1-based 题目序号 */
  index: { type: Number, required: true },
  /** 题目总数 */
  total: { type: Number, required: true },
  /** SINGLE/TRUE_FALSE：当前选中选项下标（undefined=未选）；MULTI/SHORT不使用 */
  selected: { type: Number, default: undefined },
  /** MULTI：当前选中选项下标数组；其他题型不使用 */
  selectedSet: { type: Array, default: () => [] },
  /** SHORT_ANSWER：当前文本输入；其他题型不使用 */
  textValue: { type: String, default: '' },
  /** 是否是最后一题 */
  isLast: { type: Boolean, default: false },
  /** 最后一题按钮文本（各场景可自定义）*/
  lastLabel: { type: String, default: '完成作答' },
  /** "下一题/完成"按钮是否高亮可用（父组件根据答题状态传入） */
  hasSelection: { type: Boolean, default: false },
  /** 是否显示底部提示文字 */
  showHint: { type: Boolean, default: true }
})

defineEmits(['pick', 'textInput', 'prev', 'next', 'skip'])

const letters = ['A', 'B', 'C', 'D', 'E', 'F']

/** 题型判断（type 缺省=1=单选） */
const questionType = computed(() => props.question?.type || 1)
const isMulti = computed(() => questionType.value === 2)
const isShortAnswer = computed(() => questionType.value === 4)

const progressPct = computed(() => {
  if (!props.total) return '0%'
  return Math.round((props.index / props.total) * 100) + '%'
})

function isOptSelected(i) {
  if (isMulti.value) return Array.isArray(props.selectedSet) && props.selectedSet.includes(i)
  return props.selected === i
}
</script>

<style scoped>
/* 题号行 */
.qa-meta {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}

.qa-num {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 15px;
  color: var(--mut);
}

.qa-of {
  opacity: 0.55;
}

.qa-chapter {
  font-size: 12px;
  color: var(--mut);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 10px;
}

/* 题内进度条 */
.qa-progress-track {
  height: 4px;
  background: var(--card2);
  border-radius: 99px;
  overflow: hidden;
  margin-bottom: 20px;
}

.qa-progress-fill {
  height: 100%;
  background: var(--acc);
  border-radius: 99px;
  transition: width 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 多选提示 */
.qa-multi-hint {
  font-size: 12px;
  color: var(--mut);
  margin-bottom: 8px;
}

/* 题干 */
.qa-stem {
  font-size: 18px;
  font-weight: 500;
  line-height: 1.65;
  margin-bottom: 26px;
  color: var(--ink);
}

/* 选项列表 */
.qa-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 32px;
}

.qa-opt {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 52px;
  box-sizing: border-box;
  padding: 13px 16px;
  background: var(--card);
  border: 1.5px solid var(--line);
  border-radius: 11px;
  cursor: pointer;
  transition: background-color 0.18s, border-color 0.18s;
}

.qa-opt:hover {
  border-color: var(--mut);
}

.qa-opt.selected {
  background: var(--accSoft);
  border-color: var(--acc);
}

.qa-opt-letter {
  width: 26px;
  height: 26px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid var(--line);
  border-radius: 50%;
  font-size: 12.5px;
  color: var(--mut);
  background: transparent;
  transition: background-color 0.18s, border-color 0.18s, color 0.18s;
}

.qa-opt-letter.selected {
  background: var(--acc);
  border-color: var(--acc);
  color: #FFFDF8;
}

.qa-opt-text {
  font-size: 14.5px;
  line-height: 1.55;
  color: var(--ink);
}

/* 简答输入 */
.qa-short-wrap {
  margin-bottom: 32px;
}

.qa-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  background: var(--card);
  border: 1.5px solid var(--line);
  border-radius: 10px;
  font-size: 14.5px;
  font-family: inherit;
  color: var(--ink);
  resize: vertical;
  outline: none;
  transition: border-color 0.18s;
  line-height: 1.6;
}

.qa-textarea:focus {
  border-color: var(--acc);
}

/* 导航按钮行 */
.qa-nav {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 8px;
}

.qa-btn-ghost {
  height: 42px;
  padding: 0 18px;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 9px;
  color: var(--mut);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  font-family: inherit;
  transition: border-color 0.15s, color 0.15s;
}

.qa-btn-ghost:hover {
  border-color: var(--mut);
  color: var(--ink);
}

.qa-btn-skip {
  margin-left: auto;
  height: 42px;
  padding: 0 16px;
  background: transparent;
  border: none;
  color: var(--mut);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  border-radius: 9px;
  font-family: inherit;
  transition: color 0.15s;
}

.qa-btn-skip:hover {
  color: var(--ink);
}

.qa-btn-next {
  height: 42px;
  padding: 0 30px;
  background: var(--card2);
  border: none;
  border-radius: 9px;
  color: var(--mut);
  font-size: 14px;
  font-weight: 500;
  cursor: not-allowed;
  white-space: nowrap;
  font-family: inherit;
  transition: background-color 0.2s, color 0.2s;
}

.qa-btn-next.active {
  background: var(--acc);
  color: #FFFDF8;
  cursor: pointer;
}

/* 提示文字 */
.qa-hint {
  text-align: center;
  font-size: 12px;
  color: var(--mut);
  opacity: 0.75;
  padding-bottom: 18px;
}
</style>
