import { createApp } from 'vue'
import { pinia } from './store/index.js'
import { useSessionStore } from './store/session.js'
import { useCourseStore } from './store/course.js'
import { router } from './router/index.js'
import { initTheme } from './composables/useTheme.js'
import App from './App.vue'
import './styles/tokens.css'

initTheme()

const app = createApp(App)
app.use(pinia)

// 启动时从 localStorage 恢复会话与课程上下文
useSessionStore(pinia).hydrate()
useCourseStore(pinia).hydrate()

app.use(router).mount('#app')
