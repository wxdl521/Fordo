<template>
  <div class="crv" data-testid="code-result">
    <div v-if="ungraded" class="crv-ungraded" role="status" data-testid="code-ungraded">
      未判分：{{ message || '判题服务暂不可用，本题未计分，可重新开始一次练习。' }}
    </div>
    <template v-else-if="result">
      <div class="crv-head">
        <span v-if="result.mode" class="crv-mode">{{ result.mode === 'RUN' ? '试运行' : '判题' }}</span>
        <span class="crv-badge" :class="`is-${tone}`" data-testid="code-verdict">{{ label }}</span>
        <span v-if="hasCounts" class="crv-count" data-testid="code-count">
          通过 {{ result.passedCases }} / {{ result.totalCases }} 个用例
        </span>
        <span v-if="result.durationMs != null" class="crv-time">{{ result.durationMs }} ms</span>
      </div>
      <div v-if="result.message" class="crv-msg">{{ result.message }}</div>
      <div v-if="result.compileError" class="crv-block">
        <div class="crv-cap">编译错误</div>
        <pre class="crv-pre" data-testid="code-compile-error">{{ result.compileError }}</pre>
      </div>
      <div v-if="result.stdout" class="crv-block">
        <div class="crv-cap">标准输出</div>
        <pre class="crv-pre" data-testid="code-stdout">{{ result.stdout }}</pre>
      </div>
      <div v-if="result.stderr" class="crv-block">
        <div class="crv-cap">错误输出</div>
        <pre class="crv-pre">{{ result.stderr }}</pre>
      </div>
      <ul v-if="cases.length" class="crv-cases" aria-label="用例结果">
        <li
          v-for="c in cases"
          :key="c.index"
          class="crv-case"
          :class="c.passed ? 'is-ok' : 'is-bad'"
          data-testid="code-case"
        >
          <div class="crv-case-head">
            <span>用例 {{ c.index }}{{ c.hidden ? '（隐藏）' : '' }}</span>
            <span class="crv-case-state">{{ c.passed ? '通过' : '未通过' }}<template v-if="!c.passed && c.verdict && c.verdict !== 'WRONG_ANSWER'"> · {{ verdictLabel(c.verdict) }}</template></span>
          </div>
          <dl v-if="!c.hidden && !c.passed" class="crv-diff">
            <template v-if="c.stdinPreview">
              <dt>输入</dt><dd><pre>{{ c.stdinPreview }}</pre></dd>
            </template>
            <dt>期望输出</dt><dd><pre>{{ c.expectedPreview }}</pre></dd>
            <dt>实际输出</dt><dd><pre>{{ c.actualPreview }}</pre></dd>
          </dl>
        </li>
      </ul>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { verdictLabel, verdictTone } from '../utils/codeResult.js'

const props = defineProps({
  /** codeResult (practice) or code_result event (companion) */
  result: { type: Object, default: null },
  ungraded: { type: Boolean, default: false },
  message: { type: String, default: '' }
})

const label = computed(() => verdictLabel(props.result?.verdict))
const tone = computed(() => verdictTone(props.result?.verdict))
const hasCounts = computed(() => props.result?.totalCases != null && props.result.totalCases > 0)
const cases = computed(() => props.result?.cases || [])
</script>

<style scoped>
.crv { font-size: 13px; color: var(--ink); }
.crv-head { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 6px; }
.crv-badge { padding: 2px 10px; border-radius: 999px; font-weight: 600; border: 1px solid var(--line); }
.crv-badge.is-ok { background: var(--accSoft); color: var(--ink); border-color: var(--acc); }
.crv-badge.is-bad { color: #9b2c2c; border-color: #9b2c2c; }
.crv-badge.is-warn { color: #7a5200; border-color: #7a5200; }
.crv-mode, .crv-time, .crv-count { color: var(--mut); }
.crv-ungraded, .crv-msg { padding: 8px 12px; border: 1px dashed var(--line); border-radius: 8px; color: var(--mut); margin-bottom: 6px; }
.crv-cap { font-size: 12px; color: var(--mut); margin: 6px 0 2px; }
.crv-pre, .crv-diff pre {
  margin: 0; padding: 8px 10px; background: var(--card2); border-radius: 8px; overflow: auto;
  font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; white-space: pre-wrap; word-break: break-word;
}
.crv-cases { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.crv-case { border: 1px solid var(--line); border-radius: 8px; padding: 6px 10px; }
.crv-case-head { display: flex; justify-content: space-between; gap: 8px; }
.crv-case.is-bad .crv-case-state { color: #9b2c2c; font-weight: 600; }
.crv-diff { margin: 6px 0 0; display: grid; grid-template-columns: max-content 1fr; gap: 4px 10px; }
.crv-diff dt { color: var(--mut); }
.crv-diff dd { margin: 0; min-width: 0; }
</style>
