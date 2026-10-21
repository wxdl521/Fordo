<template>
  <div data-testid="admin-layout" :style="rootStyle">
    <!-- 顶栏 -->
    <header data-testid="admin-topbar" :style="topbarStyle">
      <router-link to="/admin" :style="brandLinkStyle">
        <span :style="brandStyle">问津</span>
        <span :style="brandSubStyle">· 管理后台</span>
      </router-link>

      <div :style="rightGroupStyle">
        <span v-if="adminName" data-testid="admin-name" class="wj-admin-name" :style="nameStyle" :title="adminName">{{ adminName }}</span>
        <router-link
          to="/teacher/graph"
          data-testid="admin-enter-teacher"
          class="wj-admin-link"
          :style="linkBtnStyle"
        >进入教师端</router-link>
        <ThemeToggle small />
        <button
          type="button"
          data-testid="admin-logout"
          class="wj-admin-link"
          :style="logoutBtnStyle"
          title="退出登录"
          @click="handleLogout"
        >退出</button>
      </div>
    </header>

    <div class="wj-admin-body" :style="bodyStyle">
      <!-- 侧边栏 -->
      <aside data-testid="admin-sidebar" class="wj-admin-sidebar" :style="sidebarStyle">
        <nav aria-label="管理后台导航" class="wj-admin-navlist" :style="navStyle">
          <router-link
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            :data-testid="item.testid"
            class="wj-admin-nav"
            :style="navItemStyle(isActive(item))"
            :aria-current="isActive(item) ? 'page' : undefined"
          >{{ item.label }}</router-link>
        </nav>
      </aside>

      <!-- 内容 -->
      <main :style="mainStyle">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useSessionStore } from '../store/session.js'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const serif = "'Noto Serif SC', serif"

// 后续模块（课程与班级、系统配置…）往这里加
const navItems = [
  { to: '/admin/overview', label: '平台总览', testid: 'admin-nav-overview' },
  { to: '/admin/users', label: '用户管理', testid: 'admin-nav-users' },
  { to: '/admin/audit-logs', label: '操作日志', testid: 'admin-nav-audit-logs' },
  { to: '/admin/login-logs', label: '登录日志', testid: 'admin-nav-login-logs' }
]

const adminName = computed(() => {
  const user = session.user
  return (user && (user.realName || user.username)) || ''
})

function isActive(item) {
  return route.path === item.to || route.path.startsWith(item.to + '/')
}

// 退出登录：同 TopBar.handleLogout —— 清会话 + 课程上下文，回登录页
function handleLogout() {
  session.clearSession()
  session.resetRefreshGate()
  router.push('/')
}

// ── 样式 ──
const rootStyle = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg)',
  color: 'var(--ink)',
  transition: 'background-color 0.35s, color 0.35s'
}

const topbarStyle = {
  height: '56px',
  flex: 'none',
  display: 'flex',
  alignItems: 'center',
  gap: '14px',
  padding: '0 24px',
  borderBottom: '1px solid var(--line)',
  background: 'var(--bg)',
  transition: 'background-color 0.35s, border-color 0.35s',
  zIndex: 100
}

const brandLinkStyle = {
  display: 'flex',
  alignItems: 'baseline',
  gap: '8px',
  textDecoration: 'none',
  color: 'inherit',
  whiteSpace: 'nowrap'
}

const brandStyle = {
  fontFamily: serif,
  fontSize: '21px',
  fontWeight: 600,
  letterSpacing: '3px'
}

const brandSubStyle = {
  fontSize: '13px',
  color: 'var(--mut)',
  letterSpacing: '1px'
}

const rightGroupStyle = {
  marginLeft: 'auto',
  display: 'flex',
  alignItems: 'center',
  gap: '12px',
  minWidth: 0
}

const nameStyle = {
  fontSize: '13px',
  color: 'var(--mut)',
  maxWidth: '160px',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap'
}

const linkBtnStyle = {
  fontSize: '12.5px',
  color: 'var(--ink)',
  textDecoration: 'none',
  border: '1px solid var(--line)',
  borderRadius: '6px',
  padding: '4px 10px',
  whiteSpace: 'nowrap',
  flex: 'none'
}

const logoutBtnStyle = {
  fontSize: '12px',
  color: 'var(--mut)',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '6px',
  padding: '4px 10px',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
  flex: 'none'
}

const bodyStyle = {
  flex: 1,
  minHeight: 0,
  display: 'flex'
}

const sidebarStyle = {
  width: '184px',
  flex: 'none',
  borderRight: '1px solid var(--line)',
  background: 'var(--bg)',
  padding: '16px 12px',
  overflowY: 'auto',
  transition: 'background-color 0.35s, border-color 0.35s'
}

const navStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: '4px'
}

function navItemStyle(active) {
  return {
    display: 'block',
    fontSize: '13.5px',
    fontWeight: active ? 600 : 400,
    color: active ? 'var(--acc)' : 'var(--mut)',
    background: active ? 'var(--accSoft)' : 'transparent',
    textDecoration: 'none',
    padding: '8px 12px',
    borderRadius: '8px',
    whiteSpace: 'nowrap'
  }
}

const mainStyle = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  overflow: 'auto',
  background: 'var(--bg)'
}
</script>

<style scoped>
.wj-admin-link {
  transition: border-color 0.18s, color 0.18s, background-color 0.18s;
}
.wj-admin-link:hover {
  border-color: var(--acc) !important;
  color: var(--acc) !important;
}
.wj-admin-nav:not([aria-current]):hover {
  background: var(--card2) !important;
  color: var(--ink) !important;
}

/* 窄屏：侧边栏退化为顶部横向导航条 */
@media (max-width: 720px) {
  .wj-admin-body {
    flex-direction: column !important;
  }
  .wj-admin-sidebar {
    width: auto !important;
    border-right: none !important;
    border-bottom: 1px solid var(--line);
    padding: 8px 12px !important;
  }
  .wj-admin-navlist {
    flex-direction: row !important;
    overflow-x: auto;
  }
  .wj-admin-name {
    display: none;
  }
}
</style>
