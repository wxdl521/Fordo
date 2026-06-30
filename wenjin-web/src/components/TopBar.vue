<template>
  <div
    :style="{
      height: compact ? '56px' : '60px',
      flex: 'none',
      display: 'flex',
      alignItems: 'center',
      gap: '14px',
      padding: compact ? '0 20px' : '0 24px',
      borderBottom: '1px solid var(--line)',
      background: 'var(--bg)',
      transition: 'background-color 0.35s, border-color 0.35s',
      zIndex: 100
    }"
  >
    <!-- 品牌 -->
    <router-link to="/" :style="{ textDecoration: 'none', color: 'inherit' }">
      <span :style="{ fontFamily: serif, fontSize: compact ? '20px' : '22px', fontWeight: 600, letterSpacing: '3px', whiteSpace: 'nowrap', flex: 'none', cursor: 'pointer' }">问津</span>
    </router-link>

    <!-- 教师端标记 -->
    <span
      v-if="isTeacher"
      :style="{ fontSize: '11px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '2px 9px', flex: 'none' }"
    >教师端</span>

    <!-- 分隔线 -->
    <div :style="{ width: '1px', height: compact ? '16px' : '18px', background: 'var(--line)', flex: 'none' }"></div>

    <!-- 导航链接 -->
    <nav :style="{ display: 'flex', alignItems: 'center', gap: '4px', flex: 'none' }">
      <template v-if="isTeacher">
        <router-link
          v-for="link in teacherLinks"
          :key="link.to"
          :to="link.to"
          :style="navLinkStyle($route.path === link.to)"
        >{{ link.label }}</router-link>
      </template>
      <template v-else>
        <router-link
          v-for="link in studentLinks"
          :key="link.to"
          :to="{ path: link.to, query: link.query }"
          :style="navLinkStyle($route.path === link.to)"
        >{{ link.label }}</router-link>
      </template>
    </nav>

    <!-- 右侧：slot + 主题切换 + 退出 -->
    <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '10px' }">
      <slot />
      <ThemeToggle :small="compact" />
      <button
        @click="handleLogout"
        :style="logoutBtnStyle"
        title="退出登录"
      >退出</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import ThemeToggle from './ThemeToggle.vue'
import { useStudentCourse } from '../composables/useStudentCourse.js'

const router = useRouter()
const route = useRoute()

const serif = "'Noto Serif SC', serif"

defineProps({
  compact: { type: Boolean, default: false }
})

// 判断是否教师端
const isTeacher = computed(() => route.path.startsWith('/teacher'))

const { courseId: studentCourseId } = useStudentCourse()

// 学生端导航（携带当前课程，避免误用默认课程 1）
const studentLinks = computed(() => {
  const query = studentCourseId.value ? { courseId: studentCourseId.value } : {}
  return [
    { to: '/map', label: '染色地图', query },
    { to: '/diagnostic', label: '入口诊断', query },
    { to: '/result', label: '诊断结果', query },
    { to: '/path', label: '学习路径', query },
    { to: '/companion', label: 'AI 伴侣', query },
    { to: '/growth', label: '成长档案', query }
  ]
})

// 教师端导航
const teacherLinks = [
  { to: '/teacher/graph', label: '图谱审核' },
  { to: '/teacher/questions', label: '题目审核' },
  { to: '/teacher/dashboard', label: '学情看板' }
]

// 导航链接样式
function navLinkStyle(active) {
  return {
    fontSize: '13px',
    fontWeight: active ? 600 : 400,
    color: active ? 'var(--accent)' : 'var(--mut)',
    textDecoration: 'none',
    padding: '4px 10px',
    borderRadius: '6px',
    transition: 'color 0.2s, background 0.2s',
    whiteSpace: 'nowrap',
    flex: 'none'
  }
}

// 退出按钮样式
const logoutBtnStyle = {
  fontSize: '12px',
  color: 'var(--mut)',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '6px',
  padding: '4px 10px',
  cursor: 'pointer',
  transition: 'color 0.2s, border-color 0.2s',
  whiteSpace: 'nowrap',
  flex: 'none'
}

// 退出登录
function handleLogout() {
  localStorage.removeItem('wj_user')
  localStorage.removeItem('wj_token')
  router.push('/')
}
</script>
