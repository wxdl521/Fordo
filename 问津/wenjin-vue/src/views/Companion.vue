<template>
  <div :style="{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--bg)', color: 'var(--ink)' }">
    <TopBar compact subtitle="软件工程 · AI 学习伴侣" :subtitle-hidden="width < 600">
      <NavLink v-show="width >= 760" to="/path">学习路径</NavLink>
      <NavLink to="/map">染色地图</NavLink>
    </TopBar>

    <div :style="{ flex: 1, minHeight: 0, display: 'flex' }">

      <!-- 侧栏 -->
      <div v-show="width >= 880" :style="{ width: '240px', flex: 'none', boxSizing: 'border-box', borderRight: '1px solid var(--line)', padding: '16px 14px', display: 'flex', flexDirection: 'column', gap: '6px', transition: 'border-color 0.35s' }">
        <button @click="newChat" class="wj-hover-card2" :style="{ height: '38px', flex: 'none', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13px', cursor: 'pointer', marginBottom: '14px' }">新对话</button>
        <div :style="{ fontSize: '11px', letterSpacing: '3px', color: 'var(--mut)', padding: '0 8px 6px' }">最近对话</div>
        <div :style="{ padding: '10px 12px', borderRadius: '9px', background: 'var(--card2)', cursor: 'pointer' }">
          <div :style="convTitle">实体还是属性？</div>
          <div :style="convSub">业务实体识别 · 今天</div>
        </div>
        <div class="wj-hover-card2" :style="{ padding: '10px 12px', borderRadius: '9px', cursor: 'pointer' }">
          <div :style="convTitle">«include» 与 «extend» 的区分</div>
          <div :style="convSub">用例建模 · 6月9日</div>
        </div>
        <div class="wj-hover-card2" :style="{ padding: '10px 12px', borderRadius: '9px', cursor: 'pointer' }">
          <div :style="convTitle">等价类划分的边界怎么取</div>
          <div :style="convSub">黑盒测试 · 6月7日</div>
        </div>
        <div :style="{ marginTop: 'auto', padding: '12px 8px 4px', borderTop: '1px solid var(--line)', fontSize: '11.5px', color: 'var(--mut)', lineHeight: 1.7, transition: 'border-color 0.35s' }">伴侣会结合你的染色地图与诊断记录作答。</div>
      </div>

      <!-- 对话列 -->
      <div :style="{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }">
        <div ref="scrollEl" :style="{ flex: 1, minHeight: 0, overflowY: 'auto' }">
          <div :style="{ maxWidth: '768px', margin: '0 auto', boxSizing: 'border-box', padding: msgsPad, display: 'flex', flexDirection: 'column', gap: '24px' }">

            <!-- 空状态 -->
            <div v-if="msgs.length === 0" :style="{ minHeight: '56vh', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '28px', animation: 'wjFadeUp 0.5s ease both' }">
              <div :style="{ writingMode: 'vertical-rl', fontFamily: serif, fontSize: '22px', fontWeight: 500, letterSpacing: '10px', color: 'var(--ink)', height: '150px' }">有疑，则问津</div>
              <div :style="{ fontSize: '13px', color: 'var(--mut)' }">从下面任意一问开始，或直接输入。</div>
              <div :style="{ display: 'flex', flexDirection: 'column', gap: '10px', width: '100%', maxWidth: '380px' }">
                <button v-for="(s, i) in starters" :key="i" @click="sendText(s)" class="wj-hover-acc" :style="{ height: '44px', boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', color: 'var(--ink)', fontSize: '13.5px', cursor: 'pointer' }">{{ s }}</button>
              </div>
            </div>

            <!-- 消息流 -->
            <template v-else>
              <div :style="{ textAlign: 'center', fontSize: '11.5px', color: 'var(--mut)', opacity: 0.8 }">今天 14:32 · 从图谱节点「业务实体识别」发起</div>
              <div v-for="(m, idx) in msgs" :key="idx" :style="{ display: 'flex', gap: '12px', justifyContent: m.role === 'ai' ? 'flex-start' : 'flex-end', animation: 'wjFadeUp 0.35s ease both' }">
                <div v-if="m.role === 'ai'" :style="{ width: '30px', height: '30px', flex: 'none', border: '1px solid var(--line)', borderRadius: '8px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: serif, fontSize: '14px', color: 'var(--acc)', transition: 'border-color 0.35s' }">津</div>
                <div :style="{ maxWidth: m.role === 'ai' ? 'calc(100% - 42px)' : '78%', minWidth: 0, display: 'flex', flexDirection: 'column', gap: '10px', alignItems: m.role === 'ai' ? 'flex-start' : 'flex-end' }">
                  <router-link v-if="m.ctx" to="/knowledge" class="wj-hover-acc" :style="{ display: 'inline-flex', alignItems: 'center', gap: '8px', border: '1px solid var(--line)', borderRadius: '999px', padding: '5px 13px', fontSize: '11.5px', color: 'var(--mut)', textDecoration: 'none' }">
                    <span :style="{ width: '7px', height: '7px', flex: 'none', borderRadius: '50%', background: 'var(--warn)' }"></span>
                    <span>来自图谱 · 业务实体识别 · 掌握度 52%</span>
                  </router-link>
                  <div :style="{ boxSizing: 'border-box', background: m.role === 'ai' ? 'transparent' : 'var(--card)', border: '1px solid ' + (m.role === 'ai' ? 'transparent' : 'var(--line)'), borderRadius: '13px', padding: m.role === 'ai' ? '4px 0 0' : '11px 16px', fontSize: '14px', lineHeight: 1.9, whiteSpace: 'pre-wrap', textWrap: 'pretty', transition: 'background-color 0.35s, border-color 0.35s' }">{{ m.text }}</div>
                  <div v-if="m.role === 'ai' && idx === lastAiIdx && m.chips && !typing" :style="{ display: 'flex', flexWrap: 'wrap', gap: '8px' }">
                    <button v-for="(ch, ci) in m.chips" :key="ci" @click="sendText(ch)" class="wj-hover-acc" :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '999px', color: 'var(--ink)', fontSize: '12.5px', padding: '8px 15px', cursor: 'pointer' }">{{ ch }}</button>
                  </div>
                </div>
              </div>

              <div v-if="typing" :style="{ display: 'flex', gap: '12px', alignItems: 'center' }">
                <div :style="{ width: '30px', height: '30px', flex: 'none', border: '1px solid var(--line)', borderRadius: '8px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: serif, fontSize: '14px', color: 'var(--acc)' }">津</div>
                <span :style="{ fontSize: '13px', color: 'var(--mut)', animation: 'wjPulse2 1.4s ease-in-out infinite' }">正在沿图谱思考……</span>
              </div>
            </template>
          </div>
        </div>

        <!-- 输入区 -->
        <div :style="{ flex: 'none', borderTop: '1px solid var(--line)', background: 'var(--bg)', transition: 'border-color 0.35s, background-color 0.35s' }">
          <div :style="{ maxWidth: '768px', margin: '0 auto', boxSizing: 'border-box', padding: composerPad, display: 'flex', flexDirection: 'column', gap: '10px' }">
            <div v-if="ctxOn" :style="{ display: 'flex' }">
              <div :style="{ display: 'flex', alignItems: 'center', gap: '8px', border: '1px solid var(--line)', background: 'var(--card)', borderRadius: '999px', padding: '4px 6px 4px 13px', fontSize: '11.5px', color: 'var(--mut)', transition: 'background-color 0.35s, border-color 0.35s' }">
                <span :style="{ width: '7px', height: '7px', flex: 'none', borderRadius: '50%', background: 'var(--warn)' }"></span>
                <span>对话上下文：业务实体识别 · 薄弱</span>
                <button @click="ctxOn = false" class="wj-hover-card2" title="移除上下文" :style="{ width: '20px', height: '20px', flex: 'none', border: 'none', background: 'transparent', borderRadius: '50%', color: 'var(--mut)', fontSize: '13px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }">×</button>
              </div>
            </div>
            <div :style="{ display: 'flex', alignItems: 'flex-end', gap: '10px', border: '1px solid var(--line)', background: 'var(--card)', borderRadius: '13px', padding: '7px 7px 7px 16px', transition: 'background-color 0.35s, border-color 0.35s' }">
              <textarea v-model="input" @keydown="onKey" placeholder="向伴侣提问……" :style="{ flex: 1, minWidth: 0, height: '44px', background: 'transparent', border: 'none', outline: 'none', resize: 'none', padding: '11px 0', boxSizing: 'border-box', fontSize: '14px', lineHeight: 1.6, color: 'var(--ink)' }"></textarea>
              <button @click="sendText(input)" :style="{ height: '38px', flex: 'none', padding: '0 20px', border: 'none', borderRadius: '9px', background: hasText ? 'var(--acc)' : 'var(--card2)', color: hasText ? '#FFFDF8' : 'var(--mut)', fontSize: '13.5px', fontWeight: 500, cursor: hasText ? 'pointer' : 'not-allowed', transition: 'background-color 0.2s, color 0.2s' }">发送</button>
            </div>
            <div v-show="width >= 880" :style="{ fontSize: '11.5px', color: 'var(--mut)', opacity: 0.8 }">Enter 发送 · Shift + Enter 换行 · 伴侣只引路，不代写作业</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch, onBeforeUnmount } from 'vue'
