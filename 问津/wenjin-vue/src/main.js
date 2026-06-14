import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import { initTheme } from './composables/useTheme.js'
import './styles/tokens.css'

initTheme()

const app = createApp(App)
app.use(router)
router.isReady().then(() => app.mount('#app'))
