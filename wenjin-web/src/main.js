import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { router } from './router/index.js'
import { initTheme } from './composables/useTheme.js'
import App from './App.vue'
import './styles/tokens.css'

initTheme()

createApp(App).use(createPinia()).use(router).mount('#app')
