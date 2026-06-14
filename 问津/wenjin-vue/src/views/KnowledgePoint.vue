<template>
  <div :style="{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)' }">
    <TopBar subtitle="软件工程 · 知识点详情" :subtitle-hidden="width < 560">
      <NavLink v-show="width >= 480" to="/path">学习路径</NavLink>
      <NavLink to="/map">染色地图</NavLink>
    </TopBar>

    <div :style="{ flex: 1, width: '100%', maxWidth: '1024px', margin: '0 auto', boxSizing: 'border-box', padding: mainPad, animation: 'wjFadeUp 0.55s cubic-bezier(0.22,1,0.36,1) both' }">

      <!-- 面包屑 -->
      <div :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '18px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }">
        <router-link to="/path" class="wj-underline" :style="{ color: 'var(--mut)', textDecoration: 'none' }">学习路径</router-link>
        <span :style="{ opacity: 0.5 }">/</span>
        <span>步骤 2</span>
        <span :style="{ opacity: 0.5 }">/</span>
        <span :style="{ color: 'var(--ink)' }">业务实体识别</span>
      </div>

      <!-- 头部卡 -->
      <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: headPad, marginBottom: '24px', transition: 'background-color 0.35s, border-color 0.35s' }">
        <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px', flexWrap: 'wrap' }">
          <span :style="{ fontSize: '11.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '3px 10px' }">系统分析 · 领域模型</span>
          <span :style="{ fontSize: '11.5px', color: 'var(--acc)', border: '1px solid var(--acc)', borderRadius: '999px', padding: '3px 10px' }">考核重点</span>
        </div>
        <div :style="{ fontFamily: serif, fontSize: titleSize, fontWeight: 600, lineHeight: 1.4, marginBottom: '10px' }">业务实体识别</div>
        <div :style="{ fontSize: '14.5px', color: 'var(--mut)', lineHeight: 1.8, maxWidth: '600px', marginBottom: '22px' }">从用例与业务描述中提取候选业务实体——名词分析、职责归属、去伪存真。实体提对了，领域类图就成了画出来的事，而不是猜出来的事。</div>

        <div :style="{ display: 'flex', gap: statGap, flexWrap: 'wrap', alignItems: 'stretch' }">
          <div :style="{ flex: 1, minWidth: '200px', background: 'var(--card2)', borderRadius: '10px', padding: '14px 18px', boxSizing: 'border-box' }">
            <div :style="{ fontSize: '11.5px', letterSpacing: '2px', color: 'var(--mut)', marginBottom: '8px' }">掌握度</div>
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '8px', marginBottom: '8px' }">
              <span :style="{ fontSize: '26px', fontWeight: 600, color: 'var(--warn)' }">52%</span>
              <span :style="{ fontSize: '12px', color: 'var(--warn)', background: 'var(--warnSoft)', borderRadius: '999px', padding: '2px 9px' }">薄弱 · 待修</span>
            </div>
            <div :style="{ height: '5px', background: 'var(--line)', borderRadius: '99px', overflow: 'hidden', marginBottom: '6px' }">
              <div :style="{ height: '100%', width: '52%', background: 'var(--warn)', borderRadius: '99px' }"></div>
            </div>
            <div :style="{ fontSize: '11.5px', color: 'var(--mut)' }">来自 6月10日 入口诊断</div>
          </div>
          <div :style="{ flex: 1, minWidth: '200px', background: 'var(--card2)', borderRadius: '10px', padding: '14px 18px', boxSizing: 'border-box' }">
            <div :style="{ fontSize: '11.5px', letterSpacing: '2px', color: 'var(--mut)', marginBottom: '8px' }">基本信息</div>
            <div :style="{ display: 'flex', flexDirection: 'column', gap: '7px', fontSize: '12.5px' }">
              <div :style="{ display: 'flex', justifyContent: 'space-between' }"><span :style="{ color: 'var(--mut)' }">难度</span><span :style="{ letterSpacing: '2px' }">●●●○○</span></div>
              <div :style="{ display: 'flex', justifyContent: 'space-between' }"><span :style="{ color: 'var(--mut)' }">认知层级</span><span>分析</span></div>
              <div :style="{ display: 'flex', justifyContent: 'space-between' }"><span :style="{ color: 'var(--mut)' }">在路径中</span><span>第 2 / 4 步</span></div>
            </div>
          </div>
        </div>
      </div>

      <!-- 双栏 -->
      <div :style="{ display: 'grid', gridTemplateColumns: gridCols, gap: '24px' }">

        <!-- 学习资源 -->
        <div :style="cardBox">
          <div :style="sectionLabel">学习资源</div>
          <div :style="{ display: 'flex', flexDirection: 'column', gap: '8px' }">
            <div v-for="(r, i) in resources" :key="i" class="wj-res-row" :style="{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 14px', background: 'var(--card2)', borderRadius: '9px', cursor: 'pointer' }">
              <span :style="{ fontSize: '11px', letterSpacing: '1px', color: r.accent ? 'var(--acc)' : 'var(--mut)', border: '1px solid ' + (r.accent ? 'var(--acc)' : 'var(--line)'), borderRadius: '5px', padding: '2px 7px', flex: 'none' }">{{ r.tag }}</span>
              <div :style="{ flex: 1, minWidth: 0 }">
                <div :style="{ fontSize: '13.5px', marginBottom: r.prog ? '3px' : 0 }">{{ r.name }}</div>
                <div v-if="r.prog" :style="{ height: '3px', background: 'var(--line)', borderRadius: '99px', overflow: 'hidden', maxWidth: '220px' }"><div :style="{ height: '100%', width: r.prog, background: 'var(--acc)', borderRadius: '99px' }"></div></div>
              </div>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)', flex: 'none' }">{{ r.meta }}</span>
            </div>
          </div>
        </div>

        <!-- 前置知识点 -->
        <div :style="cardBox">
          <div :style="sectionLabel">前置知识点</div>
          <div :style="{ display: 'flex', flexDirection: 'column', gap: '8px' }">
            <div class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '10px', padding: '10px 12px', border: '1px solid var(--line)', borderRadius: '9px', cursor: 'pointer' }">
              <span :style="{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--ok)', flex: 'none' }"></span>
              <span :style="{ fontSize: '13px', lineHeight: 1.4 }">用例图绘制</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--ok)', marginLeft: 'auto', flex: 'none', whiteSpace: 'nowrap' }">81% · 刚修复</span>
            </div>
            <div class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '10px', padding: '10px 12px', border: '1px solid var(--line)', borderRadius: '9px', cursor: 'pointer' }">
              <span :style="{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--warn)', flex: 'none' }"></span>
              <span :style="{ fontSize: '13px', lineHeight: 1.4 }">需求模型的构建 · 用例模型</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--warn)', marginLeft: 'auto', flex: 'none', whiteSpace: 'nowrap' }">62%</span>
            </div>
          </div>
        </div>

        <!-- 针对练习入口 -->
        <div :style="{ ...cardBox, border: '1.5px solid var(--acc)', display: 'flex', alignItems: 'center' }">
          <div :style="{ display: 'flex', alignItems: 'center', gap: '14px', flexWrap: 'wrap', flex: 1 }">
            <div :style="{ flex: 1, minWidth: '220px' }">
              <div :style="{ fontSize: '15px', fontWeight: 600, marginBottom: '5px' }">针对练习 · 实体提取 10 题</div>
              <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7 }">按你的错误模式生成：6 题实体识别 + 4 题职责判断，约 15 分钟。做完即更新掌握度。</div>
            </div>
            <router-link to="/diagnostic" class="wj-btn-acc" :style="{ height: '42px', boxSizing: 'border-box', display: 'inline-flex', alignItems: 'center', padding: '0 26px', background: 'var(--acc)', border: 'none', borderRadius: '9px', color: '#FFFDF8', fontSize: '14px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none', textDecoration: 'none' }">开始练习</router-link>
          </div>
        </div>

        <!-- 学完它能解锁 -->
        <div :style="{ ...cardBox, display: 'flex', flexDirection: 'column' }">
          <div :style="sectionLabel">学完它能解锁</div>
          <div class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '10px', padding: '10px 12px', border: '1px solid var(--line)', borderRadius: '9px', cursor: 'pointer' }">
            <span :style="{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--acc)', flex: 'none' }"></span>
            <span :style="{ fontSize: '13px', lineHeight: 1.4 }">领域类图绘制</span>
            <span :style="{ fontSize: '11.5px', color: 'var(--acc)', marginLeft: 'auto', flex: 'none', whiteSpace: 'nowrap' }">34% · 你的卡点</span>
          </div>
          <div :style="{ height: '12px', flex: 'none' }"></div>
          <router-link to="/map" class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '36px', marginTop: 'auto', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--mut)', fontSize: '12.5px', textDecoration: 'none' }">在地图上查看位置</router-link>
        </div>

        <!-- 卡住了 -->
        <div :style="{ ...cardBox, gridColumn: helpCol }">
          <div :style="{ ...sectionLabel, marginBottom: '12px' }">卡住了？</div>
          <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7, marginBottom: '12px' }">AI 伴侣已读过这个知识点和你的诊断记录，可以直接问。</div>
          <router-link to="/companion" class="wj-hover-card2" :style="{ width: '100%', height: '36px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '12.5px', textDecoration: 'none' }">问 AI 伴侣</router-link>
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
const narrow = computed(() => width.value < 800)

