<template>
  <div :style="{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)' }">
    <TopBar subtitle="软件工程 · 学习路径" :subtitle-hidden="width < 560">
      <NavLink v-show="width >= 480" to="/result">诊断结果</NavLink>
      <NavLink to="/map">染色地图</NavLink>
    </TopBar>

    <div :style="{ flex: 1, width: '100%', maxWidth: '880px', margin: '0 auto', boxSizing: 'border-box', padding: mainPad }">

      <!-- 路径头 -->
      <div :style="{ marginBottom: '32px' }">
        <div :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '14px', display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }">
          <span>由 6月10日 入口诊断生成</span><span :style="{ opacity: 0.5 }">·</span>
          <router-link to="/result" class="wj-underline" :style="{ color: 'var(--mut)', textDecoration: 'underline', textUnderlineOffset: '3px' }">查看诊断依据</router-link>
        </div>
        <div :style="{ fontFamily: serif, fontSize: titleSize, fontWeight: 600, lineHeight: 1.4, marginBottom: '10px' }">突破「领域类图绘制」</div>
        <div :style="{ fontSize: '14.5px', color: 'var(--mut)', lineHeight: 1.8, maxWidth: '560px' }">从根因「用例图绘制」补起，沿前置链逐步推进——根基稳了，卡点自然松动。</div>

        <div :style="{ display: 'flex', alignItems: 'center', gap: '14px', marginTop: '24px' }">
          <div :style="{ flex: 1, maxWidth: '320px', height: '6px', background: 'var(--card2)', border: '1px solid var(--line)', borderRadius: '99px', overflow: 'hidden', boxSizing: 'border-box' }">
            <div :style="{ height: '100%', width: '25%', background: 'var(--ok)', borderRadius: '99px' }"></div>
          </div>
          <span :style="{ fontSize: '12.5px', color: 'var(--mut)', whiteSpace: 'nowrap' }">四步已行其一 · 预计还需约 2.5 小时</span>
        </div>
      </div>

      <!-- 时间线 -->
      <div :style="{ position: 'relative' }">
        <div :style="{ position: 'absolute', left: '17px', top: '18px', bottom: '24px', width: '2px', background: 'var(--line)', transition: 'background-color 0.35s' }"></div>

        <!-- 步骤 1 · 已完成 -->
        <div data-comment-anchor="path-step-1" :style="{ position: 'relative', display: 'grid', gridTemplateColumns: '36px 1fr', gap: stepGap, marginBottom: '20px', animation: 'wjFadeUp 0.5s cubic-bezier(0.22,1,0.36,1) both', animationDelay: '0.05s' }">
          <div :style="{ position: 'relative', zIndex: 1, width: '36px', height: '36px', borderRadius: '50%', background: 'var(--ok)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#FFFDF8', fontSize: '15px' }">✓</div>
          <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px', padding: cardPad, transition: 'background-color 0.35s, border-color 0.35s' }">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', flexWrap: 'wrap', marginBottom: '6px' }">
              <span :style="{ fontSize: '16px', fontWeight: 600, textDecoration: 'line-through', textDecorationColor: 'var(--mut)', textDecorationThickness: '1px', color: 'var(--mut)' }">用例图绘制</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">需求确定 · 根因</span>
              <span :style="{ marginLeft: 'auto', fontSize: '12px', color: 'var(--ok)', whiteSpace: 'nowrap' }">已完成 · 掌握度 48% → 81%</span>
            </div>
            <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.7, marginBottom: '14px' }"><span :style="{ color: 'var(--ok)' }">为什么先学这个</span> — 参与者与系统边界识别是实体提取的直接基础，根因不补，后面每步都是空中楼阁。</div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }">
              <span :style="chip">视频 · 用例建模精讲 <span :style="{ opacity: 0.6 }">22 分钟</span></span>
              <span :style="chip">练习 · 边界识别 8 题 <span :style="{ opacity: 0.6 }">8 / 8 对</span></span>
              <router-link to="/knowledge" class="wj-hover-card2" :style="{ marginLeft: 'auto', height: '32px', boxSizing: 'border-box', display: 'inline-flex', alignItems: 'center', padding: '0 14px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--mut)', fontSize: '12.5px', whiteSpace: 'nowrap', textDecoration: 'none' }">回顾</router-link>
            </div>
          </div>
        </div>

        <!-- 步骤 2 · 当前 -->
        <div data-comment-anchor="path-step-2" :style="{ position: 'relative', display: 'grid', gridTemplateColumns: '36px 1fr', gap: stepGap, marginBottom: '20px', animation: 'wjFadeUp 0.5s cubic-bezier(0.22,1,0.36,1) both', animationDelay: '0.12s' }">
          <div :style="{ position: 'relative', zIndex: 1, width: '36px', height: '36px', borderRadius: '50%', background: 'var(--acc)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#FFFDF8', fontSize: '15px', fontWeight: 600, animation: 'wjPulse 2.4s ease-out infinite' }">2</div>
          <div :style="{ background: 'var(--card)', border: '1.5px solid var(--acc)', borderRadius: '12px', padding: cardPad, transition: 'background-color 0.35s' }">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', flexWrap: 'wrap', marginBottom: '6px' }">
              <router-link to="/knowledge" class="wj-hover-acc" :style="{ fontSize: '17px', fontWeight: 600, color: 'var(--ink)', textDecoration: 'none' }">业务实体识别</router-link>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">系统分析</span>
              <span :style="{ marginLeft: 'auto', fontSize: '12px', color: 'var(--acc)', whiteSpace: 'nowrap' }">当前步骤 · 掌握度 52%</span>
            </div>
            <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.7, marginBottom: '14px' }"><span :style="{ color: 'var(--acc)' }">为什么学这个</span> — 类图画不出来，多半是实体没提对。这一步教你从用例与业务描述中提取候选实体，是领域类图的全部原材料。</div>
            <div :style="{ display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '16px' }">
              <div v-for="(r, i) in step2res" :key="i" class="wj-res-row" :style="resRow">
                <span :style="{ fontSize: '11px', letterSpacing: '1px', color: r.accent ? 'var(--acc)' : 'var(--mut)', border: '1px solid ' + (r.accent ? 'var(--acc)' : 'var(--line)'), borderRadius: '5px', padding: '1px 6px', flex: 'none' }">{{ r.tag }}</span>
                <span :style="{ fontSize: '13px' }">{{ r.name }}</span>
                <span :style="{ fontSize: '11.5px', color: 'var(--mut)', marginLeft: 'auto', whiteSpace: 'nowrap' }">{{ r.meta }}</span>
              </div>
            </div>
            <router-link to="/knowledge" class="wj-btn-acc" :style="{ display: 'inline-flex', alignItems: 'center', height: '40px', boxSizing: 'border-box', padding: '0 24px', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, whiteSpace: 'nowrap', textDecoration: 'none' }">继续学习</router-link>
          </div>
        </div>

        <!-- 步骤 3 · 待开始 -->
        <div data-comment-anchor="path-step-3" :style="{ position: 'relative', display: 'grid', gridTemplateColumns: '36px 1fr', gap: stepGap, marginBottom: '20px', animation: 'wjFadeUp 0.5s cubic-bezier(0.22,1,0.36,1) both', animationDelay: '0.19s' }">
          <div :style="dimMark">3</div>
          <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px', padding: cardPad, opacity: 0.82, transition: 'background-color 0.35s, border-color 0.35s' }">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', flexWrap: 'wrap', marginBottom: '6px' }">
              <span :style="{ fontSize: '16px', fontWeight: 600 }">领域类图绘制</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">系统分析 · 你的卡点</span>
              <span :style="{ marginLeft: 'auto', fontSize: '12px', color: 'var(--mut)', whiteSpace: 'nowrap' }">待开始 · 掌握度 34%</span>
            </div>
            <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.7, marginBottom: '14px' }"><span :style="{ color: 'var(--ink)' }">为什么学这个</span> — 这就是诊断发现的卡点本身。前两步打通后再回来，实体属性与关系建模会顺手得多。</div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }">
              <span :style="chip">视频 · 25 分钟</span>
              <span :style="chip">讲义 · 16 页</span>
              <span :style="chip">练习 · 12 题</span>
            </div>
          </div>
        </div>

        <!-- 步骤 4 · 检验 -->
        <div data-comment-anchor="path-step-4" :style="{ position: 'relative', display: 'grid', gridTemplateColumns: '36px 1fr', gap: stepGap, animation: 'wjFadeUp 0.5s cubic-bezier(0.22,1,0.36,1) both', animationDelay: '0.26s' }">
          <div :style="dimMark">4</div>
          <div :style="{ background: 'var(--card)', border: '1px dashed var(--line)', borderRadius: '12px', padding: cardPad, opacity: 0.82, transition: 'background-color 0.35s, border-color 0.35s' }">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', flexWrap: 'wrap', marginBottom: '6px' }">
              <span :style="{ fontSize: '16px', fontWeight: 600 }">突破检验</span>
              <span :style="{ marginLeft: 'auto', fontSize: '12px', color: 'var(--mut)', whiteSpace: 'nowrap' }">完成前三步后解锁</span>
            </div>
            <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.7 }">一组跨越整条回溯链的混合测验（12 题），验证卡点是否真正打通——通过后，地图上这条链会一起染绿。</div>
          </div>
        </div>
      </div>

      <div :style="{ textAlign: 'center', marginTop: '48px' }">
        <div :style="{ fontFamily: serif, fontSize: '13.5px', color: 'var(--mut)', letterSpacing: '2px' }">路漫漫其修远，此处有渡口。</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import TopBar from '../components/TopBar.vue'
