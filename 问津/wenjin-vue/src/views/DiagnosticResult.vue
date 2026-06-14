<template>
  <div :style="{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)' }">
    <TopBar subtitle="软件工程 · 诊断结果" :subtitle-hidden="width < 560">
      <NavLink to="/path">学习路径</NavLink>
      <NavLink to="/map">染色地图</NavLink>
    </TopBar>

    <div :style="{ flex: 1, width: '100%', maxWidth: '1064px', margin: '0 auto', boxSizing: 'border-box', padding: mainPad, animation: 'wjFadeUp 0.55s cubic-bezier(0.22,1,0.36,1) both' }">

      <div :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '16px', display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '6px 10px' }">
        <span :style="{ whiteSpace: 'nowrap' }">入口诊断 · 第 1 次</span><span :style="{ opacity: 0.5 }">·</span>
        <span :style="{ whiteSpace: 'nowrap' }">6月10日 21:32</span><span :style="{ opacity: 0.5 }">·</span>
        <span :style="{ whiteSpace: 'nowrap' }">25 题 · 用时 14 分 28 秒</span>
      </div>

      <!-- 结论卡 -->
      <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: cardPad, marginBottom: '24px', transition: 'background-color 0.35s, border-color 0.35s' }">
        <div :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)', marginBottom: '14px' }">诊断结论</div>
        <div :style="{ fontFamily: serif, fontSize: titleSize, fontWeight: 600, lineHeight: 1.4, marginBottom: '10px' }">你卡在「领域类图绘制」</div>
        <div :style="{ fontSize: '15px', color: 'var(--mut)', lineHeight: 1.8, maxWidth: '620px', marginBottom: '28px' }">根本原因更可能是前置点「用例图绘制」掌握薄弱，而不是这个知识点本身——参与者与系统边界识别不清，业务实体便提取困难，领域类图自然无从下手。</div>

        <!-- 回溯链 -->
        <div :style="{ display: 'flex', flexDirection: chainDir, alignItems: 'stretch', gap: '12px', marginBottom: '30px' }">
          <div :style="{ position: 'relative', background: 'var(--card2)', border: '1.5px solid var(--acc)', borderRadius: '10px', padding: '14px 18px', minWidth: '150px', boxSizing: 'border-box' }">
            <div :style="{ position: 'absolute', top: '-9px', left: '12px', background: 'var(--acc)', color: '#FFFDF8', fontSize: '10.5px', letterSpacing: '1px', borderRadius: '5px', padding: '2px 7px' }">根因</div>
            <div :style="{ fontSize: '13.5px', fontWeight: 500, marginBottom: '6px' }">用例图绘制</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '6px' }">
              <span :style="{ fontSize: '19px', fontWeight: 600, color: 'var(--warn)' }">48%</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">薄弱</span>
            </div>
          </div>
          <div :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '15px' }">{{ chainArrow }}</div>
          <div :style="{ background: 'var(--card2)', border: '1px solid var(--line)', borderRadius: '10px', padding: '14px 18px', minWidth: '150px', boxSizing: 'border-box' }">
            <div :style="{ fontSize: '13.5px', fontWeight: 500, marginBottom: '6px' }">业务实体识别</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '6px' }">
              <span :style="{ fontSize: '19px', fontWeight: 600, color: 'var(--warn)' }">52%</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">薄弱</span>
            </div>
          </div>
          <div :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '15px' }">{{ chainArrow }}</div>
          <div :style="{ position: 'relative', background: 'var(--card2)', border: '1px solid var(--line)', borderRadius: '10px', padding: '14px 18px', minWidth: '150px', boxSizing: 'border-box' }">
            <div :style="{ position: 'absolute', top: '-9px', left: '12px', background: 'var(--card)', border: '1px solid var(--acc)', color: 'var(--acc)', fontSize: '10.5px', letterSpacing: '1px', borderRadius: '5px', padding: '1px 7px' }">当前卡点</div>
            <div :style="{ fontSize: '13.5px', fontWeight: 500, marginBottom: '6px' }">领域类图绘制</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '6px' }">
              <span :style="{ fontSize: '19px', fontWeight: 600, color: 'var(--acc)' }">34%</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">待突破</span>
            </div>
          </div>
        </div>

        <div :style="{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }">
          <router-link to="/path" class="wj-btn-acc" :style="{ height: '42px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 26px', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '14px', fontWeight: 500, whiteSpace: 'nowrap', textDecoration: 'none' }">生成学习路径</router-link>
          <router-link to="/map?root=1" class="wj-hover-card2" :style="{ height: '42px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 18px', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13.5px', textDecoration: 'none', whiteSpace: 'nowrap' }">在地图上查看回溯链</router-link>
        </div>
      </div>

      <!-- 双栏 -->
      <div :style="{ display: 'grid', gridTemplateColumns: gridCols, gap: '24px', alignItems: 'stretch' }">

        <!-- 判断依据 -->
        <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '26px 30px', transition: 'background-color 0.35s, border-color 0.35s' }">
          <div :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)', marginBottom: '18px' }">判断依据</div>
          <div :style="{ display: 'flex', flexDirection: 'column' }">
            <div v-for="(b, i) in bases" :key="i" :style="{ display: 'flex', gap: '16px', padding: '14px 0', borderBottom: i < bases.length - 1 ? '1px solid var(--line)' : 'none' }">
              <span :style="{ fontFamily: serif, fontSize: '15px', color: 'var(--mut)', flex: 'none', paddingTop: '1px' }">{{ b.ord }}</span>
              <div :style="{ flex: 1 }">
                <div :style="{ fontSize: '14px', lineHeight: 1.65, marginBottom: '4px' }" v-html="b.title"></div>
                <div :style="{ fontSize: '12.5px', color: 'var(--mut)' }">{{ b.sub }}</div>
              </div>
              <span :style="{ fontSize: '19px', fontWeight: 600, color: b.color, flex: 'none' }">{{ b.pct }}</span>
            </div>
          </div>
        </div>

        <!-- 本次诊断 -->
        <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '26px 28px', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', transition: 'background-color 0.35s, border-color 0.35s' }">
          <div :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)', marginBottom: '18px' }">本次诊断</div>
          <div :style="{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '13.5px', paddingBottom: '18px', borderBottom: '1px solid var(--line)' }">
            <div :style="{ display: 'flex', justifyContent: 'space-between' }"><span :style="{ color: 'var(--mut)' }">题目数</span><span :style="{ fontWeight: 500 }">25 题</span></div>
            <div :style="{ display: 'flex', justifyContent: 'space-between' }"><span :style="{ color: 'var(--mut)' }">用时</span><span :style="{ fontWeight: 500 }">14 分 28 秒</span></div>
            <div :style="{ display: 'flex', justifyContent: 'space-between' }"><span :style="{ color: 'var(--mut)' }">覆盖知识点</span><span :style="{ fontWeight: 500 }">18 / 42</span></div>
          </div>

          <div :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)', margin: '18px 0 14px' }">掌握度分布</div>
          <div :style="{ display: 'flex', flexDirection: 'column', gap: '12px', marginBottom: '22px' }">
            <div v-for="d in dist" :key="d.label">
              <div :style="{ display: 'flex', justifyContent: 'space-between', fontSize: '12.5px', marginBottom: '5px' }"><span>{{ d.label }}</span><span :style="{ color: 'var(--mut)' }">{{ d.count }}</span></div>
              <div :style="{ height: '6px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden' }"><div :style="{ height: '100%', width: d.w, background: d.color, borderRadius: '99px' }"></div></div>
            </div>
          </div>

          <router-link to="/diagnostic" class="wj-hover-card2" :style="{ marginTop: 'auto', width: '100%', height: '38px', flex: 'none', boxSizing: 'border-box', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--mut)', fontSize: '13px', textDecoration: 'none' }">重新诊断</router-link>
        </div>
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
const cardPad = computed(() => (narrow.value ? '24px 20px 24px' : '36px 40px 32px'))
const titleSize = computed(() => (narrow.value ? '24px' : '32px'))
const chainDir = computed(() => (narrow.value ? 'column' : 'row'))
const chainArrow = computed(() => (narrow.value ? '↓' : '→'))
const gridCols = computed(() => (narrow.value ? '1fr' : '1fr 360px'))

const bases = [
  { ord: '一', title: '「领域类图绘制」6 道题答错 4 道，错误集中在<span style="font-weight:500">实体识别与关系判断</span>', sub: '非画图规范问题，指向更上游的概念理解', pct: '67%', color: 'var(--acc)' },
  { ord: '二', title: '前置点「用例图绘制」测得掌握度 48%，失分集中在<span style="font-weight:500">参与者与系统边界识别</span>', sub: '该能力恰是实体提取的直接基础', pct: '48%', color: 'var(--warn)' },
  { ord: '三', title: '更上游的「业务流程分析」已掌握(83%)，回溯到「用例图绘制」为止', sub: '说明问题不在业务理解，而在用例建模这一步', pct: '83%', color: 'var(--ok)' }
]

const dist = [
  { label: '已掌握', count: '16 个', w: '38%', color: 'var(--ok)' },
  { label: '薄弱 · 待修', count: '6 个', w: '14%', color: 'var(--warn)' },
  { label: '未学', count: '20 个', w: '48%', color: 'var(--dim)' }
]
</script>
