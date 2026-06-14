<template>
  <div :style="{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)' }">
    <TopBar compact subtitle="软件工程 · 成长档案" :subtitle-hidden="width < 600">
      <NavLink v-show="width >= 760" to="/path">学习路径</NavLink>
      <NavLink to="/map">染色地图</NavLink>
    </TopBar>

    <div :style="{ flex: 1, width: '100%', maxWidth: '1024px', margin: '0 auto', boxSizing: 'border-box', padding: mainPad, animation: 'wjFadeUp 0.45s ease both' }">

      <!-- 头部 -->
      <div :style="{ marginBottom: '24px' }">
        <div :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '12px' }">林晚舟 · 自 6月10日 入口诊断起</div>
        <div :style="{ fontFamily: serif, fontSize: titleSize, fontWeight: 600, lineHeight: 1.4, marginBottom: '22px' }">你的地图，正在变绿</div>
        <div :style="{ display: 'grid', gridTemplateColumns: statCols, gap: '12px' }">
          <div :style="statCard">
            <div :style="statLabel">整体掌握度</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '9px' }">
              <span :style="{ fontSize: '28px', fontWeight: 600, color: 'var(--acc)' }">65%</span>
              <span :style="{ fontSize: '12px', color: 'var(--ok)', background: 'var(--okSoft)', borderRadius: '999px', padding: '2px 9px' }">较诊断时 +13</span>
            </div>
          </div>
          <div :style="statCard">
            <div :style="statLabel">已掌握节点</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '9px' }">
              <span :style="{ fontSize: '28px', fontWeight: 600 }">22<span :style="{ fontSize: '15px', color: 'var(--mut)', fontWeight: 400 }"> / 42</span></span>
              <span :style="{ fontSize: '12px', color: 'var(--mut)' }">诊断时 16</span>
            </div>
          </div>
          <div :style="statCard">
            <div :style="statLabel">待修薄弱点</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '9px' }">
              <span :style="{ fontSize: '28px', fontWeight: 600, color: 'var(--warn)' }">5</span>
              <span :style="{ fontSize: '12px', color: 'var(--mut)' }">诊断时 6</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 掌握度曲线 -->
      <div :style="{ ...card, marginBottom: '24px' }">
        <div :style="{ display: 'flex', alignItems: 'baseline', gap: '12px', marginBottom: '18px', flexWrap: 'wrap' }">
          <span :style="sectionLabel">掌握度曲线</span>
          <span :style="{ fontSize: '12px', color: 'var(--mut)', opacity: 0.8, marginLeft: 'auto' }">按学习活动记录</span>
        </div>
        <svg viewBox="0 0 720 250" :style="{ width: '100%', height: 'auto', display: 'block' }">
          <line x1="34" y1="20" x2="690" y2="20" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="34" y1="87" x2="690" y2="87" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="34" y1="153" x2="690" y2="153" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 5" opacity="0.7" />
          <line x1="34" y1="220" x2="690" y2="220" stroke="var(--line)" stroke-width="1" />
          <text x="704" y="24" text-anchor="end" font-size="10.5" fill="var(--mut)">70%</text>
          <text x="704" y="91" text-anchor="end" font-size="10.5" fill="var(--mut)">60%</text>
          <text x="704" y="157" text-anchor="end" font-size="10.5" fill="var(--mut)">50%</text>
          <path d="M 40 140 L 200 120 L 360 100 L 520 80 L 680 53 L 680 220 L 40 220 Z" fill="var(--ok)" opacity="0.08" />
          <polyline points="40,140 200,120 360,100 520,80 680,53" fill="none" stroke="var(--ok)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
          <circle cx="40" cy="140" r="3.5" fill="var(--ok)" />
          <circle cx="200" cy="120" r="3.5" fill="var(--ok)" />
          <circle cx="360" cy="100" r="3.5" fill="var(--ok)" />
          <circle cx="520" cy="80" r="3.5" fill="var(--ok)" />
          <circle cx="680" cy="53" r="5" fill="var(--acc)" />
          <text x="668" y="42" text-anchor="end" font-size="13" font-weight="600" fill="var(--acc)">65%</text>
          <text x="48" y="162" font-size="11" fill="var(--mut)">入口诊断 52%</text>
          <text x="40" y="242" font-size="11" fill="var(--mut)">6月10日</text>
          <text x="440" y="242" text-anchor="middle" font-size="11" fill="var(--mut)">6月11日</text>
          <text x="680" y="242" text-anchor="end" font-size="11" fill="var(--mut)">今天</text>
        </svg>
        <div :style="{ fontSize: '12px', color: 'var(--mut)', lineHeight: 1.7, marginTop: '10px' }">每完成一次练习、看完一份资源，问津都会重估相关节点的掌握度。</div>
      </div>

      <!-- 前后对比 -->
      <div :style="{ ...card, marginBottom: '28px' }">
        <div :style="{ display: 'flex', alignItems: 'baseline', gap: '12px', marginBottom: '20px', flexWrap: 'wrap' }">
          <span :style="sectionLabel">地图前后对比</span>
          <span :style="{ fontSize: '12px', color: 'var(--ok)', background: 'var(--okSoft)', borderRadius: '999px', padding: '2px 10px', marginLeft: 'auto' }">+6 个节点转绿</span>
        </div>

        <div :style="{ display: 'grid', gridTemplateColumns: compareCols, gap: '20px', marginBottom: '18px' }">
          <div v-for="(p, pi) in panels" :key="pi" :style="{ border: '1px solid var(--line)', borderRadius: '12px', padding: '16px 18px', boxSizing: 'border-box' }">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', marginBottom: '16px', flexWrap: 'wrap' }">
              <span :style="{ fontSize: '13px', fontWeight: 600 }">{{ p.title }}</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)', marginLeft: 'auto' }">{{ p.sub }}</span>
            </div>
            <div :style="{ display: 'flex', flexDirection: 'column', gap: '11px' }">
              <div v-for="(ch, ci) in p.chapters" :key="ci" :style="{ display: 'flex', alignItems: 'center', gap: '12px' }">
                <span :style="{ width: '96px', flex: 'none', fontSize: '12px', color: 'var(--mut)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }">{{ ch.name }}</span>
                <div :style="{ display: 'flex', flexWrap: 'wrap', gap: '5px' }">
                  <span v-for="(dt, di) in ch.dots" :key="di" :style="{ width: '12px', height: '12px', borderRadius: '50%', background: dt.bg, boxShadow: dt.ring, transition: 'background-color 0.35s' }"></span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div :style="{ display: 'flex', gap: '18px', flexWrap: 'wrap', alignItems: 'center' }">
          <span v-for="(lg, li) in legend" :key="li" :style="{ display: 'inline-flex', alignItems: 'center', gap: '7px', fontSize: '12px', color: 'var(--mut)' }"><span :style="{ width: '10px', height: '10px', borderRadius: '50%', background: lg.c }"></span>{{ lg.t }}</span>
          <router-link to="/map" class="wj-underline" :style="{ marginLeft: 'auto', fontSize: '12.5px', color: 'var(--mut)', textDecoration: 'underline', textUnderlineOffset: '3px' }">在地图上查看</router-link>
        </div>
      </div>

      <!-- 成就时刻 -->
      <div :style="{ textAlign: 'center', padding: '8px 0 44px' }">
        <div :style="{ fontFamily: serif, fontSize: '17px', letterSpacing: '4px', marginBottom: '10px' }">来路渐明，前路可期</div>
        <div :style="{ fontSize: '12.5px', color: 'var(--mut)' }">两天里，六个节点由琥珀与灰，转为苍绿。</div>
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
const narrow = computed(() => width.value < 720)