import NavLink from '../components/NavLink.vue'
import { useViewport } from '../composables/useViewport.js'

const serif = "'Noto Serif SC', serif"
const { width } = useViewport()
const narrow = computed(() => width.value < 760)

const mainPad = computed(() => (narrow.value ? '24px 16px 48px' : '40px 32px 64px'))
const cardPad = computed(() => (narrow.value ? '16px 16px 18px' : '20px 24px 22px'))
const stepGap = computed(() => (narrow.value ? '12px' : '18px'))
const titleSize = computed(() => (narrow.value ? '24px' : '30px'))

const chip = {
  display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px',
  color: 'var(--mut)', background: 'var(--card2)', borderRadius: '6px', padding: '5px 10px'
}
const resRow = {
  display: 'flex', alignItems: 'center', gap: '10px', padding: '9px 12px',
  background: 'var(--card2)', borderRadius: '8px', cursor: 'pointer'
}
const dimMark = {
  position: 'relative', zIndex: 1, width: '36px', height: '36px', boxSizing: 'border-box',
  borderRadius: '50%', background: 'var(--bg)', border: '2px solid var(--line)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)',
  fontSize: '14px', fontWeight: 600, transition: 'background-color 0.35s, border-color 0.35s'
}

const step2res = [
  { tag: '视频', name: '从用例到实体：提取的三条线索', meta: '18 分钟 · 看到 06:42', accent: true },
  { tag: '讲义', name: '名词分析法与 CRC 卡片', meta: '12 页', accent: false },
  { tag: '练习', name: '针对练习 · 实体提取 10 题', meta: '约 15 分钟', accent: false }
]
</script>

<style scoped>
.wj-res-row:hover {
  background: var(--accSoft) !important;
}
</style>
