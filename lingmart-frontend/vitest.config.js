import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 轻营前端 · Vitest 单测配置（与 Vite 开发/构建相互独立，避免互相影响）
// 运行：npm test（vitest run） / npm run test:watch
export default defineConfig({
  plugins: [vue()],
  test: {
    // jsdom 模拟浏览器环境（localStorage / DOM / History 均可用）
    environment: 'jsdom',
    // 全局注入 describe/it/expect，测试文件无需逐行 import
    globals: true,
    setupFiles: ['./test/setup.js'],
    include: ['src/**/*.spec.js']
  }
})
