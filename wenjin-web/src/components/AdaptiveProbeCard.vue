<template>
  <!-- 单道探针题：题号 + 题干 + 选项（无导航按钮，探针内全部题目共用一个「提交本轮」） -->
  <div class="apc">
    <div class="apc-meta">
      <span class="apc-num">第 {{ index }} 题<span v-if="total > 1" class="apc-of"> · 共 {{ total }} 题</span></span>
      <span v-if="question.c" class="apc-chapter">{{ question.c }}</span>
    </div>

    <div class="apc-stem">{{ question.q }}</div>

    <div class="apc-options">
      <div
        v-for="(opt, i) in (question.o || [])"
        :key="i"
        class="apc-opt"
        :class="{ selected: selected === i }"
        @click="$emit('pick', i)"
      >
        <span class="apc-opt-letter" :class="{ selected: selected === i }">{{ letters[i] }}</span>
        <span class="apc-opt-text">{{ opt }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  /** 题目对象：{ questionId, c(chapter), q(stem), o(option texts[]), optionKeys[] } */
  question: { type: Object, required: true },
  /** 1-based 题号 */
  index: { type: Number, required: true },
  /** 本探针题目总数（1–2） */
  total: { type: Number, default: 1 },
  /** 当前选中选项下标（undefined=未选） */
  selected: { type: Number, default: undefined }
})

defineEmits(['pick'])

const letters = ['A', 'B', 'C', 'D', 'E', 'F']
</script>

<style scoped>
.apc {
  padding: 18px 0;
  border-bottom: 1px solid var(--line);
}
.apc:last-child {
  border-bottom: none;
}

.apc-meta {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}

.apc-num {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 15px;
  color: var(--mut);
}

.apc-of {
  opacity: 0.55;
}

.apc-chapter {
  font-size: 12px;
  color: var(--mut);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 10px;
}

.apc-stem {
  font-size: 18px;
  font-weight: 500;
  line-height: 1.65;
  margin-bottom: 22px;
  color: var(--ink);
}

.apc-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.apc-opt {
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

.apc-opt:hover {
  border-color: var(--mut);
}

.apc-opt.selected {
  background: var(--accSoft);
  border-color: var(--acc);
}

.apc-opt-letter {
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

.apc-opt-letter.selected {
  background: var(--acc);
  border-color: var(--acc);
  color: #FFFDF8;
}

.apc-opt-text {
  font-size: 14.5px;
  line-height: 1.55;
  color: var(--ink);
}
</style>