const mainPad = computed(() => (narrow.value ? '24px 16px 48px' : '36px 32px 64px'))
const headPad = computed(() => (narrow.value ? '22px 18px 20px' : '30px 34px 28px'))
const cardPad = computed(() => (narrow.value ? '18px 16px 18px' : '22px 26px 22px'))
const titleSize = computed(() => (narrow.value ? '24px' : '30px'))
const statGap = computed(() => (narrow.value ? '12px' : '16px'))
const gridCols = computed(() => (narrow.value ? '1fr' : '1fr 340px'))
const helpCol = computed(() => (narrow.value ? 'auto' : '2'))

const cardBox = computed(() => ({
  background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px',
  padding: cardPad.value, boxSizing: 'border-box', transition: 'background-color 0.35s, border-color 0.35s'
}))
const sectionLabel = { fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)', marginBottom: '16px' }

const resources = [
  { tag: '视频', name: '从用例到实体：提取的三条线索', meta: '18 分钟 · 看到 06:42', accent: true, prog: '37%' },
  { tag: '讲义', name: '名词分析法与 CRC 卡片', meta: '12 页 · 未读', accent: false },
  { tag: '案例', name: '网上书店：一次完整的实体提取', meta: '9 分钟 · 未读', accent: false }
]
</script>

<style scoped>
.wj-res-row:hover {
  background: var(--accSoft) !important;
}
</style>
