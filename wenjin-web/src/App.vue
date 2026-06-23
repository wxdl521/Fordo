<template>
  <div :style="{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }">
    <!-- 全局顶栏：登录页隐藏 -->
    <TopBar v-if="showTopBar" />

    <!-- 路由出口 -->
    <!-- 注意：不要用 <transition mode="out-in">。out-in 在「旧页 leave 完成后再挂载新页」的
         延迟挂载机制下存在已知卡死问题——某些页面（如入口诊断空态）leave 结束后新页 enter
         永不触发、组件不挂载，表现为「从别的页面切到染色地图打不开、刷新才显示」。
         改用默认（同时）模式 + 离场页绝对定位，实现交叉淡入淡出且不发生上下挤压。 -->
    <router-view v-slot="{ Component }">
      <div :style="{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, overflow: 'hidden' }">
        <transition name="page-fade">
          <component :is="Component" />
        </transition>
      </div>
    </router-view>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TopBar from './components/TopBar.vue'

const route = useRoute()

// 登录页不显示 TopBar
const showTopBar = computed(() => route.path !== '/')
</script>

<style>
/* 全局过渡（交叉淡入淡出，默认同时模式） */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.25s ease;
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}
/* 离场页脱离文档流，避免与入场页上下挤压（两个 flex:1 同时在场会各占一半高度） */
.page-fade-leave-active {
  position: absolute;
  inset: 0;
  z-index: 0;
}
</style>
