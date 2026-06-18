<template>
  <div :style="{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }">
    <!-- 全局顶栏：登录页隐藏 -->
    <TopBar v-if="showTopBar" />

    <!-- 路由出口 -->
    <router-view v-slot="{ Component }">
      <div :style="{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, overflow: 'hidden' }">
        <transition name="page-fade" mode="out-in">
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
/* 全局过渡 */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.25s ease;
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}
</style>
