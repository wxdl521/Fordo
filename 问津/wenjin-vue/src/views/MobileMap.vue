<template>
  <div :style="{ minHeight: '100vh', boxSizing: 'border-box', background: 'var(--page)', color: 'var(--ink)', padding: pagePad, transition: 'background-color 0.35s, color 0.35s' }">
    <div :style="{ maxWidth: '1080px', margin: '0 auto' }">

      <div :style="{ display: 'flex', alignItems: 'baseline', gap: '14px', flexWrap: 'wrap' }">
        <span :style="{ fontFamily: serif, fontSize: '22px', fontWeight: 600, letterSpacing: '3px' }">问津</span>
        <span :style="{ fontSize: '14px', color: 'var(--mut)' }">移动端 · 地图 Tab · 两个形态</span>
        <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '14px' }">
          <span v-show="width >= 760" :style="{ fontSize: '12px', color: 'var(--mut)', opacity: 0.8 }">点节点打开底部抽屉 · 可切换主题</span>
          <NavLink v-show="width >= 880" to="/map">桌面端地图</NavLink>
          <ThemeToggle />
        </div>
      </div>

      <div :style="{ display: 'flex', gap: '56px', justifyContent: 'center', alignItems: 'flex-start', flexWrap: 'wrap', marginTop: '36px' }">

        <!-- 形态 A -->
        <div :style="{ display: 'flex', flexDirection: 'column', gap: '16px' }">
          <div :style="{ maxWidth: '402px' }">
            <div :style="{ fontSize: '14px', fontWeight: 600 }">形态 A · 章节卡片列表</div>
            <div :style="{ fontSize: '12px', color: 'var(--mut)', marginTop: '5px', lineHeight: 1.7 }">每章一条染色进度，点开章节展开知识点行；信息密度高，找点最快。</div>
          </div>
          <IOSFrame :dark="theme === 'ink'">
            <MobilePhone form="a" :data="data" />
          </IOSFrame>
        </div>

        <!-- 形态 B -->
        <div :style="{ display: 'flex', flexDirection: 'column', gap: '16px' }">
          <div :style="{ maxWidth: '402px' }">
            <div :style="{ fontSize: '14px', fontWeight: 600 }">形态 B · 章节星图</div>
            <div :style="{ fontSize: '12px', color: 'var(--mut)', marginTop: '5px', lineHeight: 1.7 }">延续桌面端「星图」质感：8 个章节星按掌握度染色，点章节潜入该章子图。</div>
          </div>
          <IOSFrame :dark="theme === 'ink'">
            <MobilePhone form="b" :data="data" />
          </IOSFrame>
        </div>

      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, watch, ref } from 'vue'
import IOSFrame from '../components/IOSFrame.vue'
import MobilePhone from '../components/MobilePhone.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import NavLink from '../components/NavLink.vue'
import { useTheme } from '../composables/useTheme.js'
import { useViewport } from '../composables/useViewport.js'
import { useGraphData } from '../composables/useGraphData.js'

const serif = "'Noto Serif SC', serif"
const { theme } = useTheme()
const { width } = useViewport()
const { data } = useGraphData()

const pagePad = computed(() => (width.value < 560 ? '24px 16px 56px' : '40px 48px 72px'))
</script>