import TopBar from '../components/TopBar.vue'
import NavLink from '../components/NavLink.vue'
import { useViewport } from '../composables/useViewport.js'

const serif = "'Noto Serif SC', serif"
const { width } = useViewport()

const SEED = [
  { role: 'user', ctx: true, text: '做题的时候我总分不清哪些名词该建成实体、哪些只是属性。比如「订单的收货地址」，到底是 Address 类，还是 Order 的一个字段？' },
  { role: 'ai', text: '问得很准——这正是名词分析法的第一道坎。判断时过两条线索：\n\n一、它有没有自己的生命周期和职责？如果收货地址需要被单独维护——用户保存多个地址、一个地址复用于多个订单——它就值得独立成类；\n\n二、有没有别的对象需要引用它？只被 Order 用一次、谁也不再关心的，做成属性就够了。\n\n另外，你 6月10日 的诊断里，这一处失分其实回溯到了前置点「用例描述」：候选实体本来就是从事件流的名词里提出来的，事件流读不稳，名词就提不准。', chips: ['出一道针对练习', '回顾前置点：用例描述', '用网上书店举个例子'] }
]

const starters = ['这一章我该从哪里开始补？', '帮我解释聚合与组合的区别', '根据诊断结果，我最该先修什么？']

const msgs = ref(SEED.slice())
const input = ref('')
const typing = ref(false)
const ctxOn = ref(true)
const scrollEl = ref(null)
let timer = null

