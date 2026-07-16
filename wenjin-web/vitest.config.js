import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(root, 'src')
    }
  },
  test: {
    environment: 'happy-dom',
    globals: false,
    setupFiles: ['./test/setup.js'],
    include: [
      'src/**/*.{test,spec}.{js,mjs,ts}',
      'test/**/*.{test,spec}.{js,mjs,ts}'
    ],
    exclude: [
      '**/node_modules/**',
      '**/dist/**',
      '**/e2e/**'
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov', 'html'],
      include: [
        'src/store/**',
        'src/composables/**',
        'src/api/**',
        'src/utils/**'
      ],
      exclude: [
        'src/**/__tests__/**',
        'src/**/*.{test,spec}.*',
        '**/node_modules/**'
      ],
      // 诚实阈值：门禁关键路径，可随覆盖提升上调
      thresholds: {
        lines: 80,
        statements: 80,
        branches: 70,
        functions: 70
      }
    }
  }
})
