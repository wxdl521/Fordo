import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base 设为相对路径，便于构建产物直接以文件方式打开或部署到任意子路径
export default defineConfig({
  base: './',
  plugins: [vue()],
  server: { port: 5173, open: true }
})