const mainPad = computed(() => (narrow.value ? '26px 18px 8px' : '40px 24px 8px'))
const titleSize = computed(() => (narrow.value ? '24px' : '30px'))
const statCols = computed(() => (width.value < 820 ? '1fr' : '1fr 1fr 1fr'))
const compareCols = computed(() => (narrow.value ? '1fr' : '1fr 1fr'))
const cardPad = computed(() => (narrow.value ? '18px 16px' : '22px 24px'))

const card = computed(() => ({
  background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px',
  padding: cardPad.value, boxSizing: 'border-box', transition: 'background-color 0.35s, border-color 0.35s'
}))
const statCard = {
  background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px',
  padding: '16px 18px', boxSizing: 'border-box', transition: 'background-color 0.35s, border-color 0.35s'
}
const statLabel = { fontSize: '11.5px', letterSpacing: '2px', color: 'var(--mut)', marginBottom: '10px' }
const sectionLabel = { fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)' }

const CH = [
  { name: '软件工程概述', b: 'ggggggg', a: 'ggggggg' },
  { name: '需求确定', b: 'ggggwwwd', a: 'gggggwwd' },
  { name: '系统分析', b: 'ggwwcdddd', a: 'ggwwcdddd' },
  { name: '系统设计', b: 'gddddddd', a: 'ggggggdd' },
  { name: '实现与测试', b: 'ggddddd', a: 'ggddddd' },
  { name: '软件维护', b: 'ddd', a: 'ddd' }
]
const COLORS = { g: 'var(--ok)', w: 'var(--warn)', d: 'var(--dim)', c: 'var(--acc)' }
function dotsOf(code) {
  return code.split('').map((ch) => ({
    bg: COLORS[ch] || 'var(--dim)',
    ring: ch === 'c' ? '0 0 0 2.5px var(--accSoft)' : 'none'
  }))
}

const panels = [
  { title: '6月10日 · 诊断时', sub: '已掌握 16 · 薄弱 6 · 未学 20', chapters: CH.map((c) => ({ name: c.name, dots: dotsOf(c.b) })) },
  { title: '今天', sub: '已掌握 22 · 薄弱 5 · 未学 15', chapters: CH.map((c) => ({ name: c.name, dots: dotsOf(c.a) })) }
]

const legend = [
  { c: 'var(--ok)', t: '已掌握' },
  { c: 'var(--warn)', t: '薄弱 · 待修' },
  { c: 'var(--dim)', t: '未学' },
  { c: 'var(--acc)', t: '当前位置' }
]
</script>
