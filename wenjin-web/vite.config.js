import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端开发服务器：端口 5173，/api 请求代理到后端 8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 图谱抽取(竖长图多片串行视觉转写)可能跑数分钟,放宽代理超时与前端 axios 一致,
        // 否则代理会先于后端断开连接,前端拿到 502/ECONNRESET 而非真正结果。
        timeout: 600000,
        proxyTimeout: 600000
      }
    }
  }
})