const narrow = computed(() => width.value < 880)
const msgsPad = computed(() => (narrow.value ? '22px 16px 10px' : '32px 24px 12px'))
const composerPad = computed(() => (narrow.value ? '10px 16px 14px' : '14px 24px 16px'))
const hasText = computed(() => input.value.trim().length > 0 && !typing.value)
const lastAiIdx = computed(() => {
  for (let i = msgs.value.length - 1; i >= 0; i--) if (msgs.value[i].role === 'ai') return i
  return -1
})

const convTitle = { fontSize: '13px', lineHeight: 1.5, marginBottom: '3px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
const convSub = { fontSize: '11.5px', color: 'var(--mut)' }

function replyFor(t) {
  if (t.indexOf('练习') >= 0) return { text: '好，来一道判断式的小题：\n\n网上书店的需求里出现了四个名词——「图书」「ISBN」「购物车」「下单时间」。哪些适合建成实体？\n\n先说你的判断和理由，我再帮你对照那两条线索复盘。', chips: ['图书和购物车是实体，另外两个是属性', '我不确定，给我讲讲'] }
  if (t.indexOf('用例') >= 0 || t.indexOf('前置') >= 0) return { text: '「用例描述」是业务实体识别的直接前置点。你在入口诊断第 10 题（基本事件流）上判断有误，说明事件流的阅读方式还不稳——而候选实体恰恰是从事件流的名词里提出来的。\n\n建议先花十分钟回看《用例描述》讲义第 3 节「事件流的写法」，再回到这里继续。', chips: ['打开知识点：用例描述', '直接做针对练习'] }
  if (t.indexOf('书店') >= 0 || t.indexOf('例子') >= 0) return { text: '以「读者下单购书」这条用例走一遍名词分析。事件流里出现的名词：读者、图书、购物车、订单、收货地址、ISBN、下单时间。\n\n逐个过两条线索——读者、图书、购物车、订单都有自己的状态与职责，是实体；ISBN 是图书的标识属性；下单时间只是订单的一个字段。收货地址最微妙：若支持地址簿复用，它就该独立成类。\n\n判断依据从来不在名词本身，而在业务怎么用它。', chips: ['出一道针对练习'] }
  if (t.indexOf('图书和购物车') >= 0) return { text: '判断正确，理由也站得住：图书和购物车有自己的状态与职责；ISBN 与下单时间依附于别的对象存在。\n\n这道题对应的失分点可以视为已修复。我把这次练习记入你的掌握度——「业务实体识别」从 52% 提到了 61%，地图上它的颜色会随之变化。继续保持。', chips: ['再来一题', '回到染色地图看看'] }
  return { text: '先说我的看法：判断一个概念要不要建模，先看业务里有没有人关心它的「变化」——有人关心，它就有状态，就值得成类。\n\n如果你手里有具体的题目或场景，贴给我，我们对着场景一步步拆。', chips: ['出一道针对练习', '用网上书店举个例子'] }
}

function sendText(text) {
  const t = (text || '').trim()
  if (!t || typing.value) return
  msgs.value = msgs.value.concat([{ role: 'user', text: t }])
  input.value = ''
  typing.value = true
  timer = setTimeout(() => {
    const r = replyFor(t)
    msgs.value = msgs.value.concat([{ role: 'ai', text: r.text, chips: r.chips }])
    typing.value = false
  }, 1300)
}

function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendText(input.value)
  }
}

function newChat() {
  if (timer) clearTimeout(timer)
  msgs.value = []
  typing.value = false
  input.value = ''
  ctxOn.value = false
}

watch([() => msgs.value.length, typing], () => {
  nextTick(() => {
    const el = scrollEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
})

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<style scoped>
@keyframes wjPulse2 {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 1; }
}
</style>
