<template>
  <div class="app-shell">
    <nav class="app-nav">
      <router-link to="/diagnostic" class="nav-link">诊断</router-link>
      <router-link to="/result" class="nav-link">诊断结果</router-link>
      <router-link to="/path" class="nav-link">学习路径</router-link>
      <router-link to="/map" class="nav-link">染色地图</router-link>
      <router-link to="/companion" class="nav-link">伴侣</router-link>
      <router-link to="/growth" class="nav-link">成长</router-link>
      <router-link to="/admin" class="nav-link">管理</router-link>
      <router-link to="/teacher/graph" class="nav-link">教师端</router-link>
    </nav>
    <div class="app-content">
      <router-view />
    </div>
  </div>
</template>

<script setup>
// App.vue — thin shell: nav bar + router outlet.
// Routes: / → /diagnostic, /diagnostic → Diagnostic, /result → DiagnosticResult,
//         /path → LearningPath, /map → ColorMap, /admin → Admin
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.app-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 44px;
  flex: none;
  padding: 0 14px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  z-index: 10;
}

.nav-link {
  padding: 6px 12px;
  border-radius: 7px;
  font-size: 13px;
  color: var(--text-mut);
  text-decoration: none;
  transition: color 0.15s, background-color 0.15s;
}

.nav-link:hover {
  color: var(--text);
  background: var(--panel-2);
}

.nav-link.router-link-active {
  color: var(--accent);
  background: rgba(216, 103, 74, 0.12);
}

.app-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
